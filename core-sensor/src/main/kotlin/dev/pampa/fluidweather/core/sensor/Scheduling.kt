package dev.pampa.fluidweather.core.sensor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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

  private companion object {
    const val WORK_NAME = "pressure-sampling"
    const val MIN_PERIODIC_MINUTES = 15
  }
}

class PressureSamplingWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    applicationContext.sensorRuntime().samplingEngine.runScheduledPass()
    return Result.success()
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
    val pending = goAsync()
    val runtime = context.sensorRuntime()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        runtime.samplingEngine.runScheduledPass()
        // Riapplicare la modalita' corrente e' anche il riaggancio della catena: se nel frattempo
        // l'utente ha cambiato modalita', qui la catena muore e subentra il lavoro periodico.
        runtime.samplingScheduler.applyCurrentMode()
      } finally {
        pending.finish()
      }
    }
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
        runtime.samplingScheduler.applyCurrentMode()
      } finally {
        pending.finish()
      }
    }
  }
}
