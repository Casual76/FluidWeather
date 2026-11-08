package dev.pampa.fluidweather.feature.home.pages

import dev.pampa.fluidweather.core.model.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DetailForecastTest {
  private val zone = ZoneId.of("Europe/Rome")
  private val now = Instant.parse("2026-11-08T14:10:00Z").toEpochMilli()
  private fun time(hour: Int) = Instant.parse("2026-11-08T${hour.toString().padStart(2, '0')}:00:00Z").toEpochMilli()
  private fun uv(t: Long, v: Double) = FusedHour(t, mapOf(FusionVariables.UV_INDEX to FusedValue(v, emptyList())), null)

  @Test fun availablePeakDoesNotClaimThePastNoonMaximum() {
    val hours = listOf(uv(time(11), 7.0), uv(time(14), 3.0), uv(time(15), 2.0), uv(time(16), 1.0))
    assertEquals(time(15) to 2.0, remainingUvPeak(hours, now, zone))
  }
  @Test fun nonfiniteNegativeAndAmbiguousSamplesCannotBecomeHazards() {
    val hours = listOf(uv(time(15), Double.POSITIVE_INFINITY), uv(time(16), Double.NaN),
      uv(time(17), -1.0), uv(time(18), 9.0), uv(time(18), 2.0), uv(time(19), 0.0))
    assertEquals(listOf(time(19) to 0.0), supportedUvSamples(hours))
    assertEquals(time(19) to 0.0, remainingUvPeak(hours, now, zone))
    assertNull(remainingUvPeak(hours.dropLast(1), now, zone))
  }
  @Test fun nextDayDoesNotBecomeTodaysPeakAndMidnightUsesTheDeclaredZone() {
    val hours = listOf(uv(time(22), 1.0), uv(time(23), 10.0))
    assertEquals(time(22) to 1.0, remainingUvPeak(hours, now, zone))
    assertEquals(time(23) to 10.0, remainingUvPeak(hours, now, ZoneId.of("UTC")))
  }
  @Test fun wholeWindowReachesDuplicateDetectionBeforeAnyCountCap() {
    val start = Instant.parse("2026-11-08T00:00:00Z").toEpochMilli()
    val hours = (0..22).map { uv(start + it * 3_600_000L, 1.0) } +
      listOf(uv(start + 23 * 3_600_000L, 8.0), uv(start + 23 * 3_600_000L, 2.0))
    val window = detailForecastWindow(hours, start + 10 * 60_000L)
    assertEquals(25, window.size)
    assertEquals(23, supportedUvSamples(window).size)
    assertFalse(supportedUvSamples(window).any { it.first == start + 23 * 3_600_000L })
  }
}
