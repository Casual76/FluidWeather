package dev.pampa.fluidweather.core.sensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il fix nuovo che non arriva non deve portarsi dietro anche l'ultima posizione nota: erano sotto
 * lo stesso timeout, e un `getCurrentLocation` appeso faceva perdere un fix che il telefono aveva.
 * Il tempo e' virtuale: il test non aspetta dieci secondi veri.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationFallbackTest {

  @Test
  fun `il fix nuovo appeso ripiega sull'ultimo noto entro il totale`() = runTest {
    val fix = LocationFallback.firstFix(
      current = { delay(Long.MAX_VALUE); "nuovo" },
      lastKnown = { "ultimo" },
      totalTimeoutMillis = 10_000L,
    )

    assertEquals("ultimo", fix)
    assertEquals(8_000L, currentTime)
  }

  @Test
  fun `il fix nuovo nullo ripiega sull'ultimo noto`() = runTest {
    val fix = LocationFallback.firstFix<String>(current = { null }, lastKnown = { "ultimo" }, totalTimeoutMillis = 5_000L)

    assertEquals("ultimo", fix)
  }

  @Test
  fun `un fix nuovo non chiede mai l'ultimo noto`() = runTest {
    var asked = false
    val fix = LocationFallback.firstFix(
      current = { "nuovo" },
      lastKnown = { asked = true; "ultimo" },
      totalTimeoutMillis = 5_000L,
    )

    assertEquals("nuovo", fix)
    assertFalse(asked)
  }

  @Test
  fun `se anche l'ultimo e' appeso si finisce al totale con null`() = runTest {
    val fix = LocationFallback.firstFix<String>(
      current = { delay(Long.MAX_VALUE); null },
      lastKnown = { delay(Long.MAX_VALUE); null },
      totalTimeoutMillis = 5_000L,
    )

    assertNull(fix)
    assertEquals(5_000L, currentTime)
  }

  @Test
  fun `un'eccezione del fix nuovo vale non arrivato`() = runTest {
    val fix = LocationFallback.firstFix(
      current = { error("Play Services ha cambiato idea") },
      lastKnown = { "ultimo" },
      totalTimeoutMillis = 5_000L,
    )

    assertEquals("ultimo", fix)
  }

  @Test
  fun `la revoca del permesso non si nasconde`() = runTest {
    assertThrows(SecurityException::class.java) {
      kotlinx.coroutines.runBlocking {
        LocationFallback.firstFix<String>(
          current = { throw SecurityException("permesso revocato") },
          lastKnown = { "ultimo" },
          totalTimeoutMillis = 5_000L,
        )
      }
    }
  }

  @Test
  fun `un totale piccolo non sfora`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val fix = LocationFallback.firstFix<String>(
      current = { gate.await(); null },
      lastKnown = { delay(Long.MAX_VALUE); null },
      totalTimeoutMillis = 1_000L,
    )

    assertNull(fix)
    assertEquals(1_000L, currentTime)
  }
}
