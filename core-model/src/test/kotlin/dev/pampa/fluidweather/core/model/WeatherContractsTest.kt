package dev.pampa.fluidweather.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WeatherContractsTest {
  @Test fun unavailablePreservesUnknowns() {
    val result = LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, 1000)
    assertNull(result.coordinates)
    assertNull(result.horizon)
    assertNull(result.arrivalAtMillis)
    assertNull(WeatherBrief(result).current)
  }

  @Test fun pastModelAndConflictingMeasurementRetainProvenance() {
    val model = WeatherEvidence("open-meteo", WeatherEvidenceType.MODEL,
      WeatherInterval(100, 200), 90, precipitationState = PrecipitationState.WET)
    val measured = WeatherEvidence("local-gauge", WeatherEvidenceType.MEASUREMENT,
      WeatherInterval(100, 200), 210, precipitationState = PrecipitationState.DRY)
    val result = LocalPrecipitationAssessment(PrecipitationState.UNCERTAIN, 300,
      evidence = listOf(model, measured), limits = setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS))
    assertEquals(WeatherEvidenceType.MODEL, result.evidence.first().type)
    assertEquals(90L, result.evidence.first().downloadedAtMillis)
    assertEquals(PrecipitationState.DRY, result.evidence.last().precipitationState)
  }

  @Test fun invalidIntervalsCoordinatesAndUnsupportedArrivalAreRejected() {
    assertTrue(runCatching { GeoPoint(Double.NaN, 0.0) }.isFailure)
    assertTrue(runCatching { WeatherInterval(2, 1) }.isFailure)
    assertTrue(runCatching { LocalPrecipitationAssessment(PrecipitationState.WET, 1) }.isFailure)
    assertTrue(runCatching { CurrentWeatherSummary(WeatherKind.RAIN, 15.0) }.isFailure)
    assertTrue(runCatching {
      LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, 1, arrivalAtMillis = 0)
    }.isFailure)
  }

  @Test fun routineOvernightBelongsToItsStartDay() {
    val routine = RoutineWindow("night", "Night", setOf(DayOfWeek.FRIDAY),
      LocalTime.of(23, 0), LocalTime.of(1, 0))
    assertTrue(routine.crossesMidnight)
    assertEquals(setOf(DayOfWeek.FRIDAY), routine.daysOfWeek)
    assertTrue(runCatching { routine.copy(end = routine.start) }.isFailure)
  }

  @Test fun invalidZoneFallsBackExplicitlyAndValidPlaceWins() {
    val device = ZoneId.of("Asia/Tokyo")
    assertNull(WeatherZones.validId("Europe/Invented"))
    assertNull(WeatherZones.validId("+02:00"))
    assertEquals(WeatherZoneSource.DEVICE_FALLBACK,
      WeatherZones.resolve("invalid", null, device).source)
    assertEquals(device, WeatherZones.resolve(null, null, device).zoneId)
    assertEquals(WeatherZoneSource.SNAPSHOT,
      WeatherZones.resolve(null, "Europe/Rome", device).source)
    assertEquals(ZoneId.of("Europe/Rome"),
      WeatherZones.resolve("Europe/Rome", "America/New_York", device).zoneId)
  }

  @Test fun resolvedIanaZoneUsesDstRulesWithoutShiftingAbsoluteInstants() {
    val zone = WeatherZones.resolve("Europe/Rome", null, ZoneId.of("UTC")).zoneId
    val before = Instant.parse("2026-03-29T00:30:00Z")
    val after = before.plusSeconds(3600)
    assertEquals(1, before.atZone(zone).hour)
    assertEquals(3, after.atZone(zone).hour)
    val overlap1 = Instant.parse("2026-10-25T00:30:00Z").atZone(zone)
    val overlap2 = Instant.parse("2026-10-25T01:30:00Z").atZone(zone)
    assertEquals(overlap1.toLocalTime(), overlap2.toLocalTime())
    assertNotEquals(overlap1.offset, overlap2.offset)
  }
}
