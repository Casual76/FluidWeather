package dev.pampa.fluidweather.core.cycle

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Quello che i receiver, istanziati dal sistema, devono poter raggiungere: il ciclo e la
 * riprogrammazione del riepilogo. L'Application lo implementa, come [dev.pampa.fluidweather.core.sensor.SensorRuntime].
 */
interface CycleRuntime {
  val backgroundCycle: BackgroundCycle

  /** Rilegge l'impostazione e programma (o cancella) il prossimo riepilogo. */
  suspend fun rescheduleDailySummary()

  /** True when completed or no longer relevant; false requests durable, bounded retry. */
  suspend fun runDailySummary(dueAtMillis: Long): Boolean =
    !backgroundCycle.run(CycleTrigger.DAILY_SUMMARY, dueAtMillis).dailySummaryPending
}

fun Context.cycleRuntime(): CycleRuntime = applicationContext as CycleRuntime

/**
 * Il riepilogo giornaliero all'ora scelta: un allarme INESATTO che puo' partire anche in Doze
 * (`setAndAllowWhileIdle`), riprogrammato a ogni scatto e a ogni riavvio. Non serve
 * l'esattezza al secondo di MASSIMA: "verso le 7:30" e' la promessa.
 */
object DailySummaryAlarm {

  fun schedule(context: Context, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val trigger = nextTriggerMillis(System.currentTimeMillis(), hour, minute, zone)
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context))
  }

  fun cancel(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(pendingIntent(context))
  }

  /** La prossima occorrenza di hh:mm nel fuso: oggi se deve ancora venire, altrimenti domani. */
  fun nextTriggerMillis(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    return DailySummarySchedule.nextOccurrence(nowMillis, hour, minute, zone)
  }

  private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    1,
    Intent(context, DailySummaryReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

class DailySummaryReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    val runtime = context.cycleRuntime()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        // Only settings/ledger reads and durable WorkManager enqueue belong in this receiver.
        // The worker owns location, providers, notification delivery and retries.
        withTimeout(HANDOFF_TIMEOUT_MILLIS) { runtime.rescheduleDailySummary() }
      } catch (failure: Exception) {
        if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
        android.util.Log.e("DailySummary", "Could not hand off daily summary", failure)
      } finally {
        pending.finish()
      }
    }
  }

  private companion object { const val HANDOFF_TIMEOUT_MILLIS = 8_000L }
}

/** Gli allarmi muoiono col riavvio: il riepilogo si riprogramma da solo. */
class CycleBootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in RECONCILE_ACTIONS) return
    val pending = goAsync()
    val runtime = context.cycleRuntime()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        withTimeout(8_000L) { runtime.rescheduleDailySummary() }
      } catch (failure: Exception) {
        if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
        android.util.Log.e("DailySummary", "Could not restore daily summary schedule", failure)
      } finally {
        pending.finish()
      }
    }
  }

  private companion object {
    val RECONCILE_ACTIONS = setOf(
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
    )
  }
}
