package dev.pampa.fluidweather.strings

import android.content.res.Resources
import dev.pampa.fluidweather.core.model.*
import java.time.ZoneId
import java.util.Locale

object WeatherBriefFormatter {
  fun document(resources: Resources, brief: WeatherBrief?, placeLabel: String,
    units: UnitFormatter, locale: Locale, nowMillis: Long): WeatherSummaryDocument {
    val language = if (locale.language == "it") "it" else "en"
    val zone = WeatherZones.resolve(brief?.precipitation?.place?.zoneId,
      brief?.routines?.firstOrNull()?.occurrence?.zone?.takeIf { it.source != WeatherZoneSource.DEVICE_FALLBACK }?.zoneId?.id,
      ZoneId.systemDefault())
    fun s(id: Int, vararg args: Any): String = resources.getString(id, *args)
    fun at(time: Long) = TimeFormats.dayTime(time, zone.zoneId, locale)
    val facts = mutableListOf<WeatherSummaryFact>()
    fun fact(id: String, text: String, required: Boolean = false) { facts += WeatherSummaryFact(id, text, required) }
    var until = nowMillis + 60_000L
    fun boundary(time: Long?) { if (time != null && time > nowMillis) until = minOf(until, time) }
    fact("place", s(R.string.summary_place, placeLabel.trim().take(120).ifBlank { s(R.string.summary_place_unknown) }), true)
    fact("zone", s(if (zone.source == WeatherZoneSource.DEVICE_FALLBACK) R.string.home_rain_zone_fallback else R.string.summary_zone, zone.zoneId.id), true)
    val usable = WeatherSummaryPolicy.rainUsable(brief, nowMillis)
    val headline = if (usable) WeatherBriefPresentation.headline(brief) else BriefRainHeadline.UNAVAILABLE
    fact("rain", s(headlineRes(headline)), true)
    fact("limits", s(R.string.summary_limits), true)
    if (!usable) fact("freshness", s(R.string.summary_refresh), true)
    val transition = if (usable) WeatherBriefPresentation.transition(brief, nowMillis) else null
    fact("transition", transition?.let {
      s(if (it.arrival) R.string.home_rain_arrival else R.string.home_rain_end, at(it.startMillis), at(it.endMillis))
    } ?: s(R.string.home_rain_no_eta), true)
    brief?.precipitation?.let { rain ->
      boundary(rain.evaluatedAtMillis + 60_000L); boundary(rain.horizon?.endMillis)
      boundary(rain.arrivalAtMillis); boundary(rain.endAtMillis)
    }
    boundary(transition?.startMillis); boundary(transition?.endMillis)
    val current = brief?.current
    val currentUsable = current != null && current.evidence.isNotEmpty() && current.evidence.all { WeatherSummaryPolicy.usable(it, nowMillis, current = true) }
    fact("current", if (currentUsable) s(R.string.summary_current,
      current!!.temperatureC?.let { units.temperature(it) } ?: s(R.string.summary_unknown),
      current.kind?.labelRes()?.let { s(it) } ?: s(R.string.summary_unknown)) else s(R.string.home_rain_current_unknown))
    current?.evidence?.forEach { boundary(WeatherSummaryPolicy.cutoff(it, current = true)) }
    val allEvidence = (brief?.precipitation?.evidence.orEmpty() + current?.evidence.orEmpty() + brief?.routines.orEmpty().flatMap { it.evidence }).distinct()
    allEvidence.forEach { boundary(WeatherSummaryPolicy.cutoff(it, recent = it in brief?.precipitation?.evidence.orEmpty())) }
    // All essential provenance stays mandatory. Cap detail at four sources and state omissions.
    val sourceText = allEvidence.take(4).map { e ->
      val type = s(when (e.type) {
        WeatherEvidenceType.MODEL -> R.string.home_rain_source_model
        WeatherEvidenceType.RADAR_ESTIMATE -> R.string.home_rain_source_radar
        WeatherEvidenceType.MEASUREMENT -> R.string.home_rain_source_measurement
      })
      listOf(type + " · " + e.sourceId.take(80),
        e.downloadedAtMillis?.let { s(R.string.home_rain_download, "", at(it)) } ?: s(R.string.home_rain_download_unknown, ""),
        e.sourceTimestampMillis?.let { s(R.string.home_rain_source_time, "", at(it)) } ?: s(R.string.home_rain_source_unknown, ""),
        e.effectiveInterval?.let { s(R.string.summary_interval, at(it.startMillis), at(it.endMillis)) } ?: s(R.string.summary_interval_unknown),
        e.temporalResolutionMillis?.let { s(R.string.summary_resolution, routineResolution(it)) } ?: s(R.string.summary_resolution_unknown),
        if (WeatherSummaryPolicy.usable(e, nowMillis)) "" else s(R.string.home_rain_stale))
        .filter { it.isNotBlank() }.joinToString(" · ")
    }
    fact("sources", allEvidence.take(4).joinToString(" · ") { e ->
      s(when (e.type) {
        WeatherEvidenceType.MODEL -> R.string.home_rain_source_model
        WeatherEvidenceType.RADAR_ESTIMATE -> R.string.home_rain_source_radar
        WeatherEvidenceType.MEASUREMENT -> R.string.home_rain_source_measurement
      }) + ": " + e.sourceId.take(80)
    }.ifBlank { s(R.string.summary_sources_unknown) }, true)
    fact("source_details", sourceText.joinToString("\n").ifBlank { s(R.string.summary_sources_unknown) } +
      if (allEvidence.size > 4) "\n" + s(R.string.summary_more_sources) else "")
    val limits = (allEvidence.flatMap { it.limits } + brief?.precipitation?.limits.orEmpty()).toSet()
    if (limits.isNotEmpty()) fact("source_limits", limits.sortedBy { it.ordinal }.joinToString(" · ") { s(limitRes(it)) }, true)
    brief?.routines.orEmpty().take(3).forEachIndexed { index, assessment ->
      val valid = assessment.evaluatedAtMillis <= nowMillis && nowMillis < assessment.evaluatedAtMillis + 60_000L &&
        nowMillis < assessment.assessedInterval.endMillis && assessment.evidence.isNotEmpty() &&
        assessment.limits.none { it == WeatherEvidenceLimit.STALE || it == WeatherEvidenceLimit.LOCATION_MISMATCH } &&
        assessment.evidence.all { WeatherSummaryPolicy.usable(it, nowMillis) }
      val state = if (valid) s(routineForecastRes(assessment)) else s(R.string.routines_unknown)
      val detail = buildList {
        add(s(R.string.summary_routine, assessment.occurrence.routine.name.take(80), routineOccurrenceLabel(assessment.occurrence, locale), state))
        if (valid && assessment.hasCompleteCoverage) assessment.rainAmountMm?.let { add(s(R.string.routines_amount, units.precipitation(it))) }
        if (valid && assessment.minTemperatureC != null && assessment.maxTemperatureC != null)
          add(s(R.string.summary_samples, units.temperature(assessment.minTemperatureC!!), units.temperature(assessment.maxTemperatureC!!)))
        addAll(routineLimitResources(assessment).map { s(it) })
      }.joinToString(" · ")
      fact("routine_$index", detail)
      boundary(assessment.evaluatedAtMillis + 60_000L)
      boundary(assessment.assessedInterval.startMillis); boundary(assessment.assessedInterval.endMillis)
      boundary(assessment.occurrence.interval.startMillis); boundary(assessment.occurrence.interval.endMillis)
    }
    if (brief?.routines.orEmpty().size > 3) fact("more_routines", s(R.string.summary_more_routines), true)
    val revision = WeatherSummaryPolicy.revision(brief)
    // Absolute timestamps, no cached relative-age prose. Clock-only reevaluation leaves identity stable.
    val context = WeatherSummaryPolicy.hash("$revision|$placeLabel|$locale|${units.units}|$zone|$headline|$transition|$currentUsable|${facts.map { it.text }}")
    val defaults = listOf("rain", "transition", "current") + facts.filter { it.id.startsWith("routine_") }.map { it.id }
    return WeatherSummaryDocument(context, revision, language, units.units, facts, defaults, until)
  }

