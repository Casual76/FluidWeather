package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.radar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CachedWeatherBriefSourceTest {
  @get:Rule val temporary = TemporaryFolder()
  private val f = AssessmentFixture()

  @Test(timeout = 10_000) fun `cache only recreation rechecks ETA expiry cutoff and conflict without loading`() = runBlocking {
    var now = f.noon + 9 * 60_000
    val dir = temporary.newFolder(); val guard = DataWipeGuard()
    val cache = RadarAssessmentCache(dir, guard) { now }
    val radar = f.radar(0, Eta(10, 30, null, 60))
    cache.put(f.point, radar, cache.epoch())
    val source: CachedWeatherBriefSource = WeatherBriefService(this,
      { _, _ -> error("cache-only consumer invoked network") }, { now },
      radarCache = RadarAssessmentCache(dir, guard) { now }, wipeGuard = guard)
    assertEquals(f.noon + 600_000, source.cachedBrief(f.place, f.point, null).precipitation.arrivalAtMillis)
    val conflict = source.cachedBrief(f.place, f.point, f.snapshot()).precipitation
    assertEquals(PrecipitationState.UNCERTAIN, conflict.state)
    assertTrue(WeatherEvidenceLimit.CONFLICTING_SIGNALS in conflict.limits)
    now = f.noon + 600_000
    val expired = source.cachedBrief(f.place, f.point, null).precipitation
    assertEquals(PrecipitationState.UNCERTAIN, expired.state)
    assertTrue(WeatherEvidenceLimit.TRANSITION_EXPIRED in expired.limits)
    now = f.noon + 1_200_001
    assertEquals(PrecipitationState.UNAVAILABLE, source.cachedBrief(f.place, f.point, null).precipitation.state)
    assertEquals(PrecipitationState.UNAVAILABLE, source.cachedBrief(f.place, GeoPoint(44.00000000001, 11.0), null).precipitation.state)
    assertEquals(PrecipitationState.UNAVAILABLE, source.cachedBrief(f.place, null, null).precipitation.state)
    assertEquals(radar, cache.read(f.point)) // evaluation never rewrites source times.
  }

  @Test(timeout = 10_000) fun `nowcast and brief share pending loader and cancellation leaves remaining consumer alive`() = runBlocking {
    val guard = DataWipeGuard(); val cache = RadarAssessmentCache(temporary.newFolder(), guard) { f.noon }
    var calls = 0; val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
    val service = WeatherBriefService(this, { _, _ -> calls++; entered.complete(Unit); release.await(); f.radar(30) },
      { f.noon }, radarCache = cache, wipeGuard = guard)
    val nowcast = async { service.radarSample(44.0, 11.0) }
    entered.await()
    val brief = async { service.brief(f.place, f.point, null) }
    yield(); assertEquals(1, calls)
    nowcast.cancelAndJoin(); release.complete(Unit)
    assertEquals(PrecipitationState.WET, brief.await().precipitation.state)
    assertEquals(f.radar(30), cache.read(f.point))
    assertEquals(PrecipitationState.WET, service.cachedBrief(f.place, f.point, null).precipitation.state)
  }

  @Test(timeout = 10_000) fun `noncooperative loads cannot publish after service clear or shared wipe`() = runBlocking {
    for (sharedWipe in listOf(false, true)) {
      val guard = DataWipeGuard(); val cache = RadarAssessmentCache(temporary.newFolder(), guard) { f.noon }
      val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); var calls = 0
      val service = WeatherBriefService(this, { _, _ ->
        if (++calls == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        f.radar(30)
      }, { f.noon }, radarCache = cache, wipeGuard = guard)
      val old = async { service.radarSample(44.0, 11.0) }; entered.await()
      if (sharedWipe) guard.wipe { service.clear() } else service.clear()
      val token = service.cachedInputUpdates.value
      release.complete(Unit)
      try { old.await(); fail("late sample published") } catch (_: CancellationException) { }
      assertNull(cache.read(f.point))
      assertEquals(token, service.cachedInputUpdates.value)
      assertEquals(PrecipitationState.UNAVAILABLE, service.cachedBrief(f.place, f.point, null).precipitation.state)
      assertEquals(f.radar(30), service.radarSample(44.0, 11.0))
    }
  }

  @Test(timeout = 10_000) fun `loader can acquire wipe guard proving network executes outside it`() = runBlocking {
    val guard = DataWipeGuard()
    val service = WeatherBriefService(this, { _, _ ->
      guard.writeIfUnchanged(guard.generation()) { Unit }
      f.radar(30)
    }, { f.noon }, radarCache = RadarAssessmentCache(temporary.newFolder(), guard) { f.noon }, wipeGuard = guard)
    assertEquals(f.radar(30), service.radarSample(44.0, 11.0))
  }

  @Test(timeout = 10_000) fun `latest explicit unavailable never revives earlier successful sample`() = runBlocking {
    var now = f.noon; var calls = 0
    val cache = RadarAssessmentCache(temporary.newFolder(), DataWipeGuard()) { now }
    val service = WeatherBriefService(this, { _, _ ->
      if (++calls == 1) f.radar(0) else RadarSample.Unavailable("offline")
    }, { now }, radarCache = cache)
    service.radarSample(44.0, 11.0)
    now += 60_001
    service.radarSample(44.0, 11.0)
    assertEquals(PrecipitationState.UNAVAILABLE, service.cachedBrief(f.place, f.point, null).precipitation.state)
    assertEquals(2, calls)
  }

  @Test(timeout = 10_000) fun `older completion cannot replace newer explicit unavailability`() = runBlocking {
    val cache = RadarAssessmentCache(temporary.newFolder(), DataWipeGuard()) { f.noon }
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
    val service = WeatherBriefService(this, { _, _ ->
      if (++calls == 1) { entered.complete(Unit); release.await(); f.radar(0) }
      else RadarSample.Unavailable("latest offline")
    }, { f.noon }, radarCache = cache)
    val older = async { service.brief(f.place, f.point, f.snapshot(0.0)) }
    entered.await()
    service.brief(f.place, f.point, f.snapshot(1.0))
    release.complete(Unit); older.await()
    assertEquals(RadarSample.Unavailable("latest offline"), cache.read(f.point))
    assertEquals(PrecipitationState.UNAVAILABLE, service.cachedBrief(f.place, f.point, null).precipitation.state)
  }

  @Test(timeout = 10_000) fun `pure radar adapter retains numeric conversion and missing sample behavior`() {
    val ok = f.radar(30) as RadarSample.Ok
    val stamped = ok.copy(reading = ok.reading.copy(sampledAtMillis = f.noon))
    assertNotNull(RadarObservations.of(stamped))
    assertNull(RadarObservations.of(RadarSample.Unavailable("missing")))
    assertNull(RadarObservations.of(ok)) // unknown sample time remains unknown.
    assertEquals((Math.pow(10.0, 3.0) / 200).let { Math.pow(it, 1 / 1.6) }, RadarObservations.millimetresPerHour(30), 0.0)
  }
}
