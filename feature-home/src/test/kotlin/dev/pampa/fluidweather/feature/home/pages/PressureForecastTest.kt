package dev.pampa.fluidweather.feature.home.pages

import dev.pampa.fluidweather.core.model.*

import org.junit.Assert.*
import org.junit.Test

class PressureForecastTest {
  private val hour = 3_600_000L
  @Test fun seventeenHourDifferenceDoesNotBecomeAThreeHourStorm() {
    assertNull(threeHourPressureOutlook(listOf(0L to 1020.0, 17 * hour to 1016.0)))
  }
  @Test fun exactThreeHourSampleRetainsItsRealChangeAndRate() {
    val result = threeHourPressureOutlook(listOf(0L to 1020.0, hour to 1019.5, 3 * hour to 1018.5, 17 * hour to 1005.0))!!
    assertEquals(1018.5, result.pressureHpa, 0.0)
    assertEquals(-1.5, result.changeHpa, 0.0)
    assertEquals(-.5, result.rateHpaPerHour, 0.0)
  }
  @Test fun nearOrLaterSamplesDoNotReplaceTheMissingEndpoint() {
    assertNull(threeHourPressureOutlook(listOf(0L to 1020.0, 3 * hour - 1 to 1019.0, 3 * hour + 1 to 1018.0)))
  }
  @Test fun ambiguousOrNonfiniteEndpointsAreUnavailable() {
    assertNull(threeHourPressureOutlook(listOf(0L to 1020.0, 3 * hour to 1019.0, 3 * hour to 1018.0)))
    assertNull(threeHourPressureOutlook(listOf(0L to 1020.0, 0L to 1021.0, 3 * hour to 1019.0)))
    assertNull(threeHourPressureOutlook(listOf(0L to 1020.0, 3 * hour to Double.NaN)))
    assertNull(threeHourPressureOutlook(emptyList()))
  }
  private fun sample(at: Long, value: Double?) = FusedHour(at,
    value?.let { mapOf(FusionVariables.PRESSURE_MSL to FusedValue(it, emptyList())) }.orEmpty(), null)
  @Test fun twoDayHorizonUsesElapsedTimeInsteadOfFortyEightAvailableValues() {
    val hours = listOf(sample(-hour, 1021.0), sample(0, 1020.0), sample(47 * hour, 1019.0),
      sample(48 * hour, 1018.0), sample(70 * hour, 1017.0))
    assertEquals(listOf(0L to 1020.0, 47 * hour to 1019.0), pressureForecastSeries(hours, hour / 2))
  }
  @Test fun duplicateForecastClocksAreUnknownRatherThanArbitrarilyChosen() {
    val result = pressureForecastSeries(listOf(sample(0, 1020.0), sample(0, 1021.0), sample(3 * hour, 1019.0)), 0)
    assertEquals(listOf(3 * hour to 1019.0), result)
  }
  @Test fun missingAndInvalidSourceValuesNeverBecomeZero() {
    assertEquals(listOf(4 * hour to 1018.0), pressureForecastSeries(
      listOf(sample(0, null), sample(hour, Double.NaN), sample(2 * hour, Double.POSITIVE_INFINITY), sample(4 * hour, 1018.0)), 0))
  }
}
