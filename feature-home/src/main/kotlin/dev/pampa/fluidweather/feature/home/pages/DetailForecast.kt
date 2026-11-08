package dev.pampa.fluidweather.feature.home.pages

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import java.time.Instant
import java.time.ZoneId

/** Keep the complete time window so a count cap cannot hide an ambiguous timestamp. */
internal fun detailForecastWindow(hours: List<FusedHour>, nowMillis: Long): List<FusedHour> =
  hours.filter { it.timestampMillis >= nowMillis - 30 * 60_000L && it.timestampMillis < nowMillis + 24 * 3_600_000L }

internal fun supportedUvSamples(hours: List<FusedHour>): List<Pair<Long, Double>> =
  curveSamples(hours, FusionVariables.UV_INDEX).filter { it.second >= 0.0 }

/** Available forecast samples after now, through local midnight; never a whole-day maximum. */
internal fun remainingUvPeak(hours: List<FusedHour>, nowMillis: Long, zone: ZoneId): Pair<Long, Double>? {
  val end = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
    .atStartOfDay(zone).toInstant().toEpochMilli()
  return supportedUvSamples(hours).filter { it.first >= nowMillis && it.first < end }.maxByOrNull { it.second }
}
