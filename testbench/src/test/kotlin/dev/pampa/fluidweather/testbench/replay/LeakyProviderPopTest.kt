package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakyProviderPopTest {

  private val hour = SyntheticWorld.HOUR
  private val anchor = SyntheticWorld.START_MILLIS + 100 * hour

  @Test
  fun `la PoP di finestra e' il massimo sugli slot esatti della finestra`() {
    // Slot che si chiudono da ancora+1h ad ancora+6h; fuori dalla finestra un valore enorme che non deve contare.
    val pop = mapOf(
      anchor to 0.99, // lo slot che si chiude all'ancora e' passato: non e' della 0-1h
      anchor + hour to 0.1,
      anchor + 2 * hour to 0.4,
      anchor + 3 * hour to 0.2,
      anchor + 4 * hour to 0.3,
      anchor + 5 * hour to 0.7,
      anchor + 6 * hour to 0.5,
      anchor + 7 * hour to 0.99,
    )
    assertEquals(0.1, LeakyProviderPop.windowPop(pop, anchor, RainWindows.ZERO_ONE), 0.0)
    assertEquals(0.4, LeakyProviderPop.windowPop(pop, anchor, RainWindows.ONE_THREE), 0.0)
    assertEquals(0.7, LeakyProviderPop.windowPop(pop, anchor, RainWindows.THREE_SIX), 0.0)
    // Un buco in mezzo rende la finestra muta, non "il massimo di quel che c'e'".
    assertTrue(LeakyProviderPop.windowPop(pop - (anchor + 5 * hour), anchor, RainWindows.THREE_SIX).isNaN())
  }

  @Test
  fun `le colonne dicono che hanno fuga e rispondono solo in FRESH`() {
    val predictor = LeakyProviderPop(listOf("icon_seamless"))
    assertEquals(listOf("icon_seamless PoP con fuga (stitched)"), predictor.columns)
    assertTrue(predictor.columns.all { LeakyProviderPop.isLeaky(it) })
    assertFalse(PredictorNames.BASELINES.any { LeakyProviderPop.isLeaky(it) })

    val day = 24 * hour
    val start = SyntheticWorld.START_MILLIS
    val pop = (0 until 40 * 24).associate { start + it * hour to 0.35 }
    val input = SyntheticWorld.inputs("a", days = 40, providerPop = mapOf("icon_seamless" to pop))
    val result = TierReplayer(TierPeriod("sint", start + 30 * day, start + 33 * day, start), extras = listOf(predictor), threads = 1)
      .replay(listOf(input))
    val column = result.indexOf("icon_seamless PoP con fuga (stitched)")
    for (record in result.records) {
      if (record.kind == TierKind.FRESH) {
        assertEquals(0.35, record.probabilities[column], 0.0)
      } else {
        assertTrue(record.probabilities[column].isNaN())
      }
    }
    // Mai la migliore baseline, anche quando sarebbe la migliore per Brier.
    val stats = TierStats(result)
    for (w in RainWindows.ALL.indices) {
      val rows = stats.rows(stats.cases(listOf("a"), TierKind.FRESH, w, TruthKind.PANEL), TruthKind.PANEL)
      assertFalse(LeakyProviderPop.isLeaky(stats.bestBaseline(rows)!!.predictor))
    }
  }
}
