package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import org.junit.Assert.*
import org.junit.Test

class AppWidgetPrecipitationIntervalsTest {
  private val now = 1_800_000_000_000L
  private val h = FUSED_PRECIPITATION_HOUR_MILLIS
  private val boundary = now - Math.floorMod(now, h)
  private fun rain(start: Long, probability: Double, known: Boolean = true) = FusedHour(start + h,
    mapOf(FusionVariables.PRECIP_PROBABILITY to FusedValue(probability, emptyList())), null,
    precipitationInterval = WeatherInterval(start, start + h).takeIf { known })
  private fun model(hours: List<FusedHour>) = AppWidgetModelBuilder.of(
    WeatherSnapshot("gps", 44.0, 11.0, boundary, emptyList(), FusedForecast(hours, emptyMap()), null),
    "Here", null, boundary, AppWidgetTier.MEDIUM)

  @Test fun alreadyEndedAndLegacyRainCannotOutvoteUpcomingDeclaredInterval() {
    val result = model(listOf(rain(boundary - h, 99.0), rain(boundary, 40.0),
      rain(boundary + 2*h, 100.0, known = false)))
    assertEquals(40, result.rainProbabilityPercent)
  }

  @Test fun outlookIsSixActualHoursRatherThanSixRowsAcrossAnArbitraryGap() {
    assertNull(model(listOf(rain(boundary + 10*h, 95.0))).rainProbabilityPercent)
    assertEquals(0, model(listOf(rain(boundary, 0.0))).rainProbabilityPercent)
  }
}
