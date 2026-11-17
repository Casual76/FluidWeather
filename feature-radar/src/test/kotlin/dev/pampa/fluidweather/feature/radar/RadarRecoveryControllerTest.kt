package dev.pampa.fluidweather.feature.radar

import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadarRecoveryControllerTest {
  private val start = 1_800_000_000_000L
  private fun frames(at: Long) = RadarFrames("https://example.test", at,
    listOf(RadarFrame(at - 600_000L, "/earlier"), RadarFrame(at, "/$at")), emptyList())

  @Test fun failedFirstReadRecoversWithoutRecreatingScreen() = runTest {
    var calls = 0
    val fresh = frames(start + 60_000L)
    val controller = RadarRecoveryController({ if (++calls == 1) null else fresh },
      { start + testScheduler.currentTime })
    val job = backgroundScope.launch { controller.observe() }
    runCurrent()
    assertTrue(controller.state.value.framesFailed)
    assertNull(controller.state.value.frames)
    advanceTimeBy(60_001L); runCurrent()
    assertEquals(2, calls)
    assertSame(fresh, controller.state.value.frames)
    assertFalse(controller.state.value.framesFailed)
    job.cancelAndJoin()
  }

  @Test fun backgroundStopsRequestsAndResumeImmediatelyReplacesOldCatalogue() = runTest {
    var calls = 0
    val controller = RadarRecoveryController({ calls++; frames(start + testScheduler.currentTime) },
      { start + testScheduler.currentTime })
    var job = backgroundScope.launch { controller.observe() }
    runCurrent(); job.cancelAndJoin()
    advanceTimeBy(1_800_000L); runCurrent()
    assertEquals(1, calls)
    job = backgroundScope.launch { controller.observe() }
    runCurrent()
    assertEquals(2, calls)
    assertEquals(start + 1_800_000L, controller.state.value.latestFrameMillis)
    assertFalse(controller.state.value.stale)
    job.cancelAndJoin()
  }

  @Test fun failedRefreshRetainsDatedFramesAndAgesThemInsteadOfRebasingNow() = runTest {
    var calls = 0
    val original = frames(start)
    val controller = RadarRecoveryController({ if (++calls == 1) original else null },
      { start + testScheduler.currentTime })
    val job = backgroundScope.launch { controller.observe() }
    runCurrent(); advanceTimeBy(1_260_001L); runCurrent()
    val result = controller.state.value
    assertSame(original, result.frames)
    assertTrue(result.framesFailed)
    assertTrue(result.stale)
    assertFalse(RadarTimeline.offset(result.latestFrameMillis!!, result.checkedAtMillis).isNow)
    assertEquals(start, result.latestFrameMillis)
    job.cancelAndJoin()
  }

  @Test fun manualRetryUsesExistingCollectorAndRecoversMapWithoutDroppingFrames() = runTest {
    var calls = 0
    val original = frames(start)
    val controller = RadarRecoveryController({ calls++; original }, { start })
    val job = backgroundScope.launch { controller.observe() }
    runCurrent(); controller.mapFailed()
    assertTrue(controller.state.value.mapFailed)
    controller.retry(); runCurrent()
    assertEquals(2, calls)
    assertFalse(controller.state.value.mapFailed)
    assertEquals(1, controller.state.value.mapAttempt)
    assertSame(original, controller.state.value.frames)
    job.cancelAndJoin()
  }

  @Test fun resumeAfterMapFailureRearmsMapExactlyOnce() = runTest {
    val controller = RadarRecoveryController({ frames(start) }, { start })
    var job = backgroundScope.launch { controller.observe() }
    runCurrent(); job.cancelAndJoin()
    controller.mapFailed()
    job = backgroundScope.launch { controller.observe() }
    runCurrent()
    assertFalse(controller.state.value.mapFailed)
    assertEquals(1, controller.state.value.mapAttempt)
    controller.retry(); runCurrent()
    assertEquals(1, controller.state.value.mapAttempt)
    job.cancelAndJoin()
  }

  @Test fun cancellationDoesNotPublishLateFramesOrBecomeADataFailure() = runTest {
    val entered = CompletableDeferred<Unit>()
    val controller = RadarRecoveryController({ entered.complete(Unit); awaitCancellation() }, { start })
    val job = backgroundScope.launch { controller.observe() }
    entered.await(); assertTrue(controller.state.value.loading)
    job.cancelAndJoin()
    assertNull(controller.state.value.frames)
    assertFalse(controller.state.value.loading)
    assertFalse(controller.state.value.framesFailed)
  }
}
