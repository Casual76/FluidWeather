package dev.pampa.fluidweather.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DataWipeEpochTest {
  @Test fun delayedConsumerCannotRenewItsOriginAfterDeletion() = runBlocking {
    val guard = DataWipeGuard()
    var stored: String? = "private input"
    val read = CompletableDeferred<Unit>()
    val proceed = CompletableDeferred<Unit>()
    val consumer = async {
      runCatching {
        withDataWipeGeneration(guard) {
          val input = stored
          read.complete(Unit)
          proceed.await()
          withContext(Dispatchers.Default) {
            withDataWipeGeneration(guard) { writeCurrentData { stored = input } }
          }
        }
      }
    }
    read.await()
    guard.wipe { stored = null }
    proceed.complete(Unit)
    assertTrue(consumer.await().exceptionOrNull() is DataWipeInvalidatedException)
    assertNull(stored)
    withDataWipeGeneration(guard) { writeCurrentData { stored = "new input" } }
    assertEquals("new input", stored)
  }

  @Test fun queuedIntentCannotAcquireANewTokenWhenItsCoroutineStarts() = runBlocking {
    val guard = DataWipeGuard()
    val intentGeneration = guard.generation()
    guard.wipe { }
    var started = false
    val failure = runCatching {
      withDataWipeGeneration(guard, intentGeneration) { started = true }
    }.exceptionOrNull()
    assertTrue(failure is DataWipeInvalidatedException)
    assertFalse(started)
    assertEquals("fresh", withDataWipeGeneration(guard) { "fresh" })
  }
}
