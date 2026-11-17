package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherInterval
import dev.pampa.fluidweather.core.model.precipitationHours
import dev.pampa.fluidweather.core.model.knownPrecipitationInterval

/** A position survives missing variables, absent hours and duplicate source timestamps. */
data class ForecastRainSlot(val at: Long, val probability: Double?, val amount: Double?, val interval: WeatherInterval? = null)
data class ForecastSampleSum(val available: Int, val positions: Int, val value: Double?)
object ForecastRainSamples {
  const val HOUR = 3_600_000L
  fun slots(hours: List<FusedHour>, start: Long, endExclusive: Long): List<ForecastRainSlot> {
    if (endExclusive <= start) return emptyList()
    val groups = hours.precipitationHours(start, endExclusive).associateBy { it.precipitationInterval!!.startMillis }
    val originalPositions = hours.map { it.knownPrecipitationInterval()?.startMillis ?: it.timestampMillis }
      .filter { it >= start && it < endExclusive }
    // Keep the nominal grid and original positions. Only an explicit non-overlapping interval
    // carries values; an endpoint alone never establishes cadence or accumulation duration.
    val grid = generateSequence(start) { previous ->
      (previous + HOUR).takeIf { it > previous && it < endExclusive }
    }.toList()
    return (grid + originalPositions).distinct().sorted().map { at ->
      val hour = groups[at]
      ForecastRainSlot(at,
        hour?.values?.get(FusionVariables.PRECIP_PROBABILITY)?.value?.takeIf { it.isFinite() && it in 0.0..100.0 },
        hour?.values?.get(FusionVariables.PRECIPITATION)?.value?.takeIf { it.isFinite() && it >= 0.0 },
        hour?.precipitationInterval)
    }
  }
  fun sum(slots: List<ForecastRainSlot>): ForecastSampleSum {
    val amounts = slots.filter { it.interval?.startMillis == it.at }.mapNotNull { it.amount }
    return ForecastSampleSum(amounts.size, slots.size,
      amounts.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it.isFinite() })
  }
}
