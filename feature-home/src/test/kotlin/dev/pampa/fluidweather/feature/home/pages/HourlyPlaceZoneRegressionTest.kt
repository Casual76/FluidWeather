package dev.pampa.fluidweather.feature.home.pages

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.UnitFormatter
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Calls the production row builder with the same brief metadata supplied to Home. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HourlyPlaceZoneRegressionTest {
  private val originalZone = TimeZone.getDefault()
  private val originalLocale = Locale.getDefault()
  private val now = Instant.parse("2026-11-17T00:00:00Z").toEpochMilli()

  @Before fun prepare() {
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
    Locale.setDefault(Locale.ITALY)
    TimeFormats.invalidate()
  }

  @After fun restore() {
    TimeZone.setDefault(originalZone)
    Locale.setDefault(originalLocale)
    TimeFormats.invalidate()
  }

  private fun model(zoneId: String?): HourlyModel {
    val place = Place(77L, "New York", null, 40.7, -74.0, zoneId)
    val state = HomeUiState(latitude = place.latitude, longitude = place.longitude,
      fusedHours = (0..6).map { hour -> FusedHour(now + hour * 3_600_000L,
        mapOf(FusionVariables.TEMPERATURE to FusedValue(10.0 + hour, emptyList())), WeatherKind.CLEAR) },
      weatherBrief = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE,
        now, place, GeoPoint(place.latitude, place.longitude))))
    val resources = RuntimeEnvironment.getApplication().resources
    return HourlyModel.of(state, now, UnitFormatter(resources, UnitPreferences.METRIC, Locale.ENGLISH),
      resources, "Today")!!
  }

  @Test fun savedPlaceRowsUseItsClockAndMidnightBoundary() {
    val days = model("America/New_York").days
    assertEquals("The seven consulted hours cross midnight in New York", 2, days.size)
    assertEquals("Today", days.first().label)
    assertEquals(listOf("19", "20", "21", "22", "23"), days.first().rows.map { it.hour })
    assertEquals(listOf("00", "01"), days.last().rows.map { it.hour })
  }

  @Test fun changingConsultedPlaceZoneRebuildsActualHourRows() {
    assertEquals("19", model("America/New_York").days.first().rows.first().hour)
    assertEquals("09", model("Asia/Tokyo").days.first().rows.first().hour)
  }

  @Test fun absentZoneRetainsDeviceFallbackInsteadOfInventingLocationMetadata() {
    assertEquals("01", model(null).days.first().rows.first().hour)
    assertEquals(1, model(null).days.size)
  }

  @Test fun rowPairsInstantTemperatureWithRainIntervalStartingAtThatHour() {
    val resources = RuntimeEnvironment.getApplication().resources
    val units = UnitFormatter(resources, UnitPreferences.METRIC, Locale.ENGLISH)
    val hours = listOf(
      FusedHour(now, mapOf(FusionVariables.TEMPERATURE to FusedValue(10.0, emptyList())), null),
      FusedHour(now + 3_600_000L, mapOf(
        FusionVariables.TEMPERATURE to FusedValue(20.0, emptyList()),
        FusionVariables.PRECIPITATION to FusedValue(2.0, emptyList()),
        FusionVariables.PRECIP_PROBABILITY to FusedValue(80.0, emptyList())), null,
        precipitationInterval = WeatherInterval(now, now + 3_600_000L)))
    val rows = HourlyModel.of(HomeUiState(zoneId = "UTC", fusedHours = hours), now,
      units, resources, "Today")!!.days.single().rows
    assertEquals(units.degrees(10.0), rows.first().temperature)
    assertEquals("80%", rows.first().probability)
    assertEquals(precipitationSampleValue(2.0, units), rows.first().precipitation)
    assertEquals(units.degrees(20.0), rows.last().temperature)
    assertEquals("—", rows.last().probability)
  }

  @Test fun rawLegacyRainIsNotAssignedAForwardHourByTheProductionBuilder() {
    val resources = RuntimeEnvironment.getApplication().resources
    val hour = FusedHour(now, mapOf(FusionVariables.PRECIPITATION to FusedValue(2.0, emptyList()),
      FusionVariables.PRECIP_PROBABILITY to FusedValue(80.0, emptyList())), null)
    val rows = HourlyModel.of(HomeUiState(zoneId = "UTC", fusedHours = listOf(hour)), now,
      UnitFormatter(resources, UnitPreferences.METRIC, Locale.ENGLISH), resources, "Today")!!.days.single().rows
    assertEquals("—", rows.single().probability)
    assertEquals("—", rows.single().precipitation)
  }
}
