package dev.pampa.fluidweather.core.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.model.CalibrationBurst
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** La pressione al mare dei provider (e la temperatura) nello stesso istante della raffica. */
data class CalibrationReference(val mslHpa: Double, val temperatureCelsius: Double?)

/**
 * La taratura iniziale: dieci minuti a 1 Hz in un foreground service, cosi' l'utente puo'
 * andarsene dalla schermata (e dall'app) mentre il sensore lavora; poi la mediana ridotta al
 * mare contro il riferimento dei provider da' il bias. Il progresso e' osservabile: la home lo
 * mostra come barra, la notifica pure.
 */
class CalibrationController(
  private val context: Context,
  private val engine: SamplingEngine,
  private val repository: PressureRepository,
  private val store: CalibrationStore,
  private val reference: suspend () -> CalibrationReference?,
) {

  data class Progress(val completedSeconds: Int, val totalSeconds: Int)

  private val _progress = MutableStateFlow<Progress?>(null)

  /** null = nessuna taratura in corso. */
  val progress: StateFlow<Progress?> = _progress.asStateFlow()

  private val _lastOutcome = MutableStateFlow<String?>(null)

  /** Com'e' finita l'ultima raffica: per le impostazioni, quando qualcosa e' andato storto. */
  val lastOutcome: StateFlow<String?> = _lastOutcome.asStateFlow()

  /** Avvia il servizio; se una raffica e' gia' in corso, non fa niente. */
  fun start() {
    if (_progress.value != null) return
    if (!engine.barometerAvailable) {
      _lastOutcome.value = "Questo dispositivo non ha il barometro."
      return
    }
    runCatching {
      ContextCompat.startForegroundService(context, Intent(context, CalibrationService::class.java))
    }.onFailure { _lastOutcome.value = "Impossibile avviare la taratura: ${it.message}" }
  }

  fun cancel() {
    context.stopService(Intent(context, CalibrationService::class.java))
  }

  /** Il lavoro vero, eseguito dal servizio: la raffica intera, poi la stima. */
  suspend fun run() {
    if (_progress.value != null) return
    val startedAt = System.currentTimeMillis()
    _progress.value = Progress(0, CalibrationBurst.DURATION_SECONDS)
    try {
      val stored = engine.collect(
        source = SampleSource.CALIBRATION,
        durationSeconds = CalibrationBurst.DURATION_SECONDS,
      ) { completed, total ->
        _progress.value = Progress(completed, total)
      }
      if (stored == 0) {
        _lastOutcome.value = "Nessuna lettura dal barometro."
        return
      }
      val samples = repository.samplesSince(startedAt).filter { it.source == SampleSource.CALIBRATION }
      val altitude = CalibrationMath.median(samples.mapNotNull { it.altitudeMeters })
      val target = runCatching { reference() }.getOrNull()
      if (target == null) {
        _lastOutcome.value = "Riferimento dei provider assente (rete?): la raffica e' in archivio, riprova la stima."
        return
      }
      val record = CalibrationMath.estimate(
        stationPressures = samples.map { it.pressureHpa },
        altitudeMeters = altitude,
        temperatureCelsius = target.temperatureCelsius,
        referenceMslHpa = target.mslHpa,
        nowMillis = System.currentTimeMillis(),
      )
      if (record == null) {
        _lastOutcome.value = "Troppo poche letture per una mediana."
        return
      }
      store.save(record)
      _lastOutcome.value = null
    } finally {
      _progress.value = null
    }
  }
}

/** Il servizio che tiene vivo il processo per i dieci minuti della raffica, con la sua barra. */
class CalibrationService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val controller = sensorRuntime().calibrationController
    startInForeground(controller.progress.value)
    scope.launch {
      controller.progress.collect { progress ->
        if (progress != null && progress.completedSeconds % 15 == 0) {
          val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
          manager.notify(NOTIFICATION_ID, notification(progress))
        }
      }
    }
    scope.launch {
      try {
        controller.run()
      } finally {
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun startInForeground(progress: CalibrationController.Progress?) {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Taratura del barometro", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Solo durante i dieci minuti della raffica iniziale."
      },
    )
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(progress), type)
  }

  private fun notification(progress: CalibrationController.Progress?): Notification {
    val total = progress?.totalSeconds ?: CalibrationBurst.DURATION_SECONDS
    val done = progress?.completedSeconds ?: 0
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_compass)
      .setContentTitle("Taratura del barometro in corso")
      .setContentText("${done / 60}:${String.format("%02d", done % 60)} di ${total / 60}:00 — puoi usare il telefono normalmente")
      .setProgress(total, done, false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  private companion object {
    const val CHANNEL_ID = "calibration"
    const val NOTIFICATION_ID = 42
  }
}
