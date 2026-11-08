package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.strings.TimeFormats
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class HomeRainPresentationTest {
  private val now = 1_800_000L
  private fun brief(state: PrecipitationState, types: List<WeatherEvidenceType>, limits: Set<WeatherEvidenceLimit> = emptySet(), arrival: Long? = null) = WeatherBrief(
    LocalPrecipitationAssessment(state, now, evidence = types.map { WeatherEvidence("test", it, precipitationState = state) },
      limits = limits, horizon = WeatherInterval(now, now + 3_600_000), arrivalAtMillis = arrival))

  @Test fun modelIsAlwaysQualifiedAndCannotSupplyEta() {
    for ((state, expected) in listOf(PrecipitationState.WET to RainHeadline.MODEL_WET, PrecipitationState.DRY to RainHeadline.MODEL_DRY)) {
      val b = brief(state, listOf(WeatherEvidenceType.MODEL), arrival = now + 600_000)
      assertEquals(expected, HomeRainPresentation.headline(b))
      assertNull(HomeRainPresentation.transition(b, now))
    }
  }
  @Test fun noEchoIsNotGroundDryAndConflictIsExplicit() {
    assertEquals(RainHeadline.RADAR_NO_ECHO, HomeRainPresentation.headline(brief(PrecipitationState.DRY, listOf(WeatherEvidenceType.RADAR_ESTIMATE))))
    assertEquals(RainHeadline.CONFLICT, HomeRainPresentation.headline(brief(PrecipitationState.UNCERTAIN,
      listOf(WeatherEvidenceType.MODEL, WeatherEvidenceType.RADAR_ESTIMATE), setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS))))
    assertEquals(RainHeadline.UNAVAILABLE, HomeRainPresentation.headline(null))
    assertEquals(RainHeadline.UNCERTAIN, HomeRainPresentation.headline(brief(PrecipitationState.UNCERTAIN, listOf(WeatherEvidenceType.MODEL))))
  }
  @Test fun expiredEtaCannotRemainAFuturePromise() {
    val b = brief(PrecipitationState.DRY, listOf(WeatherEvidenceType.RADAR_ESTIMATE), arrival = now + 120_000)
    assertNotNull(HomeRainPresentation.transition(b, now))
    assertNull(HomeRainPresentation.transition(b, now + 120_000))
    val window = HomeRainPresentation.transition(b, now)!!
    assertTrue(window.startMillis >= now)
    assertEquals(600_000L, window.endMillis - window.startMillis)
    val partialWindow = HomeRainPresentation.transition(b, now + 60_000)!!
    assertEquals(600_000L, partialWindow.endMillis - partialWindow.startMillis)
    assertNull(HomeRainPresentation.transition(b.copy(precipitation = b.precipitation.copy(limits = setOf(WeatherEvidenceLimit.TRANSITION_EXPIRED))), now))
  }
  @Test fun staleEvidenceCannotTurnUnavailableIntoDry() {
    val b = brief(PrecipitationState.UNAVAILABLE, emptyList(), setOf(WeatherEvidenceLimit.STALE))
      .copy(precipitation = LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, now,
        evidence = listOf(WeatherEvidence("radar", WeatherEvidenceType.RADAR_ESTIMATE,
          sourceTimestampMillis = now - 3_600_000, limits = setOf(WeatherEvidenceLimit.STALE)))))
    assertEquals(RainHeadline.UNAVAILABLE, HomeRainPresentation.headline(b))
    assertNull(HomeRainPresentation.transition(b, now))
  }
  @Test fun interpolationDoesNotTurnModelIntoMeasurement() {
    assertEquals(RainHeadline.MODEL_WET, HomeRainPresentation.headline(brief(PrecipitationState.WET,
      listOf(WeatherEvidenceType.MODEL), setOf(WeatherEvidenceLimit.POSSIBLY_INTERPOLATED, WeatherEvidenceLimit.COARSE_TIME_RESOLUTION))))
  }
  @Test fun timelineGapsDifferFromTrueZeroAndClippingDoesNotProrate() {
    val period = PrecipitationPeriod(WeatherInterval(now + 900_000, now + 1_800_000), PrecipitationState.DRY,
      amountMm = 0.0, evidence = listOf(WeatherEvidence("m", WeatherEvidenceType.MODEL)), role = PrecipitationPeriodRole.FORECAST)
    val b = brief(PrecipitationState.UNAVAILABLE, emptyList()).copy(timeline = listOf(period))
    val parts = HomeRainPresentation.timeline(b, now)
    assertEquals(3, parts.size)
    assertNull(parts.first().period)
    assertEquals(0.0, parts[1].period!!.amountMm!!, 0.0)
    assertNull(parts.last().period)
    assertNull(HomeRainPresentation.timeline(b, now + 1_000_000).first().period!!.amountMm)
    assertNull(HomeRainPresentation.timeline(b.copy(timeline = listOf(period, period)), now)[1].period)
    assertTrue(HomeRainPresentation.timeline(null, now).all { it.period == null })
  }
  @Test fun placeZoneAndLocaleControlAbsoluteTimesAndFallbackIsExplicit() {
    val zone = WeatherZones.resolve("Europe/Rome", null, ZoneId.of("America/New_York"))
    assertEquals(WeatherZoneSource.PLACE, zone.source)
    assertEquals("01:00", TimeFormats.time(0, zone.zoneId, Locale.ITALY))
    assertEquals("19:00", TimeFormats.time(0, ZoneId.of("America/New_York"), Locale.ITALY))
    assertEquals(WeatherZoneSource.DEVICE_FALLBACK, WeatherZones.resolve("invalid", null, ZoneId.of("UTC")).source)
  }
}
