package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*

/** Pure presentation decisions: never infer current rain from the future timeline. */
typealias RainHeadline = BriefRainHeadline
typealias RainTransition = BriefRainTransition
data class RainTimelinePart(val interval: WeatherInterval, val period: PrecipitationPeriod?)
enum class RainForecastKind { NONE, DRY, WET, UNCERTAIN }

/** Future context is independent from the answer about rain now. Null totals mean incomplete coverage. */
data class RainForecastPresentation(
  val kind: RainForecastKind,
  val knownDurationMillis: Long,
  val hasGaps: Boolean,
  val totalAmountMm: Double?,
  val maximumProbabilityPercent: Double?,
  val resolutionMillis: Long?,
)
object HomeRainPresentation {
  fun headline(brief: WeatherBrief?): RainHeadline = WeatherBriefPresentation.headline(brief)

  /** Radar resolution is a window around an absolute instant, never a minute countdown. */
  fun transition(brief: WeatherBrief?, now: Long): RainTransition? = WeatherBriefPresentation.transition(brief, now)

  fun forecast(brief: WeatherBrief?, now: Long): RainForecastPresentation {
    val parts = timeline(brief, now)
    val known = parts.filter { it.period != null }
    val gaps = known.size != parts.size
    val kind = when {
      known.isEmpty() -> RainForecastKind.NONE
      known.any { it.period?.state == PrecipitationState.WET } -> RainForecastKind.WET
      known.any { it.period?.state == PrecipitationState.UNCERTAIN } -> RainForecastKind.UNCERTAIN
      else -> RainForecastKind.DRY
    }
    return RainForecastPresentation(kind,
      known.sumOf { it.interval.endMillis - it.interval.startMillis }, gaps,
      known.takeIf { it.isNotEmpty() && !gaps && it.all { p -> p.period?.amountMm != null } }
        ?.sumOf { it.period!!.amountMm!! },
      known.mapNotNull { it.period?.probabilityPercent }.maxOrNull(),
      known.flatMap { it.period!!.evidence }.mapNotNull { it.temporalResolutionMillis }.maxOrNull())
  }

  /** Fill holes explicitly; clipped amounts are omitted rather than prorated. */
  fun timeline(brief: WeatherBrief?, now: Long): List<RainTimelinePart> {
    val end = now + 3_600_000L
    val periods = brief?.timeline.orEmpty().filter { it.role == PrecipitationPeriodRole.FORECAST }
    val boundaries = (listOf(now, end) + periods.flatMap { listOf(it.interval.startMillis, it.interval.endMillis) }.filter { it > now && it < end }).distinct().sorted()
    return boundaries.zipWithNext { a, b ->
      val candidates = periods.filter { it.interval.startMillis <= a && it.interval.endMillis >= b }
      val p = candidates.singleOrNull()?.takeIf { it.state != PrecipitationState.UNAVAILABLE &&
        it.evidence.none { e -> e.limits.any { limit -> limit == WeatherEvidenceLimit.STALE || limit == WeatherEvidenceLimit.LOCATION_MISMATCH } } }
      RainTimelinePart(WeatherInterval(a, b), p?.copy(amountMm = p.amountMm.takeIf { p.interval.startMillis == a && p.interval.endMillis == b }))
    }
  }
}
