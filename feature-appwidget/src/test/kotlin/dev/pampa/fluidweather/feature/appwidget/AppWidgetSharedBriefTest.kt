package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.CachedWeatherBriefSource
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.strings.WidgetSharedRainFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class AppWidgetSharedBriefTest {
  private val now = 1_800_000_000_000L
  private val point = GeoPoint(43.8, 11.2)
  private val place = Place(12, "Firenze", null, point.latitude, point.longitude, "Europe/Rome")
  private fun evidence(type: WeatherEvidenceType, state: PrecipitationState? = null) = WeatherEvidence(
    sourceId = type.name, type = type, downloadedAtMillis = now - 60_000,
    sourceTimestampMillis = now - 120_000, precipitationState = state)
  private fun brief(state: PrecipitationState, type: WeatherEvidenceType = WeatherEvidenceType.MODEL,
    limits: Set<WeatherEvidenceLimit> = emptySet()) = WeatherBrief(LocalPrecipitationAssessment(
      state, now, place, point, listOf(evidence(type, state)),
      horizon = WeatherInterval(now, now + 600_000), limits = limits))
  private fun model(brief: WeatherBrief?, at: Long = now) = AppWidgetModelBuilder.of(
    null, place.name, null, at, AppWidgetTier.LARGE, ZoneId.of("Europe/Rome"), brief, false)

  @Test fun `shared known states and qualifications survive widget presentation`() {
    val cases = listOf(
      brief(PrecipitationState.WET), brief(PrecipitationState.DRY),
      brief(PrecipitationState.WET, WeatherEvidenceType.RADAR_ESTIMATE),
      brief(PrecipitationState.DRY, WeatherEvidenceType.RADAR_ESTIMATE),
      brief(PrecipitationState.UNCERTAIN),
      brief(PrecipitationState.UNCERTAIN, limits = setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS)),
      WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, now, place, point)))
    assertEquals(listOf(BriefRainHeadline.MODEL_WET, BriefRainHeadline.MODEL_DRY,
      BriefRainHeadline.RADAR_WET, BriefRainHeadline.RADAR_NO_ECHO,
      BriefRainHeadline.UNCERTAIN, BriefRainHeadline.CONFLICT, BriefRainHeadline.UNAVAILABLE),
      cases.map { model(it).headline })
    cases.forEach { assertEquals(WeatherBriefPresentation.headline(it), model(it).headline) }
    assertEquals(BriefRainHeadline.UNAVAILABLE, model(null).headline)
  }

  @Test fun `forecast-only cache remains useful without inventing a current observation or dry verdict`() {
    val snapshot = WeatherSnapshot(WeatherSnapshot.keyFor(place.id), point.latitude, point.longitude,
      now, emptyList(), FusedForecast(listOf(FusedHour(now + 60_000,
        mapOf(FusionVariables.TEMPERATURE to FusedValue(35.0, emptyList())), WeatherKind.RAIN)), emptyMap()), null)
    val m = AppWidgetModelBuilder.of(snapshot, place.name, null, now, AppWidgetTier.LARGE)
    assertEquals(35.0, m.temperatureC!!, 0.0)
    assertEquals(WeatherKind.RAIN, m.kind)
    assertTrue(m.usesHourlyForecast)
    assertNull(m.brief?.current)
    assertEquals(35.0, m.hours.single().temperatureC!!, 0.0)
    assertEquals(BriefRainHeadline.UNAVAILABLE, m.headline)
    assertNull(m.rainProbabilityPercent)
  }

  @Test fun `actual current is used with source freshness instead of download bundle age`() {
    val b = brief(PrecipitationState.WET).copy(current = CurrentWeatherSummary(
      WeatherKind.RAIN, 18.0, listOf(evidence(WeatherEvidenceType.MODEL))))
    assertEquals(18.0, model(b).temperatureC!!, 0.0)
    assertNull(model(b, now + 3_600_000).temperatureC)
    assertEquals(now - 120_000, model(b).brief!!.current!!.evidence.single().sourceTimestampMillis)
  }

  @Test fun `uncertain atmosphere does not paint rain or snow from current model`() {
    val b = brief(PrecipitationState.UNCERTAIN).copy(current = CurrentWeatherSummary(
      WeatherKind.SNOW, 1.0, listOf(evidence(WeatherEvidenceType.MODEL))))
    assertEquals(WeatherKind.SNOW, model(b).kind)
    assertEquals(WeatherKind.CLOUDY, model(b).skyKind)
    assertEquals(WeatherKind.RAIN, model(brief(PrecipitationState.WET)).skyKind)
  }

  @Test fun `passed transition and expired assessment do not remain future facing`() {
    val b = brief(PrecipitationState.DRY, WeatherEvidenceType.RADAR_ESTIMATE)
    val eta = b.copy(precipitation = b.precipitation.copy(arrivalAtMillis = now + 30_000))
    assertNotNull(WeatherBriefPresentation.transition(eta, now))
    assertEquals(BriefRainHeadline.UNAVAILABLE, model(eta, now + 30_000).headline)
    val reevaluated = eta.copy(precipitation = eta.precipitation.copy(state = PrecipitationState.UNCERTAIN,
      evaluatedAtMillis = now + 30_000, arrivalAtMillis = null,
      evidence = eta.precipitation.evidence.map { it.copy(precipitationState = null) },
      limits = setOf(WeatherEvidenceLimit.TRANSITION_EXPIRED)))
    assertEquals(WeatherBriefPresentation.headline(reevaluated), model(reevaluated, now + 30_000).headline)
    assertEquals(BriefRainHeadline.UNCERTAIN, model(reevaluated, now + 30_000).headline)
    assertEquals(BriefRainHeadline.UNAVAILABLE, model(b, now + 60_000).headline)
  }

  @Test fun `wrong key and foreign coordinates cannot be relabelled as saved place`() {
    val snap = WeatherSnapshot(WeatherSnapshot.GPS_KEY, point.latitude, point.longitude, now,
      emptyList(), FusedForecast(emptyList(), emptyMap()), null)
    assertFalse(widgetSnapshotMatches(place, snap))
    assertFalse(widgetSnapshotMatches(place, snap.copy(placeKey = WeatherSnapshot.keyFor(place.id), latitude = 0.0)))
    assertTrue(widgetSnapshotMatches(place, snap.copy(placeKey = WeatherSnapshot.keyFor(place.id))))
  }

  @Test fun `suspended cache read drops old place and retries actual identity`() = runTest {
    var input = AppWidgetPlace(place, null)
    val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
    val requests = mutableListOf<Place>()
    val source = object : CachedWeatherBriefSource {
      override val cachedInputUpdates = MutableStateFlow(0L)
      override suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief {
        requests += place
        if (requests.size == 1) { started.complete(Unit); resume.await() }
        return brief(PrecipitationState.WET).let { it.copy(precipitation = it.precipitation.copy(place = place, coordinates = coordinates)) }
      }
    }
    val read = async { readAppWidgetBrief(source) { input } }
    started.await()
    input = AppWidgetPlace(place.copy(id = 13, name = "Lucca", latitude = 43.84, longitude = 10.5), null)
    resume.complete(Unit)
    val result = read.await()!!
    assertEquals(listOf(place, input.place), requests)
    assertEquals(input.place, result.second.precipitation.place)
    assertEquals(GeoPoint(43.84, 10.5), result.second.precipitation.coordinates)
  }

  @Test fun `zero input token still reads and token change during read retries`() = runTest {
    var calls = 0
    val source = object : CachedWeatherBriefSource {
      override val cachedInputUpdates = MutableStateFlow(0L)
      override suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief {
        calls++
        if (calls == 1) cachedInputUpdates.value++
        return brief(if (calls == 1) PrecipitationState.WET else PrecipitationState.DRY)
      }
    }
    val result = readAppWidgetBrief(source) { AppWidgetPlace(place, null) }!!
    assertEquals(2, calls)
    assertEquals(PrecipitationState.DRY, result.second.precipitation.state)
  }

  @Test fun `clear during suspended read drops result and cancellation propagates`() = runTest {
    var input: AppWidgetPlace? = AppWidgetPlace(place, null)
    val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
    val source = object : CachedWeatherBriefSource {
      override val cachedInputUpdates = MutableStateFlow(0L)
      override suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief {
        started.complete(Unit); resume.await()
        return brief(PrecipitationState.WET)
      }
    }
    val read = async { readAppWidgetBrief(source) { input } }
    started.await(); input = null; resume.complete(Unit)
    assertNull(read.await())
    try {
      widgetLocalRead<Unit> { throw kotlinx.coroutines.CancellationException("cancelled") }
      fail("cancellation swallowed")
    } catch (_: kotlinx.coroutines.CancellationException) { }
  }
  @Test fun `same round timestamp content changed during suspended read is reread`() = runTest {
    val old = WeatherSnapshot(WeatherSnapshot.keyFor(place.id), point.latitude, point.longitude,
      now, emptyList(), FusedForecast(emptyList(), emptyMap()), null)
    var input = AppWidgetPlace(place, old)
    var calls = 0
    val source = object : CachedWeatherBriefSource {
      override val cachedInputUpdates = MutableStateFlow(0L)
      override suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief {
        calls++
        if (calls == 1) input = input.copy(snapshot = old.copy(latitude = 43.800001))
        return brief(PrecipitationState.WET).let { it.copy(precipitation = it.precipitation.copy(coordinates = coordinates)) }
      }
    }
    val read = readAppWidgetBrief(source) { input }!!
    assertEquals(2, calls)
    assertEquals(old.fetchedAtMillis, read.first.snapshot!!.fetchedAtMillis)
    assertEquals(GeoPoint(43.800001, 11.2), read.second.precipitation.coordinates)
  }

  @Test fun `local cache read failure keeps qualified legacy forecast but rain unavailable`() = runTest {
    val snapshot = WeatherSnapshot(WeatherSnapshot.keyFor(place.id), point.latitude, point.longitude,
      now, emptyList(), FusedForecast(emptyList(), emptyMap()), null)
    val source = object : CachedWeatherBriefSource {
      override val cachedInputUpdates = MutableStateFlow(0L)
      override suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief =
        throw java.io.IOException("unreadable cache")
    }
    val read = readAppWidgetBrief(source) { AppWidgetPlace(place, snapshot) }!!
    assertEquals(snapshot, read.first.snapshot)
    assertEquals(PrecipitationState.UNAVAILABLE, read.second.precipitation.state)
    assertNull(read.second.current)
  }

}
