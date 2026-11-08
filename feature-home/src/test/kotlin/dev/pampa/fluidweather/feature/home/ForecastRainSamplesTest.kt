package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ForecastRainSamplesTest {
  private val h = ForecastRainSamples.HOUR
  private fun hour(at: Long, mm: Double? = null, probability: Double? = null) = FusedHour(at,
    buildMap {
      mm?.let { put(FusionVariables.PRECIPITATION, FusedValue(it, emptyList())) }
      probability?.let { put(FusionVariables.PRECIP_PROBABILITY, FusedValue(it, emptyList())) }
    }, null)

  @Test fun missingIsNotZeroAndTrueZeroSurvives() {
    val slots = ForecastRainSamples.slots(listOf(hour(0, 0.0, 0.0), hour(h)), 0, 2*h)
    assertEquals(0.0, slots[0].probability!!, 0.0)
    assertNull(slots[1].probability)
    assertEquals(ForecastSampleSum(1, 2, 0.0), ForecastRainSamples.sum(slots))
    assertNull(ForecastRainSamples.sum(slots.drop(1)).value)
  }
  @Test fun gapsKeepAbsolutePositionsAndPartialSumCount() {
    val slots = ForecastRainSamples.slots(listOf(hour(0, 2.0, 10.0), hour(2*h, 3.0, 20.0)), 0, 4*h)
    assertEquals(listOf(0L, h, 2*h, 3*h), slots.map { it.at })
    assertNull(slots[1].amount)
    assertEquals(ForecastSampleSum(2, 4, 5.0), ForecastRainSamples.sum(slots))
  }
  @Test fun duplicatesAndInvalidSamplesNeverEstablishDry() {
    val slots = ForecastRainSamples.slots(listOf(hour(0, 0.0, 0.0), hour(0, 0.0, 0.0),
      hour(h, -1.0, 101.0), hour(2*h, Double.NaN, Double.POSITIVE_INFINITY)), 0, 3*h)
    assertTrue(slots.all { it.amount == null && it.probability == null })
    assertNull(ForecastRainSamples.sum(slots).value)
  }
  @Test fun offGridOriginalIsRetainedButCannotProveCompatibleAccumulation() {
    val slots = ForecastRainSamples.slots(listOf(hour(h/2, 8.0, 40.0)), 0, 2*h)
    assertEquals(listOf(0L, h/2, h), slots.map { it.at })
    assertTrue(slots.all { it.amount == null })
  }
  @Test fun absentSixHoursAreSixUnknownPositions() {
    val slots = ForecastRainSamples.slots(emptyList(), 0, 6*h)
    assertEquals(ForecastSampleSum(0, 6, null), ForecastRainSamples.sum(slots))
  }
  @Test fun clippedResidualOmitsAmountAndKeepsUnknownGapAndEvidencePrecision() {
    val now = 1_800_000L
    val source = WeatherEvidence("model", WeatherEvidenceType.MODEL, temporalResolutionMillis = 900000)
    val brief = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, now), timeline = listOf(
      PrecipitationPeriod(WeatherInterval(now - 10000, now + 8000), PrecipitationState.WET,
        amountMm = 2.0, evidence = listOf(source), role = PrecipitationPeriodRole.FORECAST)))
    val parts = HomeRainPresentation.timeline(brief, now)
    assertEquals(WeatherInterval(now, now + 8000), parts.first().interval)
    assertNull(parts.first().period!!.amountMm)
    assertEquals(900000L, parts.first().period!!.evidence.single().temporalResolutionMillis)
    assertNull(parts.last().period)
  }

}
