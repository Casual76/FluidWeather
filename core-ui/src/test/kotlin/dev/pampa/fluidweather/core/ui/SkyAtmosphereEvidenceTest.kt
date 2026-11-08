package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SkyAtmosphereEvidenceTest {
  private val now = 100_000_000L
  private fun bundle() = ForecastBundle("model", now, 45.0, 9.0,
    listOf(HourlyPoint(now, cloudCoverPercent = 80.0)))
  private fun wet(point: GeoPoint? = GeoPoint(45.0, 9.0)) = LocalPrecipitationAssessment(
    PrecipitationState.WET, now, coordinates = point, evidence = listOf(WeatherEvidence(
      "radar", WeatherEvidenceType.RADAR_ESTIMATE, downloadedAtMillis = now,
      precipitationState = PrecipitationState.WET, sourceTimestampMillis = now - 300_000,
    )))
  private fun derive(b: ForecastBundle? = bundle(), p: LocalPrecipitationAssessment? = null) =
    SkyAtmosphereEvidence.derive(b, p, 45.0, 9.0, now)

  @Test fun missingStaleWrongPointAndDuplicateContextPreserveLegacyAppearance() {
    assertNull(derive(null))
    assertNull(derive(bundle().copy(fetchedAtMillis = now - 90 * 60_000)))
    assertNull(derive(bundle().copy(latitude = 43.0)))
    assertNull(derive(bundle().copy(hourly = bundle().hourly + bundle().hourly)))
    assertNull(derive(bundle().copy(hourly = listOf(HourlyPoint(now, cloudCoverPercent = Double.NaN)))))
  }

  @Test fun radarWetDoesNotDependOnModelAvailabilityButCannotBorrowForeignSignal() {
    assertTrue(derive(null, wet())!!.precipitationStrength > 0f)
    assertNull(derive(null, wet(GeoPoint(40.0, 9.0))))
    assertNull(derive(null, wet().copy(evaluatedAtMillis = now - 120_001)))
    assertNull(derive(null, wet().copy(limits = setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS))))
  }

  @Test fun fullOvercastFrontUsesKnownAccumulationAndCannotStartRain() {
    val current = HourlyPoint(now, cloudCoverPercent = 100.0, precipitationMm = 0.0)
    val later = HourlyPoint(now + 60 * 60_000, cloudCoverPercent = 100.0, precipitationMm = 20.0)
    val b = bundle().copy(hourly = listOf(current, later),
      hourlyPrecipitation = PrecipitationAccumulation(3_600_000, AccumulationAnchor.PRECEDING))
    val dry = derive(b.copy(hourly = listOf(current)))!!
    val front = derive(b)!!
    assertTrue(front.cloudDepth > dry.cloudDepth)
    assertEquals(0f, front.precipitationStrength)
    assertEquals(0f, front.storminess)
    assertEquals(dry.cloudDepth, derive(b.copy(hourlyPrecipitation = null))!!.cloudDepth)
  }
}
