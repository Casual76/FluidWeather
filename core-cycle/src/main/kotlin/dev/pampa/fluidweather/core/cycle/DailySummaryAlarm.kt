package dev.pampa.fluidweather.core.cycle

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Quello che i receiver, istanziati dal sistema, devono poter raggiungere: il ciclo e la
 * riprogrammazione del riepilogo. L'Application lo implementa, come [dev.pampa.fluidweather.core.sensor.SensorRuntime].
 */
interface CycleRuntime {
  val backgroundCycle: BackgroundCycle

  /** Rilegge l'impostazione e programma (o cancella) il prossimo riepilogo. */
  suspend fun rescheduleDailySummary()
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
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    var candidate = now.toLocalDate().atTime(time).atZone(zone)
    if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
    return candidate.toInstant().toEpochMilli()
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
        runCatching { runtime.backgroundCycle.run(CycleTrigger.DAILY_SUMMARY) }
        runtime.rescheduleDailySummary()
      } finally {
        pending.finish()
      }
    }
  }
}

/** Gli allarmi muoiono col riavvio: il riepilogo si riprogramma da solo. */
class CycleBootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val pending = goAsync()
    val runtime = context.cycleRuntime()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        runtime.rescheduleDailySummary()
      } finally {
        pending.finish()
      }
    }
  }
}
