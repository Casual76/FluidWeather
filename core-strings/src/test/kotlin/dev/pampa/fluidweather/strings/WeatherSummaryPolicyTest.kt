package dev.pampa.fluidweather.strings

import dev.pampa.fluidweather.core.model.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class WeatherSummaryPolicyTest {
  private val now = 10_000_000L
  private fun evidence() = WeatherEvidence("model", WeatherEvidenceType.MODEL,
    downloadedAtMillis = now, sourceTimestampMillis = now, precipitationState = PrecipitationState.DRY)
  private fun brief() = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.DRY, now, evidence = listOf(evidence())))

  @Test fun `null unknown and expired evidence never authorize dry`() {
    assertFalse(WeatherSummaryPolicy.rainUsable(null, now))
    assertTrue(WeatherSummaryPolicy.rainUsable(brief(), now))
    assertFalse(WeatherSummaryPolicy.rainUsable(brief(), now + 60_000))
    val old = evidence().copy(sourceTimestampMillis = now - 1_200_000)
    assertFalse(WeatherSummaryPolicy.rainUsable(brief().copy(precipitation = brief().precipitation.copy(evidence = listOf(old))), now))
    assertFalse(WeatherSummaryPolicy.usable(evidence().copy(downloadedAtMillis = null), now))
  }
  @Test fun `source cutoffs precede minute refresh and current has separate age limit`() {
    val e = evidence().copy(downloadedAtMillis = now - 3_600_000 + 5_000)
    assertEquals(now + 5_000, WeatherSummaryPolicy.cutoff(e, recent = true))
    assertFalse(WeatherSummaryPolicy.usable(e, now + 5_000, recent = true))
    assertTrue(WeatherSummaryPolicy.usable(evidence().copy(sourceTimestampMillis = now - 1_500_000), now, current = true))
    assertFalse(WeatherSummaryPolicy.usable(evidence().copy(sourceTimestampMillis = now - 1_500_000), now, recent = true))
  }
  @Test fun `shared qualifier and approximate ETA are used without inventing model precision`() {
    assertEquals(R.string.home_rain_model_dry, WeatherBriefFormatter.headlineRes(WeatherBriefPresentation.headline(brief())))
    val rain = brief().precipitation.copy(horizon = WeatherInterval(now, now + 3_600_000), arrivalAtMillis = now + 100_000)
    assertNull(WeatherBriefPresentation.transition(brief().copy(precipitation = rain), now))
    val radar = evidence().copy(type = WeatherEvidenceType.RADAR_ESTIMATE)
    val radarBrief = brief().copy(precipitation = rain.copy(evidence = listOf(radar)))
    assertEquals(600_000, WeatherBriefPresentation.transition(radarBrief, now)!!.let { it.endMillis - it.startMillis }.toInt())
    assertFalse(WeatherSummaryPolicy.rainUsable(radarBrief, now + 100_000))
  }
  @Test fun `data revision excludes evaluation clock and includes revised signals place and windows`() {
    val original = brief()
    assertEquals(WeatherSummaryPolicy.revision(original), WeatherSummaryPolicy.revision(original.copy(precipitation = original.precipitation.copy(evaluatedAtMillis = now + 1))))
    assertNotEquals(WeatherSummaryPolicy.revision(original), WeatherSummaryPolicy.revision(original.copy(precipitation = original.precipitation.copy(state = PrecipitationState.WET))))
    val place = Place(1, "Rome", null, 41.9, 12.5)
    assertNotEquals(WeatherSummaryPolicy.revision(original), WeatherSummaryPolicy.revision(original.copy(precipitation = original.precipitation.copy(place = place))))
    val routine = RoutineWindowAssessment(RoutineOccurrence(RoutineWindow("a", "Walk", setOf(DayOfWeek.MONDAY), LocalTime.NOON, LocalTime.of(13, 0)),
      WeatherInterval(now, now + 3_600_000), ResolvedWeatherZone(ZoneId.of("UTC"), WeatherZoneSource.PLACE)), now)
    assertNotEquals(WeatherSummaryPolicy.revision(original), WeatherSummaryPolicy.revision(original.copy(routines = listOf(routine))))
  }

  @Test fun `forecast valid times may be future while current recent and radar frames stay guarded`() {
    val forecast = evidence().copy(sourceTimestampMillis = now + 3_600_000L)
    assertTrue(WeatherSummaryPolicy.usable(forecast, now))
    assertFalse(WeatherSummaryPolicy.usable(forecast, now, recent = true))
    assertFalse(WeatherSummaryPolicy.usable(forecast, now, current = true))
    assertFalse(WeatherSummaryPolicy.usable(forecast.copy(type = WeatherEvidenceType.RADAR_ESTIMATE), now))
    assertFalse(WeatherSummaryPolicy.usable(forecast.copy(downloadedAtMillis = now + 1), now))
    assertFalse(WeatherSummaryPolicy.usable(forecast.copy(downloadedAtMillis = now - 3_600_000L), now))
  }

  @Test fun `absolute ETA revision respects domain invariants and stays clock independent`() {
    val rain = brief().precipitation.copy(horizon = WeatherInterval(now, now + 3_600_000),
      arrivalAtMillis = now + 100_000, evidence = listOf(evidence().copy(type = WeatherEvidenceType.RADAR_ESTIMATE)))
    val b = brief().copy(precipitation = rain)
    val first = WeatherSummaryPolicy.revision(b)
    val reevaluated = b.copy(precipitation = rain.copy(evaluatedAtMillis = now + 1,
      horizon = WeatherInterval(now + 1, now + 3_600_001)))
    assertEquals(first, WeatherSummaryPolicy.revision(reevaluated))
    assertNotEquals(first, WeatherSummaryPolicy.revision(b.copy(precipitation = rain.copy(arrivalAtMillis = now + 200_000))))
    val wet = b.copy(precipitation = rain.copy(state = PrecipitationState.WET, arrivalAtMillis = null, endAtMillis = now + 300_000))
    assertNotEquals(first, WeatherSummaryPolicy.revision(wet))
  }

  @Test fun `newly reevaluated expired transition remains uncertain instead of losing common state`() {
    val rain = brief().precipitation.copy(state = PrecipitationState.UNCERTAIN,
      evidence = listOf(evidence().copy(type = WeatherEvidenceType.RADAR_ESTIMATE, precipitationState = null)),
      limits = setOf(WeatherEvidenceLimit.TRANSITION_EXPIRED))
    val b = brief().copy(precipitation = rain)
    assertTrue(WeatherSummaryPolicy.rainUsable(b, now))
    assertEquals(BriefRainHeadline.UNCERTAIN, WeatherBriefPresentation.headline(b))
    assertNull(WeatherBriefPresentation.transition(b, now))
    assertFalse(WeatherSummaryPolicy.rainUsable(b, now + 60_000))
  }
}
