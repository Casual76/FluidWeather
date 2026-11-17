package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.ProviderFetchSummary
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import org.junit.Assert.*
import org.junit.Test

class HomeSourceTimeTest {
  private val now = 1_800_000_000_000L
  private fun snapshot(download: Long?) = WeatherSnapshot("gps", 44.0, 11.0, now,
    listOf(ProviderFetchSummary("fixture", 1, null, download)),
    FusedForecast(listOf(FusedHour(now, mapOf(FusionVariables.TEMPERATURE to
      FusedValue(12.0, listOf(Contribution("fixture", 1.0, 12.0)))), null)), emptyMap()), null)

  @Test fun presentationUsesContributingAcquisitionInsteadOfFreshRoundIdentity() {
    val cachedSource = now - 3_600_000L
    assertEquals(cachedSource, homeSourceTime(snapshot(cachedSource), now))
  }

  @Test fun unknownAndFutureSourceTimesStayUnknownForPresentation() {
    assertNull(homeSourceTime(snapshot(null), now))
    assertNull(homeSourceTime(snapshot(now + 3_600_000L), now))
  }
}
