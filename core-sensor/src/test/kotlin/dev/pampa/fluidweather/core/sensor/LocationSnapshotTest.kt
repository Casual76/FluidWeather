package dev.pampa.fluidweather.core.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Fix di adesso" e' la condizione con cui la home, l'assistente e la taratura iscrivono un giro del
 * posto del telefono alla classifica pioggia. L'ultima posizione nota che `snapshot()` restituisce
 * quando il fix nuovo non arriva puo' avere ore: le sue coordinate servono a mostrare il meteo, non
 * a dire "il barometro era qui".
 */
class LocationSnapshotTest {

  private val now = 1_788_220_800_000L
  private val minute = 60_000L

  private fun fixAt(fixedAt: Long?) = LocationSnapshot(43.83, 11.2, null, fixedAt)

  @Test
  fun `un fix di qualche minuto e' fresco`() {
    assertTrue(fixAt(now).isFreshAt(now))
    assertTrue(fixAt(now - 9 * minute).isFreshAt(now))
    assertTrue(fixAt(now - LocationSnapshot.FRESH_MAX_AGE_MILLIS).isFreshAt(now))
  }

  @Test
  fun `l'ultima posizione nota di ore fa non e' fresca`() {
    assertFalse(fixAt(now - LocationSnapshot.FRESH_MAX_AGE_MILLIS - 1).isFreshAt(now))
    assertFalse(fixAt(now - 2 * 60 * minute).isFreshAt(now))
    assertFalse(fixAt(now - 3 * 24 * 60 * minute).isFreshAt(now))
  }

  @Test
  fun `un fix dal futuro vale solo entro la tolleranza dell'orologio`() {
    assertTrue(fixAt(now + LocationSnapshot.CLOCK_SKEW_MILLIS).isFreshAt(now))
    assertFalse(fixAt(now + LocationSnapshot.CLOCK_SKEW_MILLIS + 1).isFreshAt(now))
  }

  @Test
  fun `senza istante dichiarato il fix appena chiesto vale come fresco`() {
    assertTrue(fixAt(null).isFreshAt(now))
  }
}
