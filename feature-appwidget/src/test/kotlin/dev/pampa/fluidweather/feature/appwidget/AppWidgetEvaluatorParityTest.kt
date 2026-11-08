package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.LocalPrecipitationEvaluator
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.radar.*
import dev.pampa.fluidweather.strings.WidgetSharedRainFormatter
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Real evaluator inputs cross the public formatter and production widget model boundary. */
class AppWidgetEvaluatorParityTest {
  private val now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
  private val place = Place(1, "Test", null, 44.0, 11.0, "Europe/Rome")
  private val point = GeoPoint(44.0, 11.0)
  private val evaluator = LocalPrecipitationEvaluator()
  private fun snapshot(mm: Double) = WeatherSnapshot("place-1", 44.0, 11.0, now,
    emptyList(), FusedForecast(emptyList(), emptyMap()), ForecastBundle("open-meteo", now,
      44.0, 11.0, emptyList(), current = ProviderCurrent(now, 10.0, WeatherKind.CLOUDY,
        mm, 900_000, now, true)))
  private fun radar(frame: Long, dbz: Int) = RadarSample.Ok(RadarReading(frame, 0, 30, dbz,
    null, 30.0, 0, dbz, RadarTrend.STEADY, 0.0, null, null, emptyList(), 1.0, 0.8,
    emptyList(), geographicCoverage = GeographicCoverage.SUPPORTED,
    tilesDownloadedAtMillis = frame - 1_000, complete = true))
  private fun widget(brief: WeatherBrief, at: Long = now) = AppWidgetModelBuilder.of(
    null, place.name, null, at, AppWidgetTier.LARGE, ZoneId.of("Europe/Rome"), brief, false)

  @Test fun `discarded expired radar cannot suppress fresh model wet or dry`() {
    val frame = now - 1_200_001
    for ((mm, expected) in listOf(1.0 to BriefRainHeadline.MODEL_WET, 0.0 to BriefRainHeadline.MODEL_DRY)) {
      val b = evaluator.evaluate(place, point, snapshot(mm), radar(frame, if (mm > 0) 0 else 30), now)
      assertEquals(expected, WeatherBriefPresentation.headline(b))
      assertTrue(WeatherEvidenceLimit.STALE in b.precipitation.limits)
      val rejected = b.precipitation.evidence.single { it.type == WeatherEvidenceType.RADAR_ESTIMATE }
      assertNull(rejected.precipitationState)
      assertEquals(frame, rejected.sourceTimestampMillis)
      assertEquals(frame - 1_000, rejected.downloadedAtMillis)
      assertEquals(expected, WidgetSharedRainFormatter.headline(b, now))
      assertEquals(expected, widget(b).headline)
      assertEquals(BriefRainHeadline.UNAVAILABLE, widget(b, now + 60_000).headline)
    }
  }

  @Test fun `discarded stale or foreign model cannot suppress fresh local radar`() {
    val stale = snapshot(0.0).let { it.copy(context = it.context!!.copy(fetchedAtMillis = now - 3_600_001)) }
    val foreign = snapshot(0.0).copy(placeKey = "place-2")
    for (s in listOf(stale, foreign)) {
      val b = evaluator.evaluate(place, point, s, radar(now - 60_000, 30), now)
      assertEquals(BriefRainHeadline.RADAR_WET, WeatherBriefPresentation.headline(b))
      assertEquals(BriefRainHeadline.RADAR_WET, widget(b).headline)
    }
  }

  @Test fun `expired contributing sources still cannot authorize wet or dry`() {
    val b = evaluator.evaluate(place, point, snapshot(1.0), radar(now, 30), now)
    val old = b.copy(precipitation = b.precipitation.copy(evidence = b.precipitation.evidence.map {
      it.copy(limits = it.limits + WeatherEvidenceLimit.STALE)
    }))
    assertEquals(BriefRainHeadline.UNAVAILABLE, widget(old).headline)
    val refreshedClock = evaluator.evaluate(place, point, snapshot(1.0), radar(now, 30), now + 1_200_001)
    assertEquals(PrecipitationState.UNAVAILABLE, refreshedClock.precipitation.state)
    assertEquals(BriefRainHeadline.UNAVAILABLE, widget(refreshedClock, now + 1_200_001).headline)
  }
}
