package dev.pampa.fluidweather.core.sensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BarometerRecoveryTest {
  private class Input(
    override val isAvailable: Boolean = true,
    val accepted: Boolean = true,
    val reading: TimedReading? = null,
    val failure: Exception? = null,
  ) : PressureSensorInput {
    override val sensorName = "fake"
    var registrations = 0
    var cleanups = 0
    override fun register(onReading: (TimedReading) -> Unit): Boolean {
      registrations++
      failure?.let { throw it }
      reading?.let(onReading)
      return accepted
    }
    override fun unregister() { cleanups++ }
  }

  @Test fun `rejected registration fails immediately and cleans listener`() = runTest {
    val input = Input(accepted = false)
    try {
      Barometer { input }.single()
      fail("rejected registration must fail")
    } catch (_: SensorRegistrationException) { }
    assertEquals(0L, currentTime)
    assertEquals(1, input.registrations)
    assertEquals(1, input.cleanups)
  }

  @Test fun `throwing registration also cleans listener`() = runTest {
    val input = Input(failure = IllegalStateException("sensor failure"))
    try {
      Barometer { input }.single()
      fail("registration error must propagate")
    } catch (_: IllegalStateException) { }
    assertEquals(1, input.cleanups)
  }

  @Test fun `supported silent sensor is bounded for single and burst`() = runTest {
    val input = Input()
    val barometer = Barometer { input }
    assertNull(barometer.single())
    assertEquals(5_000L, currentTime)
    assertTrue(barometer.burst(20).toList().isEmpty())
    assertEquals(25_000L, currentTime)
    assertEquals(2, input.cleanups)
  }

  @Test fun `missing hardware stays empty without registration or timeout`() = runTest {
    val input = Input(isAvailable = false)
    val barometer = Barometer { input }
    assertFalse(barometer.isAvailable)
    assertNull(barometer.single())
    assertTrue(barometer.burst(20).toList().isEmpty())
    assertEquals(0L, currentTime)
    assertEquals(0, input.registrations)
    assertEquals(0, input.cleanups)
  }

  @Test fun `cancellation propagates and unregisters rather than timing out`() = runTest {
    val input = Input()
    var cancelled = false
    val job = launch {
      try { Barometer { input }.single() }
      catch (failure: CancellationException) { cancelled = true; throw failure }
    }
    runCurrent()
    job.cancelAndJoin()
    assertTrue(cancelled)
    assertEquals(1, input.cleanups)
    assertEquals(0L, currentTime)
  }

  @Test fun `source timestamp and pressure survive unchanged`() = runTest {
    val reading = TimedReading(1234L, 998.125)
    assertEquals(reading, Barometer { Input(reading = reading) }.single())
  }
}
