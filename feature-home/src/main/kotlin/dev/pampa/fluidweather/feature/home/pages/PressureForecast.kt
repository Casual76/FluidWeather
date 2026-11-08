package dev.pampa.fluidweather.feature.home.pages

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables

private const val Hour = 3_600_000L

internal data class ThreeHourPressureOutlook(val pressureHpa: Double, val changeHpa: Double, val rateHpaPerHour: Double)

/** A source-supported comparison, not an extrapolation to replace a missing endpoint. */
internal fun threeHourPressureOutlook(series: List<Pair<Long, Double>>): ThreeHourPressureOutlook? {
  val first = series.firstOrNull() ?: return null
  if (!first.second.isFinite() || series.count { it.first == first.first } != 1) return null
  val target = runCatching { Math.addExact(first.first, 3 * Hour) }.getOrNull() ?: return null
  val next = series.singleOrNull { it.first == target }?.takeIf { it.second.isFinite() } ?: return null
  val change = next.second - first.second
  if (!change.isFinite()) return null
  val elapsedHours = (next.first - first.first).toDouble() / Hour
  return ThreeHourPressureOutlook(next.second, change, change / elapsedHours)
}

/** Two actual clock days; missing/duplicate pressure samples do not extend the horizon. */
internal fun pressureForecastSeries(hours: List<FusedHour>, nowMillis: Long): List<Pair<Long, Double>> {
  val start = nowMillis - Math.floorMod(nowMillis, Hour)
  val end = runCatching { Math.addExact(start, 48 * Hour) }.getOrNull() ?: return emptyList()
  return hours.filter { it.timestampMillis >= start && it.timestampMillis < end }
    .groupBy { it.timestampMillis }.toSortedMap().mapNotNull { (time, samples) ->
      samples.singleOrNull()?.values?.get(FusionVariables.PRESSURE_MSL)?.value
        ?.takeIf { it.isFinite() }?.let { time to it }
    }
}
