package dev.pampa.fluidweather.nowcast.truth

import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainWindowsTest {

  private val hour = RainWindows.HOUR_MILLIS

  /** 2024-01-01T00:00:00Z: un'ora piena. */
  private val midnight = 1_704_067_200_000L

  private fun at(hours: Int, minutes: Int = 0): Long = midnight + hours * hour + minutes * 60_000L

  /** Una serie di verita' su 24 ore: asciutta tranne gli slot dati (fine dello slot -> mm). */
  private fun series(vararg wet: Pair<Long, Double>): (Long) -> Double? {
    val map = (0..24).associate { at(it) to 0.0 }.toMutableMap()
    wet.forEach { (end, mm) -> map[end] = mm }
    return { map[it] }
  }

  // ------------------------------------------------------------------------- ancora e slot

  @Test
  fun `l'ancora arrotonda per eccesso, e l'ora piena resta com'e'`() {
    assertEquals(at(10), RainWindows.anchorOf(at(10)))
    assertEquals(at(11), RainWindows.anchorOf(at(10) + 1))
    assertEquals(at(11), RainWindows.anchorOf(at(10, 20)))
    assertEquals(at(11), RainWindows.anchorOf(at(10, 59)))
    // Anche prima del 1970: niente arrotondamenti verso lo zero.
    assertEquals(0L, RainWindows.anchorOf(-1L))
    assertEquals(-hour, RainWindows.anchorOf(-hour - 1))
  }

  @Test
  fun `lo slot T copre l'ora che finisce a T, l'ultimo chiuso e' quello appena finito`() {
    assertEquals(at(11), RainWindows.slotEndOf(at(10, 20)))
    assertEquals(at(10), RainWindows.slotEndOf(at(10)))
    assertEquals(at(10), RainWindows.lastClosedSlotEnd(at(10, 20)))
    assertEquals(at(10), RainWindows.lastClosedSlotEnd(at(10)))
  }

  @Test
  fun `le finestre sommano gli slot da ancora+(a+1)h ad ancora+b h`() {
    val anchor = at(11)
    assertArrayEquals(longArrayOf(at(12)), RainWindows.slotEnds(anchor, RainWindows.ZERO_ONE))
    assertArrayEquals(longArrayOf(at(13), at(14)), RainWindows.slotEnds(anchor, RainWindows.ONE_THREE))
    assertArrayEquals(longArrayOf(at(15), at(16), at(17)), RainWindows.slotEnds(anchor, RainWindows.THREE_SIX))
  }

  @Test
  fun `la 0-1h e' la prima ora intera dopo l'emissione, mai quella gia' vista`() {
    // Piove fra le 10 e le 11: un verdetto delle 10:20 quella pioggia la conosceva gia'.
    val rainedBefore = series(at(11) to 1.0)
    assertEquals(false, RainWindows.outcome(at(10, 20), RainWindows.ZERO_ONE, rainedBefore))
    // Piove fra le 11 e le 12: e' la sua finestra 0-1h.
    val rainedAfter = series(at(12) to 1.0)
    assertEquals(true, RainWindows.outcome(at(10, 20), RainWindows.ZERO_ONE, rainedAfter))
    // Allo scoccare delle 11 la finestra e' la stessa.
    assertEquals(true, RainWindows.outcome(at(11), RainWindows.ZERO_ONE, rainedAfter))
    assertEquals(false, RainWindows.outcome(at(11), RainWindows.ZERO_ONE, rainedBefore))
  }

  @Test
  fun `le etichette sono quelle del verdetto`() {
    assertEquals(listOf("0-1h", "1-3h", "3-6h"), RainWindows.ALL.map { it.label })
    assertEquals(TrainedNowcastV1.windows.map { it.window }, RainWindows.ALL.map { it.label })
    assertEquals(RainWindows.ONE_THREE, RainWindows.byLabel("1-3h"))
    assertNull(RainWindows.byLabel("0-2h"))
  }

  // ------------------------------------------------------------------------- soglia e buchi

  @Test
  fun `la soglia e' 0,2 mm sull'accumulo, bordo compreso`() {
    assertEquals(true, RainWindows.outcome(at(10), RainWindows.ZERO_ONE, series(at(11) to 0.2)))
    assertEquals(false, RainWindows.outcome(at(10), RainWindows.ZERO_ONE, series(at(11) to 0.19)))
    // Due ore da 0,1 fanno una 1-3h bagnata: la soglia e' sulla finestra, non sull'ora.
    assertEquals(true, RainWindows.outcome(at(10), RainWindows.ONE_THREE, series(at(12) to 0.1, at(13) to 0.1)))
    // Tre ore da 0,06 no.
    val drizzle = series(at(14) to 0.06, at(15) to 0.06, at(16) to 0.06)
    assertEquals(false, RainWindows.outcome(at(10), RainWindows.THREE_SIX, drizzle))
  }

  @Test
  fun `la virgola mobile non sposta il bordo`() {
    // In binario 0,02 + 0,18 fa 0,19999999999999998: e' comunque una finestra da 0,2 mm.
    assertTrue(0.02 + 0.18 < 0.2)
    val outcome = RainWindows.evaluate(at(10), RainWindows.ONE_THREE, series(at(12) to 0.02, at(13) to 0.18))!!
    assertTrue(outcome.wet)
    assertEquals(0.2, outcome.sumMm!!, 1e-12)
    assertEquals(0, outcome.sightings)
  }

  @Test
  fun `uno slot mancante rende la finestra ingiudicabile`() {
    val holed: (Long) -> Double? = { if (it == at(13)) null else 0.0 }
    assertNull(RainWindows.outcome(at(10), RainWindows.ONE_THREE, holed))
    // Anche se l'altro slot della finestra e' bagnato: un "si'" parziale truccherebbe il tasso.
    val holedWet: (Long) -> Double? = { if (it == at(13)) null else if (it == at(12)) 5.0 else 0.0 }
    assertNull(RainWindows.outcome(at(10), RainWindows.ONE_THREE, holedWet))
    // NaN e' un buco come un altro.
    val nan: (Long) -> Double? = { if (it == at(11)) Double.NaN else 0.0 }
    assertNull(RainWindows.outcome(at(10), RainWindows.ZERO_ONE, nan))
    // Le altre finestre non ne risentono.
    assertEquals(false, RainWindows.outcome(at(10), RainWindows.ZERO_ONE, holed))
  }

  // ------------------------------------------------------------------------- l'occhio dell'utente

  @Test
  fun `un'osservazione bagnata rende bagnata la finestra, anche coi buchi`() {
    val dry = series()
    val seen = listOf(Sighting(at(12, 30), wet = true))
    val outcome = RainWindows.evaluate(at(10), RainWindows.ONE_THREE, dry, seen)!!
    assertTrue(outcome.wet)
    assertEquals(0.0, outcome.sumMm!!, 0.0)
    assertEquals(1, outcome.sightings)

    val holed: (Long) -> Double? = { if (it == at(13)) null else 0.0 }
    val decided = RainWindows.evaluate(at(10), RainWindows.ONE_THREE, holed, seen)!!
    assertTrue(decided.wet)
    assertNull(decided.sumMm)
  }

  @Test
  fun `un'osservazione asciutta azzera il suo slot`() {
    val wet = series(at(12) to 3.0)
    assertEquals(true, RainWindows.outcome(at(10), RainWindows.ONE_THREE, wet))
    // Alle 11:40 si e' guardato fuori e non pioveva: lo slot (11, 12] vale zero.
    val seenDry = listOf(Sighting(at(11, 40), wet = false))
    val outcome = RainWindows.evaluate(at(10), RainWindows.ONE_THREE, wet, seenDry)!!
    assertFalse(outcome.wet)
    assertEquals(0.0, outcome.sumMm!!, 0.0)
    // E riempie un buco: lo slot visto asciutto e' noto.
    val holed: (Long) -> Double? = { if (it == at(12)) null else 0.0 }
    assertEquals(false, RainWindows.outcome(at(10), RainWindows.ONE_THREE, holed, seenDry))
  }

  @Test
  fun `bagnato e asciutto nello stesso slot, vince bagnato`() {
    val seen = listOf(Sighting(at(11, 10), wet = false), Sighting(at(11, 50), wet = true))
    assertEquals(true, RainWindows.outcome(at(10), RainWindows.ONE_THREE, series(), seen))
  }

  @Test
  fun `le osservazioni fuori finestra non contano`() {
    val wet = series(at(12) to 3.0)
    // All'istante dell'emissione (ora piena) e' nello slot che si chiude li': il passato.
    val atIssue = listOf(Sighting(at(10), wet = true))
    assertEquals(false, RainWindows.outcome(at(10), RainWindows.ZERO_ONE, series(), atIssue))
    // Fra l'emissione delle 10:20 e l'ancora delle 11: non e' di nessuna finestra.
    val beforeAnchor = listOf(Sighting(at(10, 40), wet = true))
    assertEquals(false, RainWindows.outcome(at(10, 20), RainWindows.ZERO_ONE, series(), beforeAnchor))
    // Dopo la fine della finestra (la 1-3h delle 10 copre fino alle 13): non la tocca.
    val later = listOf(Sighting(at(13, 30), wet = false))
    val outcome = RainWindows.evaluate(at(10), RainWindows.ONE_THREE, wet, later)!!
    assertTrue(outcome.wet)
    assertEquals(0, outcome.sightings)
  }

  // ------------------------------------------------------------------------- finalita'

  @Test
  fun `la finestra si giudica solo dopo la finalita'`() {
    val issued = at(10, 20)
    val lastEnd = RainWindows.lastSlotEnd(issued, RainWindows.THREE_SIX)
    assertEquals(at(17), lastEnd)
    val day = 24 * hour
    assertFalse(RainWindows.isFinal(lastEnd, lastEnd + day - 1, day))
    assertTrue(RainWindows.isFinal(lastEnd, lastEnd + day, day))
    assertFalse(TruthPanel.isFinal(lastEnd, lastEnd + TruthPanel.FINALITY_MILLIS - 1))
    assertTrue(TruthPanel.isFinal(lastEnd, lastEnd + TruthPanel.FINALITY_MILLIS))
  }
}
