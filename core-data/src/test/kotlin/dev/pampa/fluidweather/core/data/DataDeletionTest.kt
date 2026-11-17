package dev.pampa.fluidweather.core.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

  @Test fun workStartedDuringDeletionCannotRestoreOldInput() = runBlocking {
    val guard = DataWipeGuard()
    var stored: String? = "old"
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val wipe = launch(start = CoroutineStart.UNDISPATCHED) {
      guard.wipe { entered.complete(Unit); release.await(); stored = null }
    }
    entered.await()
    val during = guard.generation()
    val oldInput = stored
    val writer = async(start = CoroutineStart.UNDISPATCHED) {
      guard.writeIfUnchanged(during) { stored = oldInput }
    }
    assertFalse(writer.isCompleted)
    release.complete(Unit)
    wipe.join()
    assertNull(writer.await())
    assertNull(stored)
    guard.writeIfUnchanged(guard.generation()) { stored = "new" }
    assertEquals("new", stored)
  }

  @Test fun failedDeletionAlsoInvalidatesWorkStartedWhileItWasClearing() = runBlocking {
    val guard = DataWipeGuard()
    val release = CompletableDeferred<Unit>()
    val wipe = async(start = CoroutineStart.UNDISPATCHED) {
      runCatching { guard.wipe { release.await(); throw IOException("locked") } }
    }
    val during = guard.generation()
    var lateCommit = false
    val writer = async(start = CoroutineStart.UNDISPATCHED) {
      guard.writeIfUnchanged(during) { lateCommit = true }
    }
    release.complete(Unit)
    assertTrue(wipe.await().exceptionOrNull() is IOException)
    assertNull(writer.await())
    assertFalse(lateCommit)
  }
}
