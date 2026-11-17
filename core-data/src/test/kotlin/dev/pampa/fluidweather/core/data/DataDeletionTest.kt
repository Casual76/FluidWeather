package dev.pampa.fluidweather.core.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DataDeletionTest {
  @Test fun `failed store does not leave later stores untouched and reports every failure`() = runBlocking {
    val calls = mutableListOf<Int>()
    val failure = runCatching {
      clearUserData(
        { calls += 1; throw IOException("first") },
        { calls += 2 },
        { calls += 3; throw IllegalStateException("third") },
      )
    }.exceptionOrNull()!!
    assertEquals(listOf(1, 2, 3), calls)
    assertEquals(listOf("first", "third"), failure.suppressed.map { it.message })
  }

  @Test fun `successful deletion completes all stores`() = runBlocking {
    val calls = mutableListOf<Int>()
    clearUserData({ calls += 1 }, { calls += 2 })
    assertEquals(listOf(1, 2), calls)
  }

  @Test fun `cancellation is not reported as an ordinary deletion failure`() = runBlocking {
    var laterStoreTouched = false
    val cancelled = CancellationException("cancelled")
    val actual = runCatching {
      clearUserData({ throw cancelled }, { laterStoreTouched = true })
    }.exceptionOrNull()
    assertSame(cancelled, actual)
    assertFalse(laterStoreTouched)
  }

  @Test fun `an incomplete wipe still invalidates old writers and permits an explicit new attempt`() = runBlocking {
    val guard = DataWipeGuard()
    val oldGeneration = guard.generation()
    runCatching { guard.wipe { clearUserData({ throw IOException("locked") }) } }
    var resurrected = false
    guard.writeIfUnchanged(oldGeneration) { resurrected = true }
    assertFalse(resurrected)
    var completed = false
    guard.wipe { clearUserData({ completed = true }) }
    assertTrue(completed)
  }
}
