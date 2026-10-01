package dev.pampa.fluidweather.testbench.baselines

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestBaselinesTest {

  private val hour = SyntheticWorld.HOUR
  private val start = SyntheticWorld.START_MILLIS

  /** Quaranta giorni di storia, quindici di periodo. */
  private val period = TierPeriod("sint", start + 40 * 24 * hour, start + 55 * 24 * hour, start)

  /** Un'emissione dentro il periodo, all'ora 93 del ciclo (nel pieno della caduta): ora piena. */
  private val issue = start + (40 * 24 + 93 - 40 * 24 % 96) * hour

  @Test
  fun `le baseline non vedono il periodo che giudicano`() {
    val real = SyntheticWorld.inputs("a", days = 60)
    // Stessa storia, ma dopo l'inizio del periodo il pannello e' un diluvio continuo.
    val deluge = SyntheticWorld.inputs("a", days = 60, panelOverrideAfterMillis = period.firstMillis)

    val a = HonestBaselines.buildAll(period, listOf(real)).getValue("a")
    val b = HonestBaselines.buildAll(period, listOf(deluge)).getValue("a")

    assertTrue(a.hasHistory)
    assertEquals(a.encodedState(), b.encodedState())
    for (window in RainWindows.ALL) {
      for (tier in ContextTier.entries) {
        assertEquals(a.climatology(window, issue, tier), b.climatology(window, issue, tier))
        assertEquals(a.barometric(window, issue, -1.0, tier), b.barometric(window, issue, -1.0, tier))
      }
      assertEquals(a.persistence(window, issue, 1.0, issue), b.persistence(window, issue, 1.0, issue))
    }

    // E il controllo che il test sappia accorgersene: lo stesso diluvio, ma dentro la storia, cambia lo stato.
    val contaminated = SyntheticWorld.inputs("a", days = 60, panelOverrideAfterMillis = start + 10 * 24 * hour)
    val c = HonestBaselines.buildAll(period, listOf(contaminated)).getValue("a")
    assertNotEquals(a.encodedState(), c.encodedState())
  }

  @Test
  fun `le baseline non vedono le ore non ancora definitive alla prima emissione del periodo`() {
    // Revisione P3b: la prima emissione e' fino a un'ora prima di firstMillis, e a quell'istante il
    // pannello e' definitivo solo fino a 24 ore prima. Un diluvio nelle ultime 25 ore prima del periodo
    // non deve cambiare niente; prima della correzione la storia arrivava fino a firstMillis compreso.
    val real = SyntheticWorld.inputs("a", days = 60)
    val lastFinal = period.firstMillis - TierScenarios.ISSUE_OFFSET_BOUND_MILLIS - TruthPanel.FINALITY_MILLIS
    assertEquals(lastFinal, period.historyUntilMillis)
    val lateDeluge = SyntheticWorld.inputs("a", days = 60, panelOverrideAfterMillis = lastFinal)
    val a = HonestBaselines.buildAll(period, listOf(real)).getValue("a")
    val b = HonestBaselines.buildAll(period, listOf(lateDeluge)).getValue("a")
    assertEquals(a.encodedState(), b.encodedState())

    // Un'ora prima, invece, e' storia definitiva: il diluvio si vede (il test sa accorgersene).
    val earlier = SyntheticWorld.inputs("a", days = 60, panelOverrideAfterMillis = lastFinal - 6 * hour)
    val c = HonestBaselines.buildAll(period, listOf(earlier)).getValue("a")
    assertNotEquals(a.encodedState(), c.encodedState())
  }

  @Test
  fun `la storia e' solo quella dentro la finestra di storia del periodo`() {
    val input = SyntheticWorld.inputs("a", days = 60)
    val all = HonestBaselines.buildAll(period, listOf(input)).getValue("a")
    // Con una storia di soli 10 giorni (gli ultimi prima del periodo) le baseline hanno meno campioni.
    val short = TierPeriod("corto", period.firstMillis, period.endExclusiveMillis, period.firstMillis - 10 * 24 * hour)
    val shortHistory = HonestBaselines.buildAll(short, listOf(input)).getValue("a")
    val full = all.localClimatology!!.samples("1-3h")
    val cut = shortHistory.localClimatology!!.samples("1-3h")
    assertTrue("campioni $cut contro $full", cut < full / 3)
    assertTrue(cut > 0)
  }

  @Test
  fun `senza storia la localita' non ha baseline`() {
    val input = SyntheticWorld.inputs("a", days = 60)
    // Un periodo che comincia dopo la fine dei dati: nel pannello, prima del suo inizio, non c'e' niente.
    val later = start + 70 * 24 * hour
    val empty = TierPeriod("vuoto", later, later + 6 * 24 * hour, later)
    val baselines = HonestBaselines.buildAll(empty, listOf(input)).getValue("a")
    assertFalse(baselines.hasHistory)
    assertNull(baselines.climatology(RainWindows.ONE_THREE, issue, ContextTier.FRESH))
    assertNull(baselines.persistence(RainWindows.ONE_THREE, issue, 1.0, issue))
  }

  @Test
  fun `senza climatologia locale si usa il tasso di tutte le localita'`() {
    val rainy = SyntheticWorld.inputs("a", days = 60)
    // Una localita' dove non piove mai, nel pannello.
    val dry = SyntheticWorld.inputs("b", days = 60, panelOverrideAfterMillis = start - 1, panelOverrideMm = 0.0)
    val built = HonestBaselines.buildAll(period, listOf(rainy, dry))
    val a = built.getValue("a")
    val b = built.getValue("b")

    val window = RainWindows.ONE_THREE
    val label = window.label
    val samplesA = a.localClimatology!!.samples(label)
    val samplesB = b.localClimatology!!.samples(label)
    val wetA = a.localClimatology!!.overallRate(label)!! * samplesA
    val wetB = b.localClimatology!!.overallRate(label)!! * samplesB
    val expected = (wetA + wetB) / (samplesA + samplesB)

    // In NONE_NOCLIMA le due localita' dicono la stessa cosa: il tasso sommato, a prescindere dall'emissione.
    val pooledA = a.climatology(window, issue, ContextTier.NONE_NOCLIMA)!!
    val pooledB = b.climatology(window, issue + 5 * hour, ContextTier.NONE_NOCLIMA)!!
    assertEquals(expected, pooledA, 1e-9)
    assertEquals(expected, pooledB, 1e-9)

    // Nei livelli che la conoscono, la localita' asciutta dice zero e quella piovosa il suo tasso.
    assertEquals(0.0, b.climatology(window, issue, ContextTier.NONE)!!, 1e-9)
    assertTrue(a.climatology(window, issue, ContextTier.NONE)!! > 0.0)
    assertTrue(pooledB > 0.0)
  }

  @Test
  fun `la persistenza e la regola barometrica sanno qualcosa nel mondo prevedibile`() {
    val input = SyntheticWorld.inputs("a", days = 60)
    val b = HonestBaselines.buildAll(period, listOf(input)).getValue("a")

    // Piovere adesso annuncia pioggia a breve; un'ora asciutta no.
    val wetNow = b.persistence(RainWindows.ZERO_ONE, issue, 1.0, issue - 2 * hour)!!
    val dryNow = b.persistence(RainWindows.ZERO_ONE, issue, 0.0, issue - 2 * hour)!!
    assertTrue("$wetNow contro $dryNow", wetNow > dryNow + 0.3)

    // La pressione che crolla a 1 hPa/h annuncia la pioggia del ciclo; una pressione ferma no.
    val falling = b.barometric(RainWindows.ONE_THREE, issue, -1.0, ContextTier.NONE)!!
    val steady = b.barometric(RainWindows.ONE_THREE, issue, 0.0, ContextTier.NONE)!!
    assertTrue("$falling contro $steady", falling > steady + 0.1)

    // Dati mancanti: nessuna risposta, non un tasso inventato.
    assertNull(b.persistence(RainWindows.ZERO_ONE, issue, null, issue))
    assertNull(b.persistence(RainWindows.ZERO_ONE, issue, Double.NaN, issue))
    assertNull(b.barometric(RainWindows.ONE_THREE, issue, null, ContextTier.NONE))
    assertNull(b.barometric(RainWindows.ONE_THREE, issue, Double.NaN, ContextTier.NONE_NOCLIMA))
  }

  @Test
  fun `senza climatologia la regola barometrica usa i conteggi di tutte le localita'`() {
    val a = SyntheticWorld.inputs("a", days = 60)
    val b = SyntheticWorld.inputs("b", days = 60, phase = 7)
    val built = HonestBaselines.buildAll(period, listOf(a, b))
    val falling = built.getValue("a").barometric(RainWindows.ONE_THREE, issue, -1.0, ContextTier.NONE_NOCLIMA)
    val steady = built.getValue("a").barometric(RainWindows.ONE_THREE, issue, 0.0, ContextTier.NONE_NOCLIMA)
    assertNotNull(falling)
    assertNotNull(steady)
    assertTrue("$falling contro $steady", falling!! > steady!! + 0.1)
    // La regola di tutte le localita' e' la stessa per tutte: non dipende da chi chiede ne' da quando.
    assertEquals(falling, built.getValue("b").barometric(RainWindows.ONE_THREE, issue + 9 * hour, -1.0, ContextTier.NONE_NOCLIMA))
  }

  @Test
  fun `i vecchi numeri fissi sono quelli di prima`() {
    assertEquals(0.85, LegacyRules.fixedPersistence(0.5, 0.1), 1e-12)
    assertEquals(0.08, LegacyRules.fixedPersistence(0.0, 0.1), 1e-12)
    assertEquals(0.1, LegacyRules.fixedPersistence(null, 0.1), 1e-12)
    assertEquals(0.75, LegacyRules.fixedBarometric(0.1, -1.5), 1e-12)
    assertEquals(0.25, LegacyRules.fixedBarometric(0.1, -0.8), 1e-12)
    assertEquals(0.6, LegacyRules.fixedBarometric(0.5, -0.8), 1e-12)
    assertEquals(0.04, LegacyRules.fixedBarometric(0.1, 0.8), 1e-12)
    assertEquals(0.02, LegacyRules.fixedBarometric(0.01, 0.8), 1e-12)
    assertEquals(0.1, LegacyRules.fixedBarometric(0.1, 0.0), 1e-12)
    assertEquals(0.1, LegacyRules.fixedBarometric(0.1, null), 1e-12)
  }
}
