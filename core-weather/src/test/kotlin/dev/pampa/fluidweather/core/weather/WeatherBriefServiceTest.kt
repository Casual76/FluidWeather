package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.radar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherBriefServiceTest {
  private val f = AssessmentFixture()

  @Test fun `ten concurrent consumers share one load and cancelled waiter is isolated`() = runTest {
    var calls = 0
    val release = CompletableDeferred<Unit>()
    val service = WeatherBriefService(backgroundScope, { _, _ -> calls++; release.await(); f.radar(30) }, { f.noon })
    val waiters = List(10) { async { service.brief(f.place, f.point, f.snapshot()) } }
    runCurrent()
    assertEquals(1, calls)
    waiters.first().cancelAndJoin()
    release.complete(Unit)
    runCurrent()
    waiters.drop(1).forEach { assertEquals(PrecipitationState.WET, it.await().precipitation.state) }
    assertEquals(1, calls)
  }

  @Test fun `cache stores inputs and recomputes passed transition and twenty minute cutoff`() = runTest {
    var now = f.noon + 9 * 60_000
    var calls = 0
    val service = WeatherBriefService(backgroundScope, { _, _ -> calls++; f.radar(0, Eta(10, 30, null, 60)) }, { now })
    val first = async { service.brief(f.place, f.point, null) }; runCurrent()
    assertNotNull(first.await().precipitation.arrivalAtMillis)
    now += 60_000
    val passed = service.brief(f.place, f.point, null)
    assertEquals(1, calls)
    assertNull(passed.precipitation.arrivalAtMillis)
    assertEquals(PrecipitationState.UNCERTAIN, passed.precipitation.state)
    // Reload the same source frame just before cutoff, then consume that input beyond cutoff.
    now = f.noon + 1_190_000
    val before = async { service.brief(f.place, f.point, null) }; runCurrent(); before.await()
    assertEquals(2, calls)
    now = f.noon + 1_200_001
    assertEquals(PrecipitationState.UNAVAILABLE, service.brief(f.place, f.point, null).precipitation.state)
    assertEquals(2, calls)
    assertEquals(first.await().revision, passed.revision)
  }

  @Test fun `same fetch time different content and different location are never conflated in flight`() = runTest {
    var calls = 0
    val gate = CompletableDeferred<Unit>()
    val service = WeatherBriefService(backgroundScope, { _, _ -> calls++; gate.await(); RadarSample.Unavailable("offline") }, { f.noon })
    val wet = async { service.brief(f.place, f.point, f.snapshot(1.0)) }
    val dry = async { service.brief(f.place, f.point, f.snapshot(0.0)) }
    val elsewhere = async { service.brief(f.place.copy(id = 2), GeoPoint(45.0, 12.0), f.snapshot()) }
    runCurrent(); assertEquals(3, calls)
    gate.complete(Unit); runCurrent()
    assertEquals(PrecipitationState.WET, wet.await().precipitation.state)
    assertEquals(PrecipitationState.DRY, dry.await().precipitation.state)
    assertEquals(PrecipitationState.UNAVAILABLE, elsewhere.await().precipitation.state)
    assertNotEquals(wet.await().revision, dry.await().revision)
  }

  @Test fun `wipe cancels in flight and late uncooperative result cannot repopulate cache`() = runTest {
    var calls = 0
    val gate = CompletableDeferred<Unit>()
    val service = WeatherBriefService(backgroundScope, { _, _ ->
      calls++
      if (calls == 1) withContext(NonCancellable) { gate.await() }
      f.radar(30)
    }, { f.noon })
    val old = async { service.brief(f.place, f.point, f.snapshot()) }
    runCurrent(); service.clear(); gate.complete(Unit); runCurrent()
    try { old.await(); fail("pre-wipe request returned") } catch (_: CancellationException) { }
    val next = async { service.brief(f.place, f.point, f.snapshot()) }; runCurrent()
    assertEquals(PrecipitationState.WET, next.await().precipitation.state)
    assertEquals(2, calls)
  }

  @Test fun `bounded timeout falls back to model and failure is retried`() = runTest {
    var calls = 0
    val service = WeatherBriefService(backgroundScope, { _, _ -> calls++; delay(10_000); f.radar(30) },
      { f.noon }, loadTimeoutMillis = 100)
    val first = async { service.brief(f.place, f.point, f.snapshot()) }
    runCurrent(); advanceTimeBy(101); runCurrent()
    assertEquals(PrecipitationState.WET, first.await().precipitation.state)
    assertTrue(WeatherEvidenceLimit.MODEL_ONLY in first.await().precipitation.limits)
    val next = async { service.brief(f.place, f.point, f.snapshot()) }
    runCurrent(); advanceTimeBy(101); runCurrent(); next.await()
    assertEquals(2, calls)
  }

  @Test fun `consumer deadline survives temporarily noncancellable loader`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val service = WeatherBriefService(backgroundScope, { _, _ ->
      withContext(NonCancellable) { gate.await() }; f.radar(30)
    }, { f.noon }, loadTimeoutMillis = 100)
    val result = async { service.brief(f.place, f.point, f.snapshot()) }
    runCurrent(); advanceTimeBy(101); runCurrent()
    assertTrue(result.isCompleted)
    assertEquals(PrecipitationState.WET, result.await().precipitation.state)
    gate.complete(Unit); runCurrent()
    service.clear()
  }

  @Test fun `no coordinates avoids load and cache is bounded by eviction`() = runTest {
    var calls = 0
    val service = WeatherBriefService(backgroundScope, { _, _ -> calls++; f.radar(30) }, { f.noon }, maxEntries = 2)
    assertEquals(PrecipitationState.UNAVAILABLE, service.brief(Place.gps(), null, null).precipitation.state)
    assertEquals(0, calls)
    for (id in 1L..3L) {
      val result = async { service.brief(f.place.copy(id = id), f.point, null) }
      runCurrent(); result.await()
    }
    val again = async { service.brief(f.place, f.point, null) }; runCurrent(); again.await()
    assertEquals(4, calls)
  }

  @Test fun `loader cancellation propagates without poisoning retries`() = runTest {
    var calls = 0
    val service = WeatherBriefService(backgroundScope, { _, _ ->
      if (++calls == 1) throw CancellationException("upstream cancelled")
      f.radar(30)
    }, { f.noon })
    val first = async { service.brief(f.place, f.point, null) }; runCurrent()
    try { first.await(); fail("cancellation swallowed") } catch (_: CancellationException) { }
    val retry = async { service.brief(f.place, f.point, null) }; runCurrent()
    assertEquals(PrecipitationState.WET, retry.await().precipitation.state)
    assertEquals(2, calls)
  }
}
