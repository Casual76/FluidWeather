package dev.pampa.fluidweather.strings

import android.content.res.Resources
import dev.pampa.fluidweather.core.model.*
import java.time.ZoneId
import java.util.Locale

/** Original source and download clocks, never the assessment computation time. */
fun rainSourceLabel(resources: Resources, evidence: WeatherEvidence, now: Long, zone: ZoneId, locale: Locale): String {
  fun time(at: Long): String {
    val clock = TimeFormats.dayTime(at, zone, locale)
    return if (at <= now) resources.getString(R.string.home_rain_source_age, clock, (now - at) / 60_000L) else clock
  }
  val type = resources.getString(when (evidence.type) {
    WeatherEvidenceType.MODEL -> R.string.home_rain_source_model
    WeatherEvidenceType.RADAR_ESTIMATE -> R.string.home_rain_source_radar
    WeatherEvidenceType.MEASUREMENT -> R.string.home_rain_source_measurement
  })
  val namedType = "$type · ${evidence.sourceId}"
  val interval = evidence.effectiveInterval
  val source = if (interval != null) resources.getString(R.string.home_rain_source_interval, namedType,
    TimeFormats.time(interval.startMillis, zone, locale), time(interval.endMillis))
  else evidence.sourceTimestampMillis?.let { resources.getString(R.string.home_rain_source_time, namedType, time(it)) }
    ?: resources.getString(R.string.home_rain_source_unknown, namedType)
  return evidence.downloadedAtMillis?.let { resources.getString(R.string.home_rain_download, source, time(it)) }
    ?: resources.getString(R.string.home_rain_download_unknown, source)
}