  internal fun headlineRes(headline: BriefRainHeadline): Int = when (headline) {
    BriefRainHeadline.MODEL_WET -> R.string.home_rain_model_wet
    BriefRainHeadline.MODEL_DRY -> R.string.home_rain_model_dry
    BriefRainHeadline.RADAR_WET -> R.string.home_rain_radar_wet
    BriefRainHeadline.RADAR_NO_ECHO -> R.string.home_rain_radar_no_echo
    BriefRainHeadline.WET -> R.string.home_rain_wet
    BriefRainHeadline.DRY -> R.string.home_rain_dry
    BriefRainHeadline.CONFLICT -> R.string.home_rain_conflict
    BriefRainHeadline.UNCERTAIN -> R.string.home_rain_uncertain
    BriefRainHeadline.UNAVAILABLE -> R.string.home_rain_unavailable
  }

  private fun limitRes(limit: WeatherEvidenceLimit): Int = when (limit) {
    WeatherEvidenceLimit.STALE -> R.string.home_rain_stale
    WeatherEvidenceLimit.CONFLICTING_SIGNALS, WeatherEvidenceLimit.TRANSITION_EXPIRED, WeatherEvidenceLimit.FUTURE_DIVERGENCE -> R.string.home_rain_conflict_limit
    WeatherEvidenceLimit.MODEL_ONLY -> R.string.home_rain_model_limit
    WeatherEvidenceLimit.RADAR_GROUND_UNCERTAINTY -> R.string.home_rain_radar_limit
    WeatherEvidenceLimit.PARTIAL_COVERAGE -> R.string.routines_partial
    WeatherEvidenceLimit.LOCATION_MISMATCH -> R.string.routines_mismatch
    WeatherEvidenceLimit.UNKNOWN_PROVENANCE -> R.string.routines_provenance_unknown
    WeatherEvidenceLimit.MISSING_COORDINATES -> R.string.summary_coordinates_unknown
    WeatherEvidenceLimit.RADAR_UNAVAILABLE -> R.string.home_rain_radar_unavailable
    WeatherEvidenceLimit.RADAR_SUPPORT_UNKNOWN -> R.string.home_rain_radar_unknown
    WeatherEvidenceLimit.COARSE_TIME_RESOLUTION -> R.string.summary_coarse_time
    WeatherEvidenceLimit.COARSE_SPATIAL_RESOLUTION -> R.string.summary_coarse_space
    WeatherEvidenceLimit.POSSIBLY_INTERPOLATED -> R.string.summary_interpolated
  }
}
