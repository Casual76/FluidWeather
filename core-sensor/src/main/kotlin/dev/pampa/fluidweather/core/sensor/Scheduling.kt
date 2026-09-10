package dev.pampa.fluidweather.core.sensor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.SamplingMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Traduce la modalita' scelta nel meccanismo giusto: WorkManager per le cadenze dai 15 minuti in
 * su, una catena di allarmi esatti per MASSIMA (che sta sotto il minimo periodico di WorkManager).
 * Se il permesso per gli allarmi esatti manca, MASSIMA degrada a 15 minuti — la diagnostica lo
 * dice invece di fingere.
 */
class SamplingScheduler(
  private val context: Context,
  private val settingsStore: SamplingSettingsStore,
) {

  suspend fun applyCurrentMode() = apply(settingsStore.current().mode)

  fun apply(mode: SamplingMode) {
    val workManager = WorkManager.getInstance(context)
    if (mode == SamplingMode.MASSIMA && MaximaAlarm.canSchedule(context)) {
      workManager.cancelUniqueWork(WORK_NAME)
      MaximaAlarm.scheduleNext(context, mode.cadenceMinutes)
    } else {
      MaximaAlarm.cancel(context)
      val cadenceMinutes = maxOf(mode.cadenceMinutes, MIN_PERIODIC_MINUTES).toLong()
      workManager.enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<PressureSamplingWorker>(cadenceMinutes, TimeUnit.MINUTES).build(),
      )
    }
  }

  companion object {
    /** Il nome unico del lavoro periodico: [SamplingHealth] lo interroga per sapere se c'e' ancora. */
    internal const val WORK_NAME = "pressure-sampling"

    private const val MIN_PERIODIC_MINUTES = 15
  }
}

class PressureSamplingWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  /**
   * **Non lascia mai uscire un'eccezione**, e non e' pigrizia difensiva.
   *
   * Un `doWork` che lancia vale `Result.failure()`, e per un lavoro **periodico** fallito
   * WorkManager smette di riprogrammarlo: il campionamento si ferma per sempre, in silenzio, e
   * quello stato vive nel database di WorkManager — dentro i *dati* dell'app, non nella cache. E'
   * la strada piu' probabile per la barra ferma a "0 ore di 13" che ha costretto qualcuno a
   * cancellare i dati.
   *
   * `retry()` invece di `success()` quando qualcosa e' andato storto: una passata persa si
   * recupera al tentativo successivo, e lo stato resta ENQUEUED, che e' anche cio' che
   * [SamplingHealth] va a guardare.
   */
  override suspend fun doWork(): Result {
    val runtime = applicationContext.sensorRuntime()
    runCatching { runtime.samplingHealth.check() }
    return runCatching { runtime.samplingEngine.runScheduledPass() }
      .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
  }
}

/**
 * Il lavoro di una passata di MASSIMA, fuori dal receiver che l'ha svegliata.
 *
 * Una passata dura fino a cinquanta secondi — dieci di GPS, trenta di raffica, dieci di grazia —
 * e dopo di essa parte il ciclo in background con le sue chiamate di rete. Farla dentro
 * `goAsync()` di un BroadcastReceiver vuol dire sforare il budget del receiver e vedersi
 * troncare la raffica a meta': i punti che sopravvivono hanno meno di quattro campioni, quindi
 * saltano il controllo del MAD e finiscono nel filtro come se fossero buoni. Un Worker ha il suo
 * wakelock e il suo tempo, e la catena di allarmi resta compito del receiver.
 */
class MaximaPassWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val runtime = applicationContext.sensorRuntime()
    // Stesso ragionamento di PressureSamplingWorker: qui il lavoro e' uno solo e non periodico,
    // ma la catena di allarmi la riaggancia il receiver, quindi un'eccezione che sale non
    // riporterebbe niente a nessuno.
    return runCatching { runtime.samplingEngine.runScheduledPass() }
      .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
  }
}

/** La catena di allarmi esatti che regge MASSIMA: ogni scatto campiona e riprogramma il prossimo. */
object MaximaAlarm {

  fun canSchedule(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
  }

  fun scheduleNext(context: Context, minutes: Int) {
    if (!canSchedule(context)) return
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.setExactAndAllowWhileIdle(
      AlarmManager.RTC_WAKEUP,
      System.currentTimeMillis() + minutes * 60_000L,
      pendingIntent(context),
    )
  }

  fun cancel(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(pendingIntent(context))
  }

  private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, MaximaAlarmReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

class MaximaAlarmReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    // Il receiver fa due cose e nessuna delle due e' lunga: mette in coda la passata, e riaggancia
    // la catena. Il campionamento vero e proprio vive nel Worker, dove c'e' tempo per farlo intero.
    WorkManager.getInstance(context).enqueueUniqueWork(
      MAXIMA_PASS_WORK,
      ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<MaximaPassWorker>().build(),
    )
    val pending = goAsync()
    val runtime = context.sensorRuntime()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        // Riapplicare la modalita' corrente e' anche il riaggancio della catena: se nel frattempo
        // l'utente ha cambiato modalita', qui la catena muore e subentra il lavoro periodico.
        // Un'eccezione qui spezzerebbe la catena per sempre, senza dirlo a nessuno.
        runCatching { runtime.samplingScheduler.applyCurrentMode() }
      } finally {
        pending.finish()
      }
    }
  }

  private companion object {
    const val MAXIMA_PASS_WORK = "pressure-sampling-massima"
  }
}

/** WorkManager sopravvive al riavvio da solo; la catena di allarmi esatti no. */
class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val pending = goAsync()
    val runtime = context.sensorRuntime()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        runCatching { runtime.samplingScheduler.applyCurrentMode() }
      } finally {
        pending.finish()
      }
    }
  }
}
