package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.CalibrationOutcome
import dev.pampa.fluidweather.core.model.CalibrationRecord
import dev.pampa.fluidweather.core.model.PendingCalibrationBurst
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationReferenceSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PendingCalibrationEstimatorTest {
  private val start = 1_700_000_000_000L
  private val end = start + 599_000L
  private val readings = (0 until 600).map { second ->
    PressureSample(start + second * 1_000L, 1015.0, SampleSource.CALIBRATION,
      burstId = "offline", altitudeMeters = 0.0, latitude = 45.0, longitude = 9.0)
  }

  private inner class Fixture {
    val wipeGuard = DataWipeGuard()
    var pending: PendingCalibrationBurst? = PendingCalibrationBurst("offline", start, end)
    var saved: CalibrationRecord? = null
    var outcome: CalibrationOutcome? = CalibrationOutcome.NO_REFERENCE
    var target: CalibrationReferenceSample? = CalibrationReferenceSample(end, 1015.0, 15.0, 45.0, 9.0)
    var readCurrent: suspend () -> CalibrationRecord? = { null }
    var write: suspend (CalibrationRecord) -> Unit = { saved = it }
    var getReference: suspend () -> CalibrationReferenceSample? = { target }
    val estimator get() = PendingCalibrationEstimator(
      wipeGuard = wipeGuard,
      pending = { pending }, current = { readCurrent() }, samples = { readings },
      reference = { getReference() }, save = { write(it) }, clearPending = { pending = null },
      outcome = { outcome = it }, clock = { target?.atMillis ?: end },
    )
  }

  @Test fun `reference obtained after a wipe cannot restore an archived calibration`() = runTest {
    val f = Fixture()
    val entered = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    f.getReference = {
      entered.complete(Unit)
      released.await()
      f.target
    }
    val request = async { f.estimator.retry() }
    entered.await()
    f.wipeGuard.wipe {
      f.pending = null
      f.outcome = null
    }
    released.complete(Unit)
    assertFalse(request.await())
    assertNull(f.saved)
    assertNull(f.outcome)
    assertNull(f.pending)

    f.pending = PendingCalibrationBurst("after-wipe", start, end)
    assertTrue(f.estimator.retry())
    assertEquals(CalibrationOutcome.OK, f.outcome)
  }

  @Test fun `offline burst can recover against a contemporaneous reference`() = runTest {
    val f = Fixture()
    assertTrue(f.estimator.retry())
    assertEquals(0.0, f.saved!!.biasHpa, 0.001)
    assertEquals(600, f.saved!!.sampleCount)
    assertEquals(CalibrationOutcome.OK, f.outcome)
    assertNull(f.pending)
  }

  @Test fun `tomorrows weather is not a fifteen hPa permanent sensor bias`() = runTest {
    val f = Fixture()
    f.target = f.target!!.copy(atMillis = end + 86_400_000L, mslHpa = 1000.0)
    assertFalse(f.estimator.retry())
    assertNull(f.saved)
    assertEquals(CalibrationOutcome.REFERENCE_EXPIRED, f.outcome)
    assertNull(f.pending)
    assertEquals(600, readings.size) // Raw archive is untouched by expiration.
  }

  @Test fun `another city cannot calibrate this burst`() = runTest {
    val f = Fixture()
    f.target = f.target!!.copy(latitude = 43.0, longitude = 11.0, mslHpa = 1000.0)
    assertFalse(f.estimator.retry())
    assertNull(f.saved)
    assertNotNull(f.pending)
    assertEquals(CalibrationOutcome.NO_REFERENCE, f.outcome)
  }

  @Test fun `failed save is false and retains pending without claiming OK`() = runTest {
    val f = Fixture()
    f.write = { error("disk unavailable") }
    assertFalse(f.estimator.retry())
    assertNull(f.saved)
    assertNotNull(f.pending)
    assertEquals(CalibrationOutcome.NO_REFERENCE, f.outcome)
  }

  @Test fun `failed current read never means there is no existing calibration`() = runTest {
    val f = Fixture()
    f.readCurrent = { error("disk unavailable") }
    assertFalse(f.estimator.retry())
    assertNull(f.saved)
    assertNotNull(f.pending)
  }

  @Test fun `reference cancellation propagates without persistence`() = runTest {
    val f = Fixture()
    val cancelled = CancellationException("owner stopped")
    f.getReference = { throw cancelled }
    try {
      f.estimator.retry()
      fail("cancellation must propagate")
    } catch (failure: CancellationException) {
      assertTrue(failure === cancelled || failure.cause === cancelled)
    }
    assertNull(f.saved)
    assertNotNull(f.pending)
  }

  @Test fun `save cancellation propagates and never clears pending or marks OK`() = runTest {
    val f = Fixture()
    val cancelled = CancellationException("wipe")
    f.write = { throw cancelled }
    try {
      f.estimator.retry()
      fail("cancellation must propagate")
    } catch (failure: CancellationException) {
      assertTrue(failure === cancelled || failure.cause === cancelled)
    }
    assertEquals(CalibrationOutcome.NO_REFERENCE, f.outcome)
    assertNotNull(f.pending)
  }

  @Test fun `missing reference preserves a retryable burst`() = runTest {
    val f = Fixture()
    f.target = null
    assertFalse(f.estimator.retry())
    assertNotNull(f.pending)
    assertNull(f.saved)
  }
}
