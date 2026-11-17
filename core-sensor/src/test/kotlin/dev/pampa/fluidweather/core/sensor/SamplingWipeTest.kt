package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.data.PressureDao
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.PressureSampleEntity
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.core.model.SamplingMode
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SamplingWipeTest {
  private class Fixture {
    private val sampleTime = 1_700_000_000_000L
    val guard = DataWipeGuard()
    val archive = mutableListOf<PressureSampleEntity>()
    val motion = mutableListOf<MotionFeatures>()
    var readLocation: suspend () -> LocationSnapshot? = { null }
    var listener: ((TimedReading) -> Unit)? = null
    val engine = SamplingEngine(
      barometer = Barometer(clock = { sampleTime }) {
        object : PressureSensorInput {
          override val isAvailable = true
          override val sensorName = "test pressure"
          override fun register(onReading: (TimedReading) -> Unit): Boolean {
            listener = onReading
            return true
          }
          override fun unregister() {
            listener = null
          }
        }
      },
      snapshot = { readLocation() },
      latestActivity = { null },
      measureActivity = { MotionFeatures(0, 1.0, true, 0.0, null) },
      recordActivity = { measured, _ -> motion.add(measured) },
      repository = PressureRepository(object : PressureDao {
        override suspend fun insertAll(samples: List<PressureSampleEntity>) {
          archive.addAll(samples)
        }
        override suspend fun samplesSince(sinceMillis: Long) = error("unexpected read")
        override fun latest(limit: Int) = flowOf(emptyList<PressureSampleEntity>())
        override fun count() = flowOf(archive.size.toLong())
        override suspend fun averageHourlyPressureSince(sinceMillis: Long) = error("unexpected read")
        override suspend fun oldestTimestamp() = error("unexpected read")
        override suspend fun newestTimestamp() = error("unexpected read")
        override suspend fun samplesOfBurst(burstId: String) = error("unexpected read")
        override suspend fun deleteOlderThan(beforeMillis: Long) = error("unexpected cleanup")
      }),
      currentMode = { SamplingMode.MINIMA },
      startSurveillance = {},
      cleaningPipeline = CleaningPipeline(),
      wipeGuard = guard,
    )

    fun emit(offsetMillis: Long = 0) {
      checkNotNull(listener)(TimedReading(sampleTime + offsetMillis, 1015.0))
    }

    fun clear() {
      archive.clear()
      motion.clear()
    }
  }

  @Test fun `a reading captured before suspended GPS cannot restore pressure or motion`() = runTest {
    val f = Fixture()
    val gps = CompletableDeferred<Unit>()
    f.readLocation = {
      gps.await()
      null
    }
    val request = async { f.engine.collect(SampleSource.PERIODIC, 0) }
    runCurrent()
    f.emit()
    runCurrent()
    assertEquals(null, f.listener)
    f.guard.wipe { f.clear() }
    gps.complete(Unit)
    assertEquals(0, request.await())
    assertTrue(f.archive.isEmpty())
    assertTrue(f.motion.isEmpty())

    val next = async { f.engine.collect(SampleSource.PERIODIC, 0) }
    runCurrent()
    f.emit()
    assertEquals(1, next.await())
    assertEquals(1, f.archive.size)
    assertEquals(1, f.motion.size)
  }

  @Test fun `a queued pass invalidated before execution acquires no readings`() = runTest {
    val f = Fixture()
    val observed = f.engine.dataGeneration()
    f.readLocation = { error("stale pass tried to acquire location") }
    f.guard.wipe { f.clear() }
    f.engine.runScheduledPass(observed)
    assertEquals(null, f.listener)
    assertTrue(f.archive.isEmpty())
    assertTrue(f.motion.isEmpty())
  }

  @Test fun `a pass started while deletion is in progress cannot commit`() = runTest {
    val f = Fixture()
    val clearing = CompletableDeferred<Unit>()
    val finishWipe = CompletableDeferred<Unit>()
    val wipe = launch {
      f.guard.wipe {
        clearing.complete(Unit)
        finishWipe.await()
        f.clear()
      }
    }
    clearing.await()
    val request = async { f.engine.collect(SampleSource.PERIODIC, 0) }
    runCurrent()
    f.emit()
    runCurrent()
    assertFalse(request.isCompleted)
    finishWipe.complete(Unit)
    wipe.join()
    assertEquals(0, request.await())
    assertTrue(f.archive.isEmpty())
    assertTrue(f.motion.isEmpty())
  }

  @Test fun `a burst with a context refresh in flight cannot resume writes after deletion`() = runTest {
    val f = Fixture()
    val refreshing = CompletableDeferred<Unit>()
    val gps = CompletableDeferred<Unit>()
    var snapshots = 0
    f.readLocation = {
      if (++snapshots == 2) {
        refreshing.complete(Unit)
        gps.await()
      }
      null
    }
    val request = async { f.engine.collect(SampleSource.CALIBRATION, 5, contextRefreshSeconds = 1) }
    runCurrent()
    f.emit()
    runCurrent()
    assertEquals(1, f.archive.size)
    advanceTimeBy(1_000)
    refreshing.await()
    f.guard.wipe { f.clear() }
    gps.complete(Unit)
    f.emit(2_000)
    assertEquals(0, request.await())
    assertTrue(f.archive.isEmpty())
    assertTrue(f.motion.isEmpty())
  }

  @Test fun `caller cancellation releases the listener and leaves no samples`() = runTest {
    val f = Fixture()
    val request = async { f.engine.collect(SampleSource.PERIODIC, 0) }
    runCurrent()
    assertTrue(f.listener != null)
    request.cancelAndJoin()
    assertTrue(request.isCancelled)
    assertEquals(null, f.listener)
    assertTrue(f.archive.isEmpty())
  }
}
