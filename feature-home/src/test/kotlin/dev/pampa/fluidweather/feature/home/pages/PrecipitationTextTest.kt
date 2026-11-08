package dev.pampa.fluidweather.feature.home.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class PrecipitationTextTest {
  @Test fun missingZeroAndSmallPositiveProbabilitiesStayDifferent() {
    assertEquals("\u2014", precipitationProbability(null))
    assertEquals("0%", precipitationProbability(0.0))
    assertEquals("<1%", precipitationProbability(.2))
    assertEquals("1%", precipitationProbability(1.0))
    assertEquals("100%", precipitationProbability(100.0))
  }
  @Test fun invalidValuesAreUnknownRatherThanPlausibleWeather() {
    listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0).forEach {
      assertEquals("\u2014", precipitationProbability(it))
    }
  }
}
