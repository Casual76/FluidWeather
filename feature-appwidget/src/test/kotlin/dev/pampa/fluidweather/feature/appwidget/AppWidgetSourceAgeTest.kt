package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.ProviderFetchSummary
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import org.junit.Assert.*
import org.junit.Test

class AppWidgetSourceAgeTest {
  private val now = 1_800_000_000_000L
  private fun snapshot(download: Long?) = WeatherSnapshot("gps", 44.0, 11.0, now,
    listOf(ProviderFetchSummary("fixture", 1, null, download)),
    FusedForecast(listOf(FusedHour(now, mapOf(FusionVariables.TEMPERATURE to
      FusedValue(12.0, listOf(Contribution("fixture", 1.0, 12.0)))), null)), emptyMap()), null)
  private fun model(download: Long?) = AppWidgetModelBuilder.of(snapshot(download), "Here", null,
    now, AppWidgetTier.MEDIUM)

  @Test fun newRoundDoesNotRenewPreviouslyDownloadedWeather() {
    val result = model(now - 7 * 3_600_000L)
    assertEquals(now - 7 * 3_600_000L, result.dataAtMillis)
    assertEquals(DataFreshness.VERY_STALE, result.freshness)
    assertEquals(12.0, result.temperatureC!!, 0.0)
  }

  @Test fun unknownLegacyProvenanceKeepsForecastWithoutAClaimOfFreshness() {
    val result = model(null)
    assertEquals(12.0, result.temperatureC!!, 0.0)
    assertNull(result.dataAtMillis)
    assertEquals(DataFreshness.NONE, result.freshness)
    assertFalse(result.isEmpty)
  }

  @Test fun futureDownloadIsUnknownRatherThanJustUpdated() {
    val result = model(now + 3_600_000L)
    assertNull(result.dataAtMillis)
    assertNull(widgetAgeMinutes(result.dataAtMillis, now))
    assertEquals(DataFreshness.NONE, result.freshness)
  }
}
