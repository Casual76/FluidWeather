package dev.pampa.fluidweather.strings

import dev.pampa.fluidweather.core.model.*
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class RoutinePresentationTest {
  @Test fun `known resolutions retain exact duration without inferring an hour or truncating to zero`() {
    assertEquals("60 min", routineResolution(3_600_000))
    assertEquals("30 s", routineResolution(30_000))
    assertEquals("1500 ms", routineResolution(1_500))
  }
  private fun unknown() = RoutineWindowAssessment(
    RoutineOccurrence(RoutineWindow("a", "Night", setOf(DayOfWeek.SUNDAY), LocalTime.of(23, 0), LocalTime.of(1, 0)),
      WeatherInterval(1_000, 10_000), ResolvedWeatherZone(ZoneId.of("Europe/Rome"), WeatherZoneSource.DEVICE_FALLBACK), true),
    2_000, assessedInterval = WeatherInterval(2_000, 10_000),
  )
  @Test fun `unknown never uses dry wording and surfaces coverage fallback DST remaining`() {
    val result = unknown()
    assertEquals(R.string.routines_unknown, routineForecastRes(result))
    assertTrue(routineLimitResources(result).containsAll(listOf(R.string.routines_partial, R.string.routines_fallback,
      R.string.routines_dst, R.string.routines_remaining)))
  }
  @Test fun `stale evidence remains explicit even when top level omits flag`() {
    val evidence = WeatherEvidence("model", WeatherEvidenceType.MODEL, limits = setOf(WeatherEvidenceLimit.STALE))
    assertTrue(R.string.routines_stale in routineLimitResources(unknown().copy(evidence = listOf(evidence))))
  }
  @Test fun `days are localized and ordered by start weekday`() {
    assertEquals("Monday · Sunday", routineDays(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), Locale.ENGLISH))
    assertEquals("lunedì · domenica", routineDays(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), Locale.ITALIAN))
  }
}
