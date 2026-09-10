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
import dev.pampa.fluidweather.core.model.CalibrationOutcome
import dev.pampa.fluidweather.core.model.PendingCalibrationBurst
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationMath
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationReferenceSample
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationSegment
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationSegments
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R

/**
 * La taratura iniziale: una raffica a 1 Hz in un foreground service, cosi' l'utente puo'
 * andarsene dalla schermata (e dall'app) mentre il sensore lavora; poi i **tratti fermi** della
 * raffica, ciascuno ridotto al mare con la propria quota e confrontato col riferimento dei
 * provider piu' vicino nel tempo e nello spazio, votano il bias.
 *
 * Non e' come funzionava prima, e la differenza e' il motivo per cui e' stata riscritta: c'era una
 * mediana sola, una quota sola (la mediana di tutte) e un riferimento solo, chiesto a raffica
 * finita. Chi in quei dieci minuti passava per tre paesi a quote diverse veniva confrontato con
 * l'ultimo, e il bias che ne usciva non descriveva nessuno dei tre — dieci metri di quota valgono
 * circa 1,2 hPa, e la taratura intera vale qualche decimo.
 *
 * Il progresso e' osservabile: la home lo mostra come barra, la notifica pure.
 */
class CalibrationController(
  private val context: Context,
  private val engine: SamplingEngine,
  private val repository: PressureRepository,
  private val store: CalibrationStore,
  /** La pressione al mare dei provider **qui e adesso**, con il suo quando e il suo dove. */
  private val reference: suspend () -> CalibrationReferenceSample?,
  private val scope: CoroutineScope,
) {

  data class Progress(
    val completedSeconds: Int,
    val totalSeconds: Int,
    /**
     * La raffica nominale e' finita ma il tempo *fermo* non basta ancora: si sta aspettando che il
     * telefono stia un po' tranquillo, invece di stimare su un tratto preso in autostrada.
     */
    val waitingForStillness: Boolean = false,
  )

  private val _progress = MutableStateFlow<Progress?>(null)

  /** null = nessuna taratura in corso. */
  val progress: StateFlow<Progress?> = _progress.asStateFlow()

  /**
   * Com'e' finita l'ultima raffica.
   *
   * Viene **dal disco**: prima era un `MutableStateFlow` in memoria, e chi riapriva l'app dopo una
   * taratura fallita non poteva piu' sapere ne' che era fallita ne' perche'. Un errore che si
   * dimentica al riavvio e' un errore che nessuno riesce a segnalare.
   */
  val lastOutcome: StateFlow<CalibrationOutcome?> =
    store.lastOutcome.stateIn(scope, SharingStarted.Eagerly, null)

  /** Avvia il servizio; se una raffica e' gia' in corso, non fa niente. */
  fun start() {
    if (_progress.value != null) return
    if (!engine.barometerAvailable) {
      scope.launch { runCatching { store.setOutcome(CalibrationOutcome.NO_BAROMETER) } }
      return
    }
    runCatching {
      ContextCompat.startForegroundService(context, Intent(context, CalibrationService::class.java))
    }.onFailure {
      scope.launch { runCatching { store.setOutcome(CalibrationOutcome.START_FAILED) } }
    }
  }

  fun cancel() {
    context.stopService(Intent(context, CalibrationService::class.java))
  }

  /** Il lavoro vero, eseguito dal servizio: la raffica intera, poi la stima. */
  suspend fun run() {
    if (_progress.value != null) return
    val burstId = UUID.randomUUID().toString()
    val startedAt = System.currentTimeMillis()
    _progress.value = Progress(0, CalibrationBurst.DURATION_SECONDS)
    try {
      runCatching { store.setOutcome(null) }
      val references = mutableListOf<CalibrationReferenceSample>()
      // Un riferimento **prima** di cominciare, non solo alla fine: se la rete c'e' adesso e fra
      // dieci minuti no, dieci minuti di campioni non si buttano per quello.
      runCatching { reference() }.getOrNull()?.let { references += it }

      var elapsedSeconds = 0
      var stored = 0
      var segments = emptyList<CalibrationSegment>()

      while (true) {
        val chunk = if (elapsedSeconds == 0) {
          CalibrationBurst.DURATION_SECONDS
        } else {
          minOf(EXTENSION_SECONDS, CalibrationBurst.MAX_DURATION_SECONDS - elapsedSeconds)
        }
        if (chunk <= 0) break
        val extending = elapsedSeconds > 0
        val usefulBefore = CalibrationSegments.usefulSeconds(segments)
        stored += engine.collect(
          source = SampleSource.CALIBRATION,
          durationSeconds = chunk,
          // Un id solo per tutte le tranche: la raffica e' una, e deve poter essere ritrovata con
          // una query sola sia dalla stima sia dal ritentativo.
          burstId = burstId,
          contextRefreshSeconds = CalibrationBurst.CONTEXT_REFRESH_SECONDS,
        ) { completed, total ->
          _progress.value = if (extending) {
            // Da qui in poi il progresso conta i secondi **utili**, non quelli passati: dire a chi
            // sta camminando che manca poco quando non e' vero e' peggio che dire che si aspetta.
            Progress(usefulBefore, CalibrationBurst.MIN_STABLE_SECONDS, waitingForStillness = true)
          } else {
            Progress(completed, total)
          }
        }
        elapsedSeconds += chunk
        runCatching { reference() }.getOrNull()?.let { references += it }
        segments = CalibrationSegments.of(
          runCatching { repository.samplesOfBurst(burstId) }.getOrDefault(emptyList()),
        )
        if (CalibrationSegments.usefulSeconds(segments) >= CalibrationBurst.MIN_STABLE_SECONDS) break
        if (elapsedSeconds >= CalibrationBurst.MAX_DURATION_SECONDS) break
      }

      if (stored == 0) {
        runCatching { store.setOutcome(CalibrationOutcome.NO_READINGS) }
        return
      }
      if (segments.isEmpty()) {
        // Mezz'ora e nemmeno un minuto fermo: una stima si potrebbe anche fare, ma sarebbe la
        // pressione di una cabina spacciata per quella di un posto.
        runCatching { store.setOutcome(CalibrationOutcome.TOO_MUCH_MOVEMENT) }
        return
      }
      if (references.isEmpty()) {
        // La raffica resta in archivio e in sospeso: la stima si ritenta appena i provider
        // rispondono, senza chiedere altri dieci minuti a nessuno.
        runCatching {
          store.setPendingBurst(
            PendingCalibrationBurst(burstId, startedAt, System.currentTimeMillis()),
          )
          store.setOutcome(CalibrationOutcome.NO_REFERENCE)
        }
        return
      }
      val record = CalibrationMath.estimateSegments(segments, references, System.currentTimeMillis())
      if (record == null) {
        runCatching { store.setOutcome(CalibrationOutcome.TOO_FEW) }
        return
      }
      runCatching {
        store.save(record)
        store.setPendingBurst(null)
        store.setOutcome(CalibrationOutcome.OK)
      }
    } finally {
      _progress.value = null
    }
  }

  /**
   * Ritenta la stima su una raffica **gia' in archivio**, senza rifare i dieci minuti.
   *
   * La frase che l'utente legge quando manca il riferimento ("la raffica e' in archivio, riprova
   * la stima") prometteva questo da sempre, e questo non esisteva: `run()` ripartiva ogni volta da
   * capo. Adesso la chiamano l'avvio dell'app e il ciclo in background, che prima o poi la rete ce
   * l'hanno.
   *
   * Vero se la taratura e' stata salvata adesso.
   */
  suspend fun retryPendingEstimate(): Boolean {
    if (_progress.value != null) return false
    val pending = runCatching { store.pendingBurst.first() }.getOrNull() ?: return false
    // Qualcuno ha tarato nel frattempo (a mano, o un altro percorso): non c'e' piu' niente da
    // ritentare, e lasciare la raffica in sospeso significherebbe riprovare per sempre.
    if (runCatching { store.current() }.getOrNull() != null) {
      runCatching { store.setPendingBurst(null) }
      return false
    }
    val samples = runCatching { repository.samplesOfBurst(pending.burstId) }.getOrDefault(emptyList())
    if (samples.isEmpty()) {
      // La potatura dell'archivio se l'e' portata via: non tornera'.
      runCatching { store.setPendingBurst(null) }
      return false
    }
    val segments = CalibrationSegments.of(samples)
    if (segments.isEmpty()) {
      runCatching {
        store.setPendingBurst(null)
        store.setOutcome(CalibrationOutcome.TOO_MUCH_MOVEMENT)
      }
      return false
    }
    // Il riferimento e' quello di adesso, e [CalibrationReferenceSample.weightFor] lo sconta per
    // la distanza nel tempo: la pressione al mare si muove piano, ma non e' immobile.
    val target = runCatching { reference() }.getOrNull() ?: return false
    val record = CalibrationMath.estimateSegments(segments, listOf(target), System.currentTimeMillis())
      ?: return false
    runCatching {
      store.save(record)
      store.setPendingBurst(null)
      store.setOutcome(CalibrationOutcome.OK)
    }
    return true
  }

  private companion object {
    /**
     * Di quanto si allunga la raffica per volta quando il tempo fermo non basta.
     *
     * Due minuti: abbastanza da poter contenere un tratto valido (il minimo e' un minuto), poco
     * abbastanza da accorgersi presto che l'utente si e' fermato e chiudere.
     */
    const val EXTENSION_SECONDS = 120
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
      NotificationChannel(CHANNEL_ID, getString(R.string.calib_channel), NotificationManager.IMPORTANCE_LOW).apply {
        description = getString(R.string.calib_channel_desc)
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
    // Quando si aspetta la calma, dire "4:30 di 10:00" sarebbe una bugia: quei minuti sono passati
    // ma non contano, e chi legge deve sapere che tocca a lui fermarsi un attimo.
    val text = if (progress?.waitingForStillness == true) {
      getString(R.string.calib_waiting_still)
    } else {
      getString(
        R.string.calib_running_text,
        "${done / 60}:${String.format("%02d", done % 60)}",
        "${total / 60}:00",
      )
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_compass)
      .setContentTitle(getString(R.string.calib_running_title))
      .setContentText(text)
      .setProgress(total, done, progress?.waitingForStillness == true)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  private companion object {
    const val CHANNEL_ID = "calibration"
    const val NOTIFICATION_ID = 42
  }
}
