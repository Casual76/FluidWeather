package dev.pampa.fluidweather.strings

import android.content.res.Resources
import dev.pampa.fluidweather.core.model.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

fun routineDays(days: Set<DayOfWeek>, locale: Locale): String = days.sortedBy { it.value }
  .joinToString(" · ") { it.getDisplayName(TextStyle.FULL, locale) }

fun routineRuleLabel(window: RoutineWindow, locale: Locale): String =
  "${routineDays(window.daysOfWeek, locale)} · ${TimeFormats.clock(window.start.hour, window.start.minute, locale)} – ${TimeFormats.clock(window.end.hour, window.end.minute, locale)}"

/** Uses BOTH occurrence dates and offsets: overnight and repeated DST hours stay unambiguous. */
fun routineOccurrenceLabel(occurrence: RoutineOccurrence, locale: Locale): String {
  val zone = occurrence.zone.zoneId
  fun endpoint(at: Long): String = "${TimeFormats.day(at, zone, locale)} ${TimeFormats.time(at, zone, locale)} ${Instant.ofEpochMilli(at).atZone(zone).offset}"
  return "${endpoint(occurrence.interval.startMillis)} – ${endpoint(occurrence.interval.endMillis)} · ${zone.id}"
}

fun routineForecastRes(assessment: RoutineWindowAssessment): Int = when (assessment.precipitation) {
  PrecipitationState.WET -> R.string.routines_model_wet
  PrecipitationState.DRY -> R.string.routines_model_dry
  PrecipitationState.UNCERTAIN -> R.string.routines_model_uncertain
  PrecipitationState.UNAVAILABLE -> R.string.routines_unknown
}

fun routineLimitResources(assessment: RoutineWindowAssessment): List<Int> = buildList {
  val limits = assessment.limits + assessment.evidence.flatMap { it.limits }
  if (!assessment.hasCompleteCoverage || WeatherEvidenceLimit.PARTIAL_COVERAGE in limits) add(R.string.routines_partial)
  if (WeatherEvidenceLimit.STALE in limits) add(R.string.routines_stale)
  if (WeatherEvidenceLimit.LOCATION_MISMATCH in limits) add(R.string.routines_mismatch)
  if (WeatherEvidenceLimit.UNKNOWN_PROVENANCE in limits) add(R.string.routines_provenance_unknown)
  if (assessment.occurrence.zone.source == WeatherZoneSource.DEVICE_FALLBACK) add(R.string.routines_fallback)
  if (assessment.occurrence.adjustedForDst) add(R.string.routines_dst)
  if (assessment.assessedInterval.startMillis > assessment.occurrence.interval.startMillis) add(R.string.routines_remaining)
}

fun routineForecastLabel(resources: Resources, assessment: RoutineWindowAssessment, locale: Locale, units: UnitFormatter, details: Boolean = false): String = buildList {
  fun number(value: Double) = String.format(locale, "%.1f", value)
  add(resources.getString(routineForecastRes(assessment)))
  val metrics = buildList {
    assessment.maxProbabilityPercent?.let { add(resources.getString(R.string.routines_probability, number(it))) }
    if (assessment.hasCompleteCoverage) assessment.rainAmountMm?.let { add(resources.getString(R.string.routines_amount, units.precipitation(it))) }
    if (assessment.minTemperatureC != null && assessment.maxTemperatureC != null) {
      add(resources.getString(R.string.routines_temperature, units.temperature(assessment.minTemperatureC!!), units.temperature(assessment.maxTemperatureC!!)))
    }
  }
  if (metrics.isNotEmpty()) add(metrics.joinToString(if (details) "\n" else " · "))
  addAll(routineLimitResources(assessment).filter { details || it != R.string.routines_provenance_unknown }
    .map { resources.getString(it) })
}.joinToString("\n")

/** Original model clocks and exact represented intervals; never substitute evaluation time. */
fun routineEvidenceLabel(resources: Resources, assessment: RoutineWindowAssessment, locale: Locale, details: Boolean = true): String =
  assessment.evidence.filter { it.type == WeatherEvidenceType.MODEL }
    .let { if (details) it.distinct() else it.distinctBy { source -> source.sourceId to source.downloadedAtMillis } }
    .joinToString("\n") { evidence ->
    val zone = assessment.occurrence.zone.zoneId
    buildList {
      add(resources.getString(R.string.routines_source, evidence.sourceId))
      add(evidence.downloadedAtMillis?.let { at ->
        resources.getString(R.string.routines_download, TimeFormats.dayTime(at, zone, locale),
          ((assessment.evaluatedAtMillis - at) / 60_000L).coerceAtLeast(0L))
      } ?: resources.getString(R.string.routines_download_unknown))
      if (details) {
        evidence.sourceTimestampMillis?.let { add(resources.getString(R.string.routines_source_time, TimeFormats.dayTime(it, zone, locale))) }
        add(evidence.effectiveInterval?.let {
          resources.getString(R.string.routines_interval, TimeFormats.dayTime(it.startMillis, zone, locale), TimeFormats.dayTime(it.endMillis, zone, locale))
        } ?: resources.getString(R.string.routines_interval_unknown))
        add(evidence.temporalResolutionMillis?.let { resources.getString(R.string.routines_resolution, routineResolution(it)) }
          ?: resources.getString(R.string.routines_resolution_unknown))
      }
    }.joinToString(" · ")
  }

/** Preserve the represented duration exactly, choosing a readable unit without rounding. */
internal fun routineResolution(millis: Long): String = when {
  millis % 60_000L == 0L -> "${millis / 60_000L} min"
  millis % 1_000L == 0L -> "${millis / 1_000L} s"
  else -> "$millis ms"
}
