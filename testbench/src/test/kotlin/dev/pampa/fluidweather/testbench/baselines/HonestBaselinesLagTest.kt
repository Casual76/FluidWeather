package dev.pampa.fluidweather.testbench.baselines

import dev.pampa.fluidweather.nowcast.climatology.LocalBaselines
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LB2 dentro le baseline oneste: la persistenza si calibra sul ritardo del contesto, le avversarie e le feature leggono lo stesso oggetto. */
class HonestBaselinesLagTest {

  private val hour = SyntheticWorld.HOUR
  private val start = SyntheticWorld.START_MILLIS
  private val period = TierPeriod("sint", start + 40 * 24 * hour, start + 55 * 24 * hour, start)
  private val issue = start + (40 * 24 + 93 - 40 * 24 % 96) * hour

  @Test
  fun `a parita' di pioggia di adesso, un contesto piu' vecchio annuncia meno`() {
    val honest = HonestBaselines.buildAll(period, listOf(SyntheticWorld.inputs("a", days = 60))).getValue("a")
    val window = RainWindows.ZERO_ONE
    val anchor = RainWindows.anchorOf(issue)
    val fresh = honest.persistence(window, issue, 1.0, anchor - 2 * hour, ContextTier.FRESH)!!
    val stale = honest.persistence(window, issue, 1.0, anchor - 12 * hour, ContextTier.STALE)!!
    assertTrue("$fresh contro $stale", fresh > stale + 0.2)
    // Oltre tredici ore di ritardo il contesto non e' piu' un contesto.
    assertNotNull(honest.persistence(window, issue, 1.0, anchor - 13 * hour))
    assertNull(honest.persistence(window, issue, 1.0, anchor - 14 * hour))
  }

  @Test
  fun `le avversarie e le feature leggono le stesse tabelle`() {
    val honest = HonestBaselines.buildAll(period, listOf(SyntheticWorld.inputs("a", days = 60))).getValue("a")
    val anchor = RainWindows.anchorOf(issue)
    for (window in RainWindows.ALL) {
      for (tier in ContextTier.entries) {
        val priors = honest.priors(tier)!!
        assertEquals(priors.climatology(window, issue), honest.climatology(window, issue, tier)!!, 0.0)
        assertEquals(priors.barometric(window, issue, -0.9), honest.barometric(window, issue, -0.9, tier)!!, 0.0)
      }
      assertEquals(
        honest.priors(ContextTier.STALE)!!.persistence(window, issue, 1.0, anchor - 6 * hour),
        honest.persistence(window, issue, 1.0, anchor - 6 * hour, ContextTier.STALE),
      )
    }
  }

  @Test
  fun `NONE_NOCLIMA ha solo il riferimento di tutti i posti, gli altri livelli il locale`() {
    val built = HonestBaselines.buildAll(
      period,
      listOf(SyntheticWorld.inputs("a", days = 60), SyntheticWorld.inputs("b", days = 60, phase = 7)),
    )
    val a = built.getValue("a")
    assertFalse(a.priors(ContextTier.NONE_NOCLIMA)!!.isLocal)
    for (tier in listOf(ContextTier.FRESH, ContextTier.STALE, ContextTier.NONE)) assertTrue(a.priors(tier)!!.isLocal)
    assertFalse(a.pooledOnly()!!.isLocal)
    // Il riferimento e' lo stesso per tutte le localita'.
    val fromB = built.getValue("b").pooledOnly()!!
    assertEquals(a.pooledOnly()!!.encodedState(), fromB.encodedState())
  }

  @Test
  fun `senza storia nessuna tabella`() {
    val input = SyntheticWorld.inputs("a", days = 60)
    val later = start + 70 * 24 * hour
    val empty = HonestBaselines.buildAll(TierPeriod("vuoto", later, later + 6 * 24 * hour, later), listOf(input)).getValue("a")
    for (tier in ContextTier.entries) assertNull(empty.priors(tier))
    assertNull(empty.pooledOnly())
  }

  @Test
  fun `un solo intervallo di storia e' esattamente buildAll`() {
    val input = SyntheticWorld.inputs("a", days = 60)
    val viaPeriod = HonestBaselines.buildAll(period, listOf(input)).getValue("a")
    val viaRange = HonestBaselines.buildFromRanges(listOf(period.historyFromMillis..period.historyUntilMillis), listOf(input)).getValue("a")
    assertEquals(viaPeriod.encodedState(), viaRange.encodedState())
  }

  @Test
  fun `piu' intervalli non leggono mai nel buco e non contano le finestre a cavallo`() {
    val ranges = listOf(start..(start + 10 * 24 * hour), (start + 30 * 24 * hour)..(start + 39 * 24 * hour))
    val real = SyntheticWorld.inputs("a", days = 60)
    // Stesso mondo ma con un diluvio nel buco (giorni 11-29): le tabelle non cambiano.
    val deluge = SyntheticWorld.inputs(
      "a", days = 60,
      panelOverrideAfterMillis = start + 10 * 24 * hour + hour,
      panelOverrideUntilMillis = start + 30 * 24 * hour - hour,
    )
    val a = HonestBaselines.buildFromRanges(ranges, listOf(real)).getValue("a")
    val b = HonestBaselines.buildFromRanges(ranges, listOf(deluge)).getValue("a")
    assertEquals(a.encodedState(), b.encodedState())
    // Un diluvio dentro un intervallo si vede.
    val inside = SyntheticWorld.inputs("a", days = 60, panelOverrideAfterMillis = start + 2 * 24 * hour, panelOverrideUntilMillis = start + 3 * 24 * hour)
    assertNotEquals(a.encodedState(), HonestBaselines.buildFromRanges(ranges, listOf(inside)).getValue("a").encodedState())
    // Meno casi di un intervallo unico che copre anche il buco.
    val whole = HonestBaselines.buildFromRanges(listOf(start..(start + 39 * 24 * hour)), listOf(real)).getValue("a")
    assertTrue(a.localClimatology!!.samples("1-3h") < whole.localClimatology!!.samples("1-3h") * 0.8)
  }

  @Test
  fun `la fascia di ritardo usata e' quella di LocalBaselines`() {
    val honest = HonestBaselines.buildAll(period, listOf(SyntheticWorld.inputs("a", days = 60))).getValue("a")
    val anchor = RainWindows.anchorOf(issue)
    assertEquals(LocalBaselines.lagBinOf(3), LocalBaselines.lagBinOf(LocalBaselines.lagOf(issue, anchor - 3 * hour)))
    assertNotNull(honest.persistence(RainWindows.ONE_THREE, issue, 0.0, anchor - 3 * hour))
  }
}
