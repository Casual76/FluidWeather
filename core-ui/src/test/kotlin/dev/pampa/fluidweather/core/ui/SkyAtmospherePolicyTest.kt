package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.PrecipitationState
import org.junit.Assert.*
import org.junit.Test

class SkyAtmospherePolicyTest {
  private val now = 100_000_000L
  private fun sample(cloud: Double = 30.0, rain: Double? = null) =
    SkyAtmosphereSample(now, now, cloud, precipitationRateMmPerHour = rain)
  private fun wet(rate: Double? = null) = SkyPresentPrecipitation(PrecipitationState.WET, now, now, rate)
  private fun scene(c: SkyAtmosphereSample = sample(), p: SkyPresentPrecipitation? = null,
    f: SkyAtmosphereSample? = null) = SkyAtmospherePolicy.derive(SkyAtmosphereInput(now, c, f, p))

  @Test fun dryThinCloudsArePaleAndShallow() {
    val sky = scene(sample(25.0))
    assertEquals(0.25f, sky.cloudCoverage, 0.001f)
    assertTrue(sky.cloudDepth < 0.15f)
    assertTrue(sky.cloudDarkness < 0.05f)
    assertEquals(0f, sky.precipitationStrength)
  }

  @Test fun drizzleAndDenseRainHaveDifferentLightAndDepth() {
    val drizzle = scene(sample(85.0, 0.3), wet(0.3))
    val heavy = scene(sample(98.0, 12.0), wet(12.0))
    assertTrue(drizzle.diffuseLight > 0.5f)
    assertTrue(drizzle.precipitationStrength in 0.01f..0.15f)
    assertTrue(heavy.cloudDepth > drizzle.cloudDepth)
    assertTrue(heavy.cloudDarkness > drizzle.cloudDarkness)
    assertTrue(heavy.precipitationStrength > drizzle.precipitationStrength)
  }

  @Test fun approachingHeavyFrontFiltersLightButCannotStartRainOrLightning() {
    val baseline = scene()
    val front = scene(f = sample(100.0, 20.0).copy(validAtMillis = now + 45 * 60_000))
    assertTrue(front.cloudDepth > baseline.cloudDepth)
    assertTrue(front.cloudDarkness > baseline.cloudDarkness)
    assertEquals(0f, front.precipitationStrength)
    assertEquals(0f, front.storminess)
  }

  @Test fun cloudAndHourlyRainNeverAuthorizePresentRain() {
    val sky = scene(sample(100.0, 30.0))
    assertTrue(sky.diffuseLight > 0.8f)
    assertEquals(0f, sky.precipitationStrength)
    assertEquals(0f, sky.storminess)
  }

  @Test fun alreadyOvercastSkyCanDeepenBeforeHeavyRainWithoutStartingParticles() {
    val baseline = scene(sample(100.0, 0.0))
    val front = scene(sample(100.0, 0.0), f = sample(100.0, 20.0).copy(validAtMillis = now + 45 * 60_000))
    assertTrue(front.cloudDepth > baseline.cloudDepth)
    assertTrue(front.cloudDarkness > baseline.cloudDarkness)
    assertEquals(0f, front.precipitationStrength)
    assertEquals(0f, front.storminess)
  }

  @Test fun staleConflictFutureAndMissingClocksCannotProduceRain() {
    for (p in listOf(wet(20.0).copy(conflicting = true),
      wet(20.0).copy(sourceAtMillis = now - 21 * 60_000),
      wet(20.0).copy(downloadedAtMillis = now - 91 * 60_000),
      wet(20.0).copy(sourceAtMillis = now + 1),
      wet(20.0).copy(downloadedAtMillis = now - 60_000),
      wet(20.0).copy(downloadedAtMillis = null),
      wet(20.0).copy(state = PrecipitationState.UNCERTAIN))) {
      assertEquals(0f, scene(p = p).precipitationStrength)
    }
    val stale = scene(sample(100.0).copy(downloadedAtMillis = now - 91 * 60_000))
    assertEquals(0.35f, stale.cloudCoverage, 0.001f)
    assertEquals(0f, stale.cloudDarkness)
  }

  @Test fun humidityDoesNotInventFogAndNightUsesSameOpticalTraits() {
    // No solar multiplier here: night/day colour and luminance remain renderer responsibilities.
    val humid = scene(sample().copy(relativeHumidityPercent = 100.0))
    val lowVisibility = scene(sample().copy(relativeHumidityPercent = 100.0, visibilityMeters = 500.0))
    assertTrue(humid.haze <= 0.16f)
    assertTrue(lowVisibility.haze > humid.haze)
    assertEquals(0f, lowVisibility.precipitationStrength)
  }

  @Test fun missingWindIsNeutralAndCalmAndStrongWindStayBounded() {
    assertEquals(1f, scene().windSpeedFactor)
    assertEquals(0.25f, scene(sample().copy(windSpeedKmh = 0.0)).windSpeedFactor)
    assertTrue(scene(sample().copy(windSpeedKmh = 200.0)).windSpeedFactor in 1f..2f)
    assertEquals(1f, scene(sample().copy(windSpeedKmh = Double.NaN)).windSpeedFactor)
  }
  @Test fun freshHourlyCloudDoesNotFadeToBlueWithinItsHalfHour() {
    val current = scene(sample(95.0))
    val sameHour = scene(sample(95.0).copy(validAtMillis = now - 29 * 60_000L))
    assertEquals(current.cloudCoverage, sameHour.cloudCoverage, 0f)
    assertEquals(current.diffuseLight, sameHour.diffuseLight, 0f)
    val distant = scene(sample(95.0).copy(validAtMillis = now - 75 * 60_000L))
    assertTrue(distant.cloudCoverage < sameHour.cloudCoverage)
  }

  @Test fun fresheningAndSmallInputChangesRemainContinuous() {
    val first = scene(sample(70.0))
    val second = scene(sample(70.1))
    assertTrue(kotlin.math.abs(second.cloudDepth - first.cloudDepth) < 0.01f)
    val fading = scene(p = wet(3.0).copy(sourceAtMillis = now - 18 * 60_000))
    assertTrue(fading.precipitationStrength > 0f)
    assertTrue(fading.precipitationStrength < scene(p = wet(3.0)).precipitationStrength)
  }

  @Test fun unknownRateNeverBecomesHeavyAndInvalidInputsStayFinite() {
    assertTrue(scene(p = wet()).precipitationStrength <= 0.12f)
    val invalid = scene(SkyAtmosphereSample(now, now, Double.NaN, Double.POSITIVE_INFINITY,
      -1.0, -2.0, -3.0), wet(Double.NaN))
    for (v in listOf(invalid.cloudDepth, invalid.cloudCoverage, invalid.cloudDarkness,
      invalid.diffuseLight, invalid.haze, invalid.precipitationStrength, invalid.storminess)) {
      assertTrue(v.isFinite() && v in 0f..1f)
    }
  }
}
