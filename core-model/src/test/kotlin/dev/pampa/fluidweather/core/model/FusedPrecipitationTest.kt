package dev.pampa.fluidweather.core.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class FusedPrecipitationTest {
  private val h = FUSED_PRECIPITATION_HOUR_MILLIS
  private val date = LocalDate.of(2026, 11, 17)
  private val zone = ZoneId.of("UTC")
  private val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
  private fun row(start: Long, mm: Double? = 1.0, pop: Double? = 80.0) = FusedHour(start + h,
    buildMap {
      put(FusionVariables.TEMPERATURE, FusedValue(20.0, emptyList()))
      mm?.let { put(FusionVariables.PRECIPITATION, FusedValue(it, emptyList())) }
      pop?.let { put(FusionVariables.PRECIP_PROBABILITY, FusedValue(it, emptyList())) }
    }, null, precipitationInterval = WeatherInterval(start, start + h))

  @Test fun intervalContainmentIsHalfOpenAndLegacyAndAmbiguityRemainUnknown() {
    val before = row(midnight - h)
    val after = row(midnight, 2.0)
    val hours = listOf(before, after)
    assertSame(before, hours.precipitationAt(midnight - 1))
    assertSame(after, hours.precipitationAt(midnight))
    assertNull(listOf(before).precipitationAt(midnight))
    assertNull(listOf(after.copy(precipitationInterval = null)).precipitationAt(midnight))
    assertNull(listOf(after, after).precipitationAt(midnight))
    assertNull(listOf(after, row(midnight + h/2)).precipitationAt(midnight + h/2))
  }

  @Test fun dailyRainAtMidnightBelongsToPreviousDayWhileTemperatureStaysAtEndpoint() {
    val before = row(midnight - h, 4.0)
    val instantBefore = FusedHour(midnight - h, mapOf(FusionVariables.TEMPERATURE to FusedValue(10.0, emptyList())), null)
    val summaries = DailyAggregate.of(listOf(instantBefore, before, row(midnight, 2.0)), zone,
      null, null, date.minusDays(1))
    val yesterday = summaries.first()
    val today = summaries.last()
    assertEquals(10.0, yesterday.maxC, 0.0)
    assertEquals(listOf(before), yesterday.rainHours)
    assertEquals(4.0, yesterday.precipitationSampleSumMm!!, 0.0)
    assertNull(yesterday.precipitationMm)
    assertEquals(20.0, today.maxC, 0.0)
    assertEquals(2.0, today.precipitationSampleSumMm!!, 0.0)
    assertTrue(DailyAggregate.parts(yesterday, zone).last().rainHours.contains(before))
  }

  @Test fun completeDayTotalNeedsEveryDeclaredAmountAndIncludesFinalMidnightEndpoint() {
    val complete = (0..23).map { row(midnight + it*h) }
    val sum = complete.precipitationSummary(midnight, midnight + 24*h)
    assertEquals(24.0, sum.amountMm!!, 0.0)
    assertEquals(24, sum.expectedSamples)
    assertEquals(midnight + 24*h, sum.hours.last().timestampMillis)
    val missing = complete.dropLast(1).precipitationSummary(midnight, midnight + 24*h)
    assertNull(missing.amountMm)
    assertEquals(23.0, missing.sampleAmountMm!!, 0.0)
    assertEquals(23, missing.availableAmountSamples)
    val unknown = complete.map { it.copy(precipitationInterval = null) }.precipitationSummary(midnight, midnight + 24*h)
    assertNull(unknown.sampleAmountMm)
    assertEquals(0, unknown.availableAmountSamples)
  }

  @Test fun springDstDayHasTwentyThreeActualIntervalsAndNoInventedTwentyFourth() {
    val rome = ZoneId.of("Europe/Rome")
    val spring = LocalDate.of(2026, 3, 29)
    val start = spring.atStartOfDay(rome).toInstant().toEpochMilli()
    val end = spring.plusDays(1).atStartOfDay(rome).toInstant().toEpochMilli()
    val samples = (0..22).map { row(start + it*h, 0.0, 0.0) }
    val result = samples.precipitationSummary(start, end)
    assertEquals(23, result.expectedSamples)
    assertEquals(0.0, result.amountMm!!, 0.0)
    assertEquals(0.0, result.probabilityMaxPercent!!, 0.0)
  }

  @Test fun widgetOutlookStartsAtCurrentExactHourAndRejectsAlreadyEndedAndLegacyIntervals() {
    val hours = listOf(row(midnight - h, 0.0, 99.0), row(midnight, 0.0, 40.0),
      row(midnight + 6*h, 0.0, 95.0), row(midnight + 2*h, 0.0, 100.0).copy(precipitationInterval = null))
    assertEquals(40, hours.rainProbabilityPercent(midnight))
    assertEquals(95, hours.rainProbabilityPercent(midnight + h/2))
    assertNull(hours.rainProbabilityPercent(midnight, 0))
  }
}
