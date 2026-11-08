package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables

/** A position survives missing variables, absent hours and duplicate source timestamps. */
data class ForecastRainSlot(val at: Long, val probability: Double?, val amount: Double?)
data class ForecastSampleSum(val available: Int, val positions: Int, val value: Double?)
object ForecastRainSamples {
  const val HOUR = 3_600_000L
  fun slots(hours: List<FusedHour>, start: Long, endExclusive: Long): List<ForecastRainSlot> {
    if (endExclusive <= start) return emptyList()
    val groups = hours.filter { it.timestampMillis >= start && it.timestampMillis < endExclusive }
      .groupBy { it.timestampMillis }
    // Include the nominal grid AND off-grid originals. Off-grid positions remain unknown:
    // cadence alone cannot prove non-overlapping accumulation intervals.
    val grid = generateSequence(start) { previous ->
      (previous + HOUR).takeIf { it > previous && it < endExclusive }
    }.toList()
    return (grid + groups.keys).distinct().sorted().map { at ->
      val hour = groups[at]?.singleOrNull()?.takeIf { (at - start) % HOUR == 0L }
      ForecastRainSlot(at,
        hour?.values?.get(FusionVariables.PRECIP_PROBABILITY)?.value?.takeIf { it.isFinite() && it in 0.0..100.0 },
        hour?.values?.get(FusionVariables.PRECIPITATION)?.value?.takeIf { it.isFinite() && it >= 0.0 })
    }
  }
  fun sum(slots: List<ForecastRainSlot>): ForecastSampleSum {
    val amounts = slots.mapNotNull { it.amount }
    return ForecastSampleSum(amounts.size, slots.size,
      amounts.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it.isFinite() })
  }
}
