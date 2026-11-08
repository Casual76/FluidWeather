package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import org.junit.Assert.*
import org.junit.Test

class HomeSkyAtmosphereTest {
  private val now = 100_000_000L
  private val state = HomeUiState(latitude = 45.0, longitude = 9.0)
  private fun bundle(download: Long = now, current: ProviderCurrent? = null) = ForecastBundle(
    "model", download, 45.0, 9.0,
    listOf(HourlyPoint(now, cloudCoverPercent = 95.0, precipitationMm = 12.0)),
    current = current,
  )
  private fun snapshot(b: ForecastBundle) = WeatherSnapshot(
    "gps", 45.0, 9.0, now, emptyList(), FusedForecast(emptyList(), emptyMap()), b,
  )
  private fun brief(c: ProviderCurrent) = WeatherBrief(LocalPrecipitationAssessment(
    PrecipitationState.WET, now, evidence = listOf(WeatherEvidence(
      "model", WeatherEvidenceType.MODEL, c.precipitationInterval, c.downloadedAtMillis,
      precipitationState = PrecipitationState.WET, sourceTimestampMillis = c.timestampMillis,
    )),
  ))

  @Test fun newRoundCannotRejuvenateOldContext() {
    val old = deriveHomeSkyAtmosphere(state, snapshot(bundle(now - 91 * 60_000)), now)
    assertEquals(0f, old.cloudDepth)
    assertEquals(0.35f, old.cloudCoverage, 0.001f)
  }

  @Test fun unknownHourlyDurationCannotDarkenAsHeavyRainOrAuthorizePresentRain() {
    val unknown = deriveHomeSkyAtmosphere(state, snapshot(bundle()), now)
    val known = deriveHomeSkyAtmosphere(state, snapshot(bundle().copy(
      hourlyPrecipitation = PrecipitationAccumulation(3_600_000L, AccumulationAnchor.PRECEDING))), now)
    assertTrue(known.cloudDepth > unknown.cloudDepth)
    assertEquals(0f, known.precipitationStrength)
    assertEquals(0f, unknown.precipitationStrength)
  }

  @Test fun supportedRecentCurrentRateRequiresMatchingWetEvidence() {
    val c = ProviderCurrent(now, kind = WeatherKind.RAIN, precipitationMm = 3.0,
      accumulationIntervalMillis = 900_000L, downloadedAtMillis = now)
    val wet = state.copy(weatherBrief = brief(c))
    val sky = deriveHomeSkyAtmosphere(wet, snapshot(bundle(current = c)), now)
    assertEquals(0.8f, sky.precipitationStrength, 0.001f)
    val unmatched = deriveHomeSkyAtmosphere(wet, snapshot(bundle(current = c.copy(
      accumulationIntervalMillis = 300_000L))), now)
    assertEquals(0.12f, unmatched.precipitationStrength, 0.001f)
  }

  @Test fun futureAccumulationAndWrongLocationCannotMakePresentRain() {
    val c = ProviderCurrent(now + 60_000, kind = WeatherKind.THUNDERSTORM,
      precipitationMm = 8.0, accumulationIntervalMillis = 900_000L, downloadedAtMillis = now)
    val future = deriveHomeSkyAtmosphere(state.copy(weatherBrief = brief(c)), snapshot(bundle(current = c)), now)
    assertEquals(0f, future.precipitationStrength)
    assertEquals(0f, future.storminess)
    val elsewhere = deriveHomeSkyAtmosphere(state.copy(latitude = 40.0), snapshot(bundle()), now)
    assertEquals(0f, elsewhere.cloudDepth)
  }
}
