package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Compact rendering consumes the same guarded presentation data; no weather claims from holes. */
class HomeRainCompactContractTest {
  private val now = 1_800_000L
  private fun brief(probability: Double?) = WeatherBrief(
    LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, now),
    timeline = listOf(PrecipitationPeriod(WeatherInterval(now + 900_000, now + 2_700_000),
      PrecipitationState.DRY, amountMm = 0.0, probabilityPercent = probability,
      evidence = listOf(WeatherEvidence("model", WeatherEvidenceType.MODEL)),
      role = PrecipitationPeriodRole.FORECAST)))

  @Test fun partialDryNeverBecomesCurrentDryOrAnHourlyZeroTotal() {
    val b = brief(0.0)
    val forecast = HomeRainPresentation.forecast(b, now)
    assertEquals(RainHeadline.UNAVAILABLE, HomeRainPresentation.headline(b))
    assertNull(HomeRainPresentation.transition(b, now))
    assertEquals(RainForecastKind.DRY, forecast.kind)
    assertTrue(forecast.hasGaps)
    assertNull(forecast.totalAmountMm)
    assertEquals(0.0, forecast.maximumProbabilityPercent!!, 0.0)
    val parts = HomeRainPresentation.timeline(b, now)
    assertNull(parts.first().period)
    assertEquals(0.0, parts[1].period!!.amountMm!!, 0.0)
    assertNull(parts.last().period)
  }

  @Test fun missingProbabilityCannotBecomeZeroAndExpiredHourCannotStayUseful() {
    val b = brief(null)
    assertNull(HomeRainPresentation.forecast(b, now).maximumProbabilityPercent)
    val expired = HomeRainPresentation.forecast(b, now + 3_600_000)
    assertEquals(RainForecastKind.NONE, expired.kind)
    assertNull(expired.maximumProbabilityPercent)
    assertNull(expired.totalAmountMm)
    assertTrue(HomeRainPresentation.timeline(b, now + 3_600_000).all { it.period == null })
  }
}
