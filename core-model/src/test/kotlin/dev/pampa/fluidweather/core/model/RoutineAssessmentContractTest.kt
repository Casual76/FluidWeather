package dev.pampa.fluidweather.core.model

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RoutineAssessmentContractTest {
  private val rule = RoutineWindow("stable", "Night 🌙", setOf(DayOfWeek.MONDAY), LocalTime.of(23, 0), LocalTime.of(1, 0))
  private val occurrence = RoutineOccurrence(rule, WeatherInterval(100, 300), ResolvedWeatherZone(ZoneId.of("UTC"), WeatherZoneSource.PLACE))
  private val evidence = listOf(WeatherEvidence("model", WeatherEvidenceType.MODEL, WeatherInterval(200, 300), 150))

  @Test fun `new assessment and brief contain no fabricated weather or routines`() {
    val result = RoutineWindowAssessment(occurrence, 200)
    assertEquals(PrecipitationState.UNAVAILABLE, result.precipitation)
    assertNull(result.rainAmountMm); assertNull(result.minTemperatureC)
    assertFalse(result.hasCompleteCoverage)
    assertTrue(WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, 200)).routines.isEmpty())
    assertTrue(rule.crossesMidnight)
  }
  @Test fun `complete remaining coverage retains full display occurrence`() {
    val result = RoutineWindowAssessment(occurrence, 200, PrecipitationState.DRY, 100, 0.0,
      evidence = evidence, assessedInterval = WeatherInterval(200, 300))
    assertTrue(result.hasCompleteCoverage)
    assertEquals(100L, result.occurrence.interval.startMillis)
    assertEquals(200L, result.assessedInterval.startMillis)
  }
  @Test fun `partial total missing evidence invalid coverage and invalid settings are rejected`() {
    assertTrue(runCatching { RoutineWindowAssessment(occurrence, 200, rainAmountMm = 0.0) }.isFailure)
    assertTrue(runCatching { RoutineWindowAssessment(occurrence, 200, PrecipitationState.WET) }.isFailure)
    assertTrue(runCatching { RoutineWindowAssessment(occurrence, 200, minTemperatureC = 20.0) }.isFailure)
    assertTrue(runCatching { RoutineWindowAssessment(occurrence, 200, coveredMillis = 201, evidence = evidence) }.isFailure)
    assertTrue(runCatching { RoutineWindowAssessment(occurrence, 200, assessedInterval = WeatherInterval(99, 300)) }.isFailure)
    assertTrue(runCatching { rule.copy(start = rule.end) }.isFailure)
    assertTrue(runCatching { rule.copy(daysOfWeek = emptySet()) }.isFailure)
    assertTrue(runCatching { rule.copy(name = " ") }.isFailure)
  }
}
