package dev.pampa.fluidweather.core.sensor

import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SamplingRecoveryTest {
  @Test fun `zero samples retries without successful pass callback`() = runTest {
    var completed = false
    val result = runPass {
      scheduledSensorPass(true, { 0 }, { completed = true })
    }
    assertEquals(ListenableWorker.Result.retry(), result)
    assertFalse(completed)
  }

  @Test fun `unsupported hardware succeeds without collecting or notifying`() = runTest {
    var weatherUpdates = 0
    val result = runPass {
      scheduledSensorPass(false, { error("must not collect") }, { error("must not notify") }, { weatherUpdates++ })
    }
    assertEquals(ListenableWorker.Result.success(), result)
    assertEquals(1, weatherUpdates)
  }

  @Test fun `failed sensor keeps weather updates but cancellation owns both paths`() = runTest {
    var weatherUpdates = 0
    var successfulSamples = 0
    assertEquals(ListenableWorker.Result.retry(), runPass {
      scheduledSensorPass(true, { 0 }, { successfulSamples++ }, { weatherUpdates++ })
    })
    assertEquals(0, successfulSamples)
    assertEquals(1, weatherUpdates)
    try {
      scheduledSensorPass(true, { throw CancellationException("wipe") },
        { successfulSamples++ }, { weatherUpdates++ })
      fail("cancellation must propagate")
    } catch (_: CancellationException) { }
    assertEquals(1, weatherUpdates)
  }

  @Test fun `stored samples complete once`() = runTest {
    var completed = 0
    val result = runPass { scheduledSensorPass(true, { 3 }, { completed++ }) }
    assertEquals(ListenableWorker.Result.success(), result)
    assertEquals(1, completed)
  }

  @Test fun `failed sensor attempt retries but cancellation never becomes retry`() = runTest {
    assertEquals(ListenableWorker.Result.retry(), runPass { throw SensorRegistrationException() })
    val cancellation = CancellationException("owner stopped")
    try {
      runPass { throw cancellation }
      fail("cancellation must propagate")
    } catch (failure: CancellationException) { assertSame(cancellation, failure) }
  }

  @Test fun `position failure is optional and position identity is preserved`() = runTest {
    assertNull(optionalContext<LocationSnapshot> { error("GPS unavailable") })
    val fix = LocationSnapshot(45.0, 9.0, null, fixedAtMillis = 1234L)
    assertSame(fix, optionalContext { fix })
    val cancellation = CancellationException("wipe")
    try {
      optionalContext<LocationSnapshot> { throw cancellation }
      fail("cancellation must propagate")
    } catch (failure: CancellationException) { assertSame(cancellation, failure) }
  }

  @Test fun `collection failure cleans surveillance and permits fresh start`() = runTest {
    val loop = SurveillanceLoop(this)
    var attempts = 0
    var cleanups = 0
    loop.start({ cleanups++ }) { attempts++; error("collection failed") }
    runCurrent()
    assertEquals(1, cleanups)
    loop.start({ cleanups++ }) { attempts++ }
    runCurrent()
    assertEquals(2, attempts)
    assertEquals(2, cleanups)
  }

  @Test fun `surveillance prevents duplicate collectors and cleans on owner cancellation`() = runTest {
    var attempts = 0
    var cleanups = 0
    val owner = launch {
      val loop = SurveillanceLoop(this)
      loop.start({ cleanups++ }) { attempts++; awaitCancellation() }
      loop.start({ cleanups++ }) { attempts++; awaitCancellation() }
      awaitCancellation()
    }
    runCurrent()
    assertEquals(1, attempts)
    owner.cancelAndJoin()
    assertEquals(1, cleanups)
  }
}
