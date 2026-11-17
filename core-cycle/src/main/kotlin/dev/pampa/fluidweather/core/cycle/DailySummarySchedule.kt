package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.NotificationSettings
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Wall-clock scheduling and bounded recovery, independent of Android's delivery delays. */
object DailySummarySchedule {
  const val RETRY_WINDOW_MILLIS = 6 * 3_600_000L

  fun nextOccurrence(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    val today = now.toLocalDate().atTime(time).atZone(zone)
    // Rebuild from the next date: adding a day to a DST-gap-adjusted 03:30 preserves the wrong hour.
    val next = if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(time).atZone(zone)
    return next.toInstant().toEpochMilli()
  }

  fun pendingOccurrence(
    nowMillis: Long,
    settings: NotificationSettings,
    summaryEpochDay: Long?,
    zone: ZoneId,
  ): Long? {
    if (!settings.dailySummary) return null
    val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    if (summaryEpochDay == date.toEpochDay()) return null
    val due = date.atTime(settings.summaryHour.coerceIn(0, 23), settings.summaryMinute.coerceIn(0, 59))
      .atZone(zone).toInstant().toEpochMilli()
    return due.takeIf { nowMillis >= it && nowMillis - it <= RETRY_WINDOW_MILLIS }
  }

  fun isCurrentOccurrence(dueAtMillis: Long, nowMillis: Long, settings: NotificationSettings, zone: ZoneId): Boolean =
    pendingOccurrence(nowMillis, settings, null, zone) == dueAtMillis
}
