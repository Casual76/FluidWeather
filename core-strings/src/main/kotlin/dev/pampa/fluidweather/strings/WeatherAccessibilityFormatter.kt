package dev.pampa.fluidweather.strings

import android.content.res.Resources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Absolute dates and offsets also distinguish repeated wall times during DST overlap. */
fun accessibilitySampleTime(at: Long, zone: ZoneId, locale: Locale): String =
  DateTimeFormatter.ofPattern("EEE d MMM uuuu HH:mm XXX '['VV']'", locale)
    .format(Instant.ofEpochMilli(at).atZone(zone))

data class AccessibleInterval(val start: String, val end: String, val short: Boolean)

/** Short means actual elapsed time, never equality of rounded wall-clock labels. */
fun accessibleInterval(start: Long, end: Long, zone: ZoneId, locale: Locale): AccessibleInterval {
  require(end > start)
  val short = end - start < 60_000L
  // These are viewport interval captions, not sub-second meteorological timing estimates.
  val pattern = "d MMM uuuu HH:mm XXX '['VV']'"
  val format = DateTimeFormatter.ofPattern(pattern, locale)
  return AccessibleInterval(format.format(Instant.ofEpochMilli(start).atZone(zone)),
    format.format(Instant.ofEpochMilli(end).atZone(zone)), short)
}

fun accessibleIntervalLabel(resources: Resources, start: Long, end: Long, zone: ZoneId, locale: Locale): String {
  val interval = accessibleInterval(start, end, zone, locale)
  return if (interval.short) resources.getString(R.string.accessibility_short_interval, interval.start)
    else resources.getString(R.string.accessibility_interval, interval.start, interval.end)
}
