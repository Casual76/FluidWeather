package dev.pampa.fluidweather.strings

import dev.pampa.fluidweather.core.model.*
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class WeatherGlanceSummaryTest {
  private val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()
  private val source = WeatherEvidence("open-meteo", WeatherEvidenceType.MODEL,
    downloadedAtMillis = now, sourceTimestampMillis = now)
  private fun assessment() = RoutineWindowAssessment(RoutineOccurrence(
    RoutineWindow("school", "School", setOf(DayOfWeek.MONDAY), LocalTime.of(7, 20), LocalTime.of(14, 20)),
    WeatherInterval(now + 3_600_000, now + 7_200_000), ResolvedWeatherZone(ZoneId.of("Europe/Rome"), WeatherZoneSource.PLACE)),
    now, PrecipitationState.UNCERTAIN, coveredMillis = 1_800_000, maxProbabilityPercent = 33.0,
    minTemperatureC = 13.0, maxTemperatureC = 15.0, evidence = listOf(source))

  @Test fun `partial samples stay useful but expiry never exposes their quantities`() {
    val a = assessment()
    assertTrue(WeatherGlanceSummary.routineUsable(a, now))
    assertTrue(WeatherGlanceSummary.partial(a))
    assertFalse(WeatherGlanceSummary.routineUsable(a, now + 60_000))
    assertFalse(WeatherGlanceSummary.routineUsable(a.copy(evaluatedAtMillis = now + 1), now))
    assertFalse(WeatherGlanceSummary.routineUsable(a.copy(evidence = listOf(source.copy(downloadedAtMillis = null))), now))
    assertFalse(WeatherGlanceSummary.routineUsable(a.copy(limits = setOf(WeatherEvidenceLimit.LOCATION_MISMATCH)), now))
  }

  @Test fun `source identity is deduplicated while different products remain`() {
    val b = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNCERTAIN, now,
      evidence = listOf(source, source.copy(sourceTimestampMillis = now + 1))),
      current = CurrentWeatherSummary(temperatureC = 13.0, evidence = listOf(source)), routines = listOf(assessment()))
    assertEquals(listOf("open-meteo"), WeatherGlanceSummary.sources(b))
    assertEquals(2, WeatherGlanceSummary.sources(b.copy(current = b.current!!.copy(
      evidence = listOf(source.copy(sourceId = "other-model"))))).size)
  }

  @Test fun `overnight shows both dates and overlap shows both offsets`() {
    val zone = ZoneId.of("Europe/Rome")
    val start = Instant.parse("2026-10-25T00:20:00Z").toEpochMilli()
    val overlap = assessment().occurrence.copy(interval = WeatherInterval(start, start + 3_600_000), adjustedForDst = true)
    val label = WeatherGlanceSummary.time(overlap, Locale.ITALIAN)
    assertTrue(label.contains("+02:00")); assertTrue(label.contains("+01:00"))
    val night = assessment().occurrence.copy(interval = WeatherInterval(
      ZonedDateTime.of(2026, 11, 8, 23, 0, 0, 0, zone).toInstant().toEpochMilli(),
      ZonedDateTime.of(2026, 11, 9, 7, 0, 0, 0, zone).toInstant().toEpochMilli()))
    val overnight = WeatherGlanceSummary.time(night, Locale.UK)
    assertTrue(overnight.contains("8 Nov")); assertTrue(overnight.contains("9 Nov"))
  }

  @Test fun `document revision and expiry gate typed mapping`() {
    val b = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, now))
    val d = WeatherSummaryDocument("context", WeatherSummaryPolicy.revision(b), "en", UnitPreferences.METRIC,
      listOf(WeatherSummaryFact("rain", "Unknown")), listOf("rain"), now + 1000)
    assertTrue(WeatherGlanceSummary.matches(d, b, now))
    assertFalse(WeatherGlanceSummary.matches(d, b, now + 1000))
    assertFalse(WeatherGlanceSummary.matches(d, b.copy(revision = "new"), now))
  }
}
