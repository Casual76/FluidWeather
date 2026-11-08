package dev.pampa.fluidweather.strings

import android.content.res.Resources
import dev.pampa.fluidweather.core.model.*
import java.time.ZoneId
import java.util.Locale

/** Static launcher copy shares the home consumption gates and qualifiers; never renders an ETA. */
object WidgetSharedRainFormatter {
  fun headline(brief: WeatherBrief?, now: Long): BriefRainHeadline =
    if (WeatherSummaryPolicy.rainUsable(brief, now)) WeatherBriefPresentation.headline(brief)
    else BriefRainHeadline.UNAVAILABLE

  fun current(brief: WeatherBrief?, now: Long): CurrentWeatherSummary? = brief?.current?.takeIf {
    it.evidence.isNotEmpty() && it.evidence.all { e -> WeatherSummaryPolicy.usable(e, now, current = true) }
  }

  fun headline(resources: Resources, headline: BriefRainHeadline): String =
    resources.getString(WeatherBriefFormatter.headlineRes(headline))

  fun absoluteTime(time: Long, zone: ZoneId, locale: Locale): String =
    java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss xxx", locale)
      .format(java.time.Instant.ofEpochMilli(time).atZone(zone))

  /** Both original clocks are mandatory, including explicit unknowns. No relative ticking ages. */
  fun provenance(resources: Resources, brief: WeatherBrief?, assessedAt: Long, zone: ZoneId,
    fallback: Boolean, locale: Locale): String {
    fun at(time: Long) = absoluteTime(time, zone, locale)
    val evidence = (brief?.precipitation?.evidence.orEmpty() + brief?.current?.evidence.orEmpty()).distinct()
    return buildList {
      add(resources.getString(R.string.widget_shared_assessed, at(assessedAt), zone.id))
      if (fallback) add(resources.getString(R.string.home_rain_zone_fallback, zone.id))
      brief?.precipitation?.coordinates?.let {
        add(resources.getString(R.string.widget_shared_point, it.latitude.toString(), it.longitude.toString()))
      }
      brief?.precipitation?.radarAvailability?.let {
        add(resources.getString(when (it) {
          RadarAvailability.OUTSIDE -> R.string.home_rain_radar_outside
          RadarAvailability.UNKNOWN -> R.string.home_rain_radar_unknown
          RadarAvailability.UNAVAILABLE -> R.string.home_rain_radar_unavailable
          RadarAvailability.AVAILABLE -> R.string.home_rain_source_radar
        }))
      }
      evidence.forEach { e ->
        val type = resources.getString(when (e.type) {
          WeatherEvidenceType.MODEL -> R.string.home_rain_source_model
          WeatherEvidenceType.RADAR_ESTIMATE -> R.string.home_rain_source_radar
          WeatherEvidenceType.MEASUREMENT -> R.string.home_rain_source_measurement
        })
        add(resources.getString(R.string.widget_shared_source, "$type | ${e.sourceId}",
          e.sourceTimestampMillis?.let(::at) ?: resources.getString(R.string.summary_unknown),
          e.downloadedAtMillis?.let(::at) ?: resources.getString(R.string.summary_unknown)))
      }
      if (evidence.isEmpty()) add(resources.getString(R.string.summary_sources_unknown))
      val doc = WeatherBriefFormatter.document(resources, brief, "?", UnitFormatter(resources, UnitPreferences.METRIC), locale, assessedAt)
      doc.facts.firstOrNull { it.id == "source_limits" }?.let { add(it.text) }
      add(resources.getString(R.string.summary_limits))
    }.joinToString("\n")
  }
}
