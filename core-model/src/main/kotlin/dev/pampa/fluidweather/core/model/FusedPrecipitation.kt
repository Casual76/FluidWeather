package dev.pampa.fluidweather.core.model

const val FUSED_PRECIPITATION_HOUR_MILLIS: Long = 3_600_000L

/** New fused rain/PoP values describe the preceding explicit hour, never an instant. */
fun FusedHour.knownPrecipitationInterval(): WeatherInterval? = precipitationInterval?.takeIf {
  it.endMillis == timestampMillis && it.endMillis - it.startMillis == FUSED_PRECIPITATION_HOUR_MILLIS
}

/** Duplicate endpoints or overlapping periods have no unique allocation. Legacy timing stays unknown. */
private fun List<FusedHour>.unambiguousPrecipitation(): List<FusedHour> {
  val sorted = groupBy { it.timestampMillis }.values.mapNotNull { it.singleOrNull() }
    .filter { it.knownPrecipitationInterval() != null }.sortedBy { it.precipitationInterval!!.startMillis }
  return sorted.filterIndexed { index, hour ->
    val interval = hour.precipitationInterval!!
    (index == 0 || sorted[index - 1].precipitationInterval!!.endMillis <= interval.startMillis) &&
      (index == sorted.lastIndex || sorted[index + 1].precipitationInterval!!.startMillis >= interval.endMillis)
  }
}

fun List<FusedHour>.precipitationAt(atMillis: Long): FusedHour? = unambiguousPrecipitation()
  .singleOrNull { it.precipitationInterval!!.let { interval -> atMillis >= interval.startMillis && atMillis < interval.endMillis } }

fun List<FusedHour>.precipitationStartingAt(startMillis: Long): FusedHour? = unambiguousPrecipitation()
  .singleOrNull { it.precipitationInterval!!.startMillis == startMillis }

/** Only complete declared periods belong to an accumulation window; no clipping/proration. */
fun List<FusedHour>.precipitationHours(startMillis: Long, endExclusive: Long): List<FusedHour> =
  if (endExclusive <= startMillis) emptyList() else unambiguousPrecipitation().filter {
    it.precipitationInterval!!.let { interval -> interval.startMillis >= startMillis && interval.endMillis <= endExclusive }
  }

data class FusedPrecipitationSummary(
  val hours: List<FusedHour>,
  val probabilityMaxPercent: Double?,
  val amountMm: Double?,
  val sampleAmountMm: Double?,
  val availableAmountSamples: Int,
  val expectedSamples: Int,
)

fun List<FusedHour>.precipitationSummary(startMillis: Long, endExclusive: Long): FusedPrecipitationSummary {
  val selected = precipitationHours(startMillis, endExclusive)
  val amounts = selected.mapNotNull { it.values[FusionVariables.PRECIPITATION]?.value
    ?.takeIf { mm -> mm.isFinite() && mm >= 0.0 } }
  val sum = amounts.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it.isFinite() }
  val duration = (endExclusive - startMillis).coerceAtLeast(0L)
  val expected = (duration / FUSED_PRECIPITATION_HOUR_MILLIS +
    if (duration % FUSED_PRECIPITATION_HOUR_MILLIS == 0L) 0L else 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  val complete = expected > 0 && selected.size == expected && amounts.size == expected &&
    selected.first().precipitationInterval!!.startMillis == startMillis &&
    selected.last().precipitationInterval!!.endMillis == endExclusive &&
    selected.zipWithNext().all { (a, b) -> a.precipitationInterval!!.endMillis == b.precipitationInterval!!.startMillis }
  return FusedPrecipitationSummary(selected,
    selected.mapNotNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value
      ?.takeIf { p -> p.isFinite() && p in 0.0..100.0 } }.maxOrNull(),
    sum.takeIf { complete }, sum, amounts.size, expected)
}
