package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*

/** Pure presentation decisions: never infer current rain from the future timeline. */
typealias RainHeadline = BriefRainHeadline
typealias RainTransition = BriefRainTransition
data class RainTimelinePart(val interval: WeatherInterval, val period: PrecipitationPeriod?)
object HomeRainPresentation {
  fun headline(brief: WeatherBrief?): RainHeadline = WeatherBriefPresentation.headline(brief)

  /** Radar resolution is a window around an absolute instant, never a minute countdown. */
  fun transition(brief: WeatherBrief?, now: Long): RainTransition? = WeatherBriefPresentation.transition(brief, now)

  /** Fill holes explicitly; clipped amounts are omitted rather than prorated. */
  fun timeline(brief: WeatherBrief?, now: Long): List<RainTimelinePart> {
    val end = now + 3_600_000L
    val periods = brief?.timeline.orEmpty().filter { it.role == PrecipitationPeriodRole.FORECAST }
    val boundaries = (listOf(now, end) + periods.flatMap { listOf(it.interval.startMillis, it.interval.endMillis) }.filter { it > now && it < end }).distinct().sorted()
    return boundaries.zipWithNext { a, b ->
      val candidates = periods.filter { it.interval.startMillis <= a && it.interval.endMillis >= b }
      val p = candidates.singleOrNull()?.takeIf { it.state != PrecipitationState.UNAVAILABLE }
      RainTimelinePart(WeatherInterval(a, b), p?.copy(amountMm = p.amountMm.takeIf { p.interval.startMillis == a && p.interval.endMillis == b }))
    }
  }
}
