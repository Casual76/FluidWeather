package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSummaryControllerTest {
  private fun doc(key: String, until: Long = 1000) = WeatherSummaryDocument(
    key, key, "en", UnitPreferences.METRIC,
    listOf(WeatherSummaryFact("rain", "$key rain", true), WeatherSummaryFact("source", "source limitation", true),
      WeatherSummaryFact("current", "$key current"), WeatherSummaryFact("window", "$key window")),
    listOf("rain", "window"), until)

  @Test fun `open refresh resume and expiry never call AI`() = runTest {
    var calls = 0
    val c = HomeSummaryController(WeatherSummaryGateway { calls++; WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("current")) },
      backgroundScope, { testScheduler.currentTime }, { 0 }) { now, _ -> doc(if (now < 1000) "fresh" else "unknown", now + 1000) }
    assertTrue(c.state.value.render(0)!!.contains("fresh rain"))
    c.resume(); c.refresh(); c.pause(); c.resume()
    advanceTimeBy(1000); c.refresh(); runCurrent()
    assertTrue(c.state.value.render(1000)!!.contains("unknown rain"))
    assertEquals(0, calls)
  }

  @Test fun `safe selection retains mandatory source and rain facts`() = runTest {
    val c = HomeSummaryController(WeatherSummaryGateway { WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("current")) },
      backgroundScope, { 0 }, { 0 }) { _, _ -> doc("A") }
    c.resume(); c.request(true); runCurrent()
    assertEquals(HomeSummaryPhase.READY, c.state.value.phase)
    assertEquals("A rain\nsource limitation\nA current", c.state.value.render(0))
  }

  @Test fun `malformed IDs and gateway exceptions retain deterministic text without error details`() = runTest {
    for (ids in listOf(listOf("invented"), listOf("current", "current"), emptyList())) {
      val c = HomeSummaryController(WeatherSummaryGateway { WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, ids) },
        backgroundScope, { 0 }, { 0 }) { _, _ -> doc("A") }
      c.resume(); c.request(true); runCurrent()
      assertEquals(HomeSummaryPhase.FAILED, c.state.value.phase)
      assertEquals(doc("A").render(), c.state.value.render(0))
    }
    val c = HomeSummaryController(WeatherSummaryGateway { error("PRIVATE PROVIDER ERROR") }, backgroundScope, { 0 }, { 0 }) { _, _ -> doc("A") }
    c.resume(); c.request(true); runCurrent()
    assertEquals(HomeSummaryPhase.FAILED, c.state.value.phase)
    assertFalse(c.state.value.render(0)!!.contains("PRIVATE"))
  }

  @Test fun `unconfigured never calls gateway and can retry after setup`() = runTest {
    var calls = 0
    val c = HomeSummaryController(WeatherSummaryGateway { calls++; WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("current")) },
      backgroundScope, { 0 }, { 0 }) { _, _ -> doc("A") }
    c.resume(); c.request(false); runCurrent()
    assertEquals(HomeSummaryPhase.NOT_CONFIGURED, c.state.value.phase); assertEquals(0, calls)
    c.request(true); runCurrent(); assertEquals(1, calls)
  }

  @Test fun `pending context changes suppress noncooperative old responses for place locale units and windows`() = runTest {
    for (changed in listOf("place-B", "locale-it", "units-imperial", "windows-new")) {
      var key = "A"
      val c = HomeSummaryController(WeatherSummaryGateway {
        withContext(NonCancellable) { delay(100) }
        WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("current"))
      }, backgroundScope, { 0 }, { 0 }) { _, _ -> doc(key) }
      c.resume(); c.request(true); runCurrent()
      assertEquals(HomeSummaryPhase.LOADING, c.state.value.phase)
      assertEquals(doc("A").render(), c.state.value.render(0))
      key = changed; c.refresh(); advanceTimeBy(101); runCurrent()
      assertEquals(doc(changed).render(), c.state.value.render(0))
      assertEquals(HomeSummaryPhase.DEFAULT, c.state.value.phase)
    }
  }

  @Test fun `cancel close and pause cannot resurrect loading selection or failure`() = runTest {
    for (close in listOf(false, true)) {
      val c = HomeSummaryController(WeatherSummaryGateway {
        withContext(NonCancellable) { delay(100) }
        WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.FAILED)
      }, backgroundScope, { 0 }, { 0 }) { _, _ -> doc("A") }
      c.resume(); c.request(true); runCurrent()
      if (close) c.pause() else c.cancel()
      advanceTimeBy(101); runCurrent()
      assertEquals(if (close) HomeSummaryPhase.DEFAULT else HomeSummaryPhase.CANCELLED, c.state.value.phase)
      assertEquals(doc("A").render(), c.state.value.render(0))
    }
  }

  @Test fun `expiry forbids rendering and regenerates even without domain tick`() = runTest {
    var now = 0L
    val c = HomeSummaryController(WeatherSummaryGateway {
      withContext(NonCancellable) { delay(100) }
      WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("current"))
    }, backgroundScope, { now }, { 0 }) { time, _ -> doc(if (time < 50) "current" else "expired-safe", if (time < 50) 50 else 1050) }
    c.resume(); c.request(true); runCurrent(); now = 50
    assertNull(c.state.value.render(now))
    c.refresh(); advanceTimeBy(101); runCurrent()
    assertEquals(doc("expired-safe", 1050).render(), c.state.value.render(now))
    assertEquals(HomeSummaryPhase.DEFAULT, c.state.value.phase)
  }

  @Test fun `wipe barrier checks generation before completion and blocks new requests`() = runTest {
    var generation = 0L
    var calls = 0
    val c = HomeSummaryController(WeatherSummaryGateway {
      calls++; withContext(NonCancellable) { delay(100) }
      WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("current"))
    }, backgroundScope, { 0 }, { generation }) { _, wiped -> doc(if (wiped) "unknown" else "A") }
    c.resume(); c.request(true); runCurrent(); generation++
    advanceTimeBy(101); runCurrent()
    assertEquals(HomeSummaryPhase.INVALIDATED, c.state.value.phase)
    assertEquals(doc("unknown").render(), c.state.value.render(0))
    c.request(true); runCurrent(); assertEquals(1, calls)
  }

  @Test fun `mismatched result context is invalidated`() = runTest {
    val c = HomeSummaryController(WeatherSummaryGateway { WeatherSummaryResult("wrong", WeatherSummaryStatus.READY, listOf("current")) },
      backgroundScope, { 0 }, { 0 }) { _, _ -> doc("A") }
    c.resume(); c.request(true); runCurrent()
    assertEquals(HomeSummaryPhase.INVALIDATED, c.state.value.phase)
    assertEquals(doc("A").render(), c.state.value.render(0))
  }

  @Test fun `polling same context cannot extend original selection deadline`() = runTest {
    var now = 0L
    var calls = 0
    val c = HomeSummaryController(WeatherSummaryGateway {
      calls++; WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("current"))
    }, backgroundScope, { now }, { 0 }) { time, _ -> doc("A", time + 1000) }
    c.resume(); c.request(true); runCurrent()
    now = 999; c.refresh()
    assertEquals(1000L, c.state.value.document.validUntilMillis)
    assertEquals(HomeSummaryPhase.READY, c.state.value.phase)
    now = 1000
    assertNull(c.state.value.render(now))
    c.refresh()
    assertEquals(HomeSummaryPhase.DEFAULT, c.state.value.phase)
    assertEquals(doc("A", 2000).render(), c.state.value.render(now))
    assertEquals(1, calls)
  }
}
