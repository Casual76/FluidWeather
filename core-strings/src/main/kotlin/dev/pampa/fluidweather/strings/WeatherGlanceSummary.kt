package dev.pampa.fluidweather.strings

import android.content.res.Resources
import dev.pampa.fluidweather.core.model.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Compact presentation only. All consumption gates are the existing summary policy. */
object WeatherGlanceSummary {
  fun current(brief: WeatherBrief?, now: Long): CurrentWeatherSummary? = brief?.current?.takeIf {
    it.evidence.isNotEmpty() && it.evidence.all { e -> WeatherSummaryPolicy.usable(e, now, current = true) }
  }

  fun routineUsable(a: RoutineWindowAssessment, now: Long): Boolean =
    a.evaluatedAtMillis <= now && now < a.evaluatedAtMillis + 60_000L &&
      now < a.assessedInterval.endMillis && a.evidence.isNotEmpty() &&
      a.limits.none { it == WeatherEvidenceLimit.STALE || it == WeatherEvidenceLimit.LOCATION_MISMATCH } &&
      a.evidence.all { WeatherSummaryPolicy.usable(it, now) }

  fun matches(document: WeatherSummaryDocument, brief: WeatherBrief?, now: Long): Boolean =
    now < document.validUntilMillis && document.dataRevision == WeatherSummaryPolicy.revision(brief)

  fun transition(brief: WeatherBrief?, now: Long): BriefRainTransition? =
    if (WeatherSummaryPolicy.rainUsable(brief, now)) WeatherBriefPresentation.transition(brief, now) else null

  /** Deduplicate identities, not timestamps or represented intervals in the full detail catalog. */
  fun sources(brief: WeatherBrief?): List<String> =
    (brief?.precipitation?.evidence.orEmpty() + brief?.current?.evidence.orEmpty() +
      brief?.routines.orEmpty().flatMap { it.evidence }).distinctBy { it.type to it.sourceId }
      .map { it.sourceId }

  fun stateRes(state: PrecipitationState): Int = when (state) {
    PrecipitationState.WET -> R.string.glance_wet
    PrecipitationState.DRY -> R.string.glance_dry
    PrecipitationState.UNCERTAIN -> R.string.glance_uncertain
    PrecipitationState.UNAVAILABLE -> R.string.glance_unknown
  }

  fun partial(a: RoutineWindowAssessment): Boolean = !a.hasCompleteCoverage ||
    WeatherEvidenceLimit.PARTIAL_COVERAGE in a.limits || a.evidence.any { WeatherEvidenceLimit.PARTIAL_COVERAGE in it.limits }

  fun temperature(a: RoutineWindowAssessment, units: UnitFormatter): String? =
    if (a.minTemperatureC != null && a.maxTemperatureC != null)
      units.degrees(a.minTemperatureC!!) + "\u2013" + units.degrees(a.maxTemperatureC!!) else null

  /** Both dates for overnight; both offsets for a repeated wall hour. Exact zone remains in Details. */
  fun time(a: RoutineOccurrence, locale: Locale): String {
    val start = Instant.ofEpochMilli(a.interval.startMillis).atZone(a.zone.zoneId)
    val end = Instant.ofEpochMilli(a.interval.endMillis).atZone(a.zone.zoneId)
    val overnight = start.toLocalDate() != end.toLocalDate()
    val format = DateTimeFormatter.ofPattern(if (overnight) "d MMM HH:mm" else "HH:mm", locale)
    val offsets = start.offset != end.offset || a.adjustedForDst
    fun endpoint(t: java.time.ZonedDateTime) = t.format(format) + if (offsets) " " + t.offset else ""
    return endpoint(start) + "\u2013" + endpoint(end)
  }

  fun day(resources: Resources, a: RoutineOccurrence, now: Long, locale: Locale): String {
    val date = Instant.ofEpochMilli(a.interval.startMillis).atZone(a.zone.zoneId).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(a.zone.zoneId).toLocalDate()
    return when (date) {
      today -> resources.getString(R.string.glance_today)
      today.plusDays(1) -> resources.getString(R.string.glance_tomorrow)
      else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }
  }
}
