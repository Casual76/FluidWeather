package dev.pampa.fluidweather.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataWipeGuardTest {

  @Test
  fun `senza cancellazioni la scrittura avviene`() = runBlocking {
    val guard = DataWipeGuard()
    val gen = guard.generation()
    assertEquals("scritto", guard.writeIfUnchanged(gen) { "scritto" })
  }

  @Test
  fun `una generazione vecchia salta la scrittura`() = runBlocking {
    val guard = DataWipeGuard()
    val gen = guard.generation()
    var cleared = false
    guard.wipe { cleared = true }

    var written = false
    val result = guard.writeIfUnchanged(gen) {
      written = true
      "no"
    }

    assertTrue(cleared)
    assertNull(result)
    assertEquals(false, written)
    // Con la generazione nuova si scrive di nuovo.
    assertEquals("si", guard.writeIfUnchanged(guard.generation()) { "si" })
  }

  @Test
  fun `una scrittura attende la cancellazione in corso e poi si accorge che e' passata`() = runBlocking {
    val guard = DataWipeGuard()
    val gen = guard.generation()
    val wiping = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val wipe = async {
      guard.wipe {
        wiping.complete(Unit)
        release.await()
      }
    }
    wiping.await()

    val write = async { guard.writeIfUnchanged(gen) { "zombie" } }
    delay(50)
    assertTrue("la scrittura non deve passare mentre si cancella", write.isActive)

    release.complete(Unit)
    wipe.await()
    assertNull(write.await())
  }

  @Test
  fun `una cancellazione attende la scrittura in corso`() = runBlocking {
    val guard = DataWipeGuard()
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val order = mutableListOf<String>()
    val write = async {
      guard.writeIfUnchanged(guard.generation()) {
        started.complete(Unit)
        release.await()
        order += "write"
      }
    }
    started.await()

    val wipe = async { guard.wipe { order += "wipe" } }
    delay(50)
    assertTrue(wipe.isActive)

    release.complete(Unit)
    write.await()
    wipe.await()
    assertEquals(listOf("write", "wipe"), order)
  }
}
