package dev.pampa.fluidweather.core.model

import org.junit.Assert.*
import org.junit.Test

class WeatherBriefExtensionsTest {
  @Test fun `additive defaults preserve unknowns and old constructors`() {
    val assessment = LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, 10)
    val brief = WeatherBrief(assessment)
    assertTrue(brief.timeline.isEmpty())
    assertNull(brief.rainLastHour)
    assertEquals("", brief.revision)
    assertEquals(RadarAvailability.UNKNOWN, assessment.radarAvailability)
    assertNull(WeatherEvidence("test", WeatherEvidenceType.MODEL).sourceTimestampMillis)
  }

  @Test fun `unknown timeline gap has no invented amount or source`() {
    val period = PrecipitationPeriod(WeatherInterval(10, 20))
    assertEquals(PrecipitationState.UNAVAILABLE, period.state)
    assertNull(period.amountMm)
    assertNull(period.probabilityPercent)
    assertTrue(period.evidence.isEmpty())
  }

  @Test fun `source timestamp does not fabricate physical interval`() {
    val evidence = WeatherEvidence("radar", WeatherEvidenceType.RADAR_ESTIMATE,
      downloadedAtMillis = 100, sourceTimestampMillis = 90)
    assertNull(evidence.effectiveInterval)
    assertEquals(90L, evidence.sourceTimestampMillis)
    assertEquals(100L, evidence.downloadedAtMillis)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `timeline rejects invalid amount`() { PrecipitationPeriod(WeatherInterval(1, 2), amountMm = Double.NaN) }

  @Test(expected = IllegalArgumentException::class)
  fun `timeline rejects invalid probability`() { PrecipitationPeriod(WeatherInterval(1, 2), probabilityPercent = 101.0) }
}
