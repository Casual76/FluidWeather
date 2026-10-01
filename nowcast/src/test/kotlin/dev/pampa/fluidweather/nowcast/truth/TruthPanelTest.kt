package dev.pampa.fluidweather.nowcast.truth

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TruthPanelTest {

  private val france = "meteofrance_seamless"
  private val uk = "ukmo_seamless"
  private val canada = "gem_seamless"

  @Test
  fun `il pannello e' quello congelato`() {
    assertEquals("panel-1", TruthPanel.VERSION)
    assertEquals(listOf(france, uk, canada), TruthPanel.MODELS)
    assertEquals(3, TruthPanel.QUORUM)
    assertEquals("$france,$uk,$canada", TruthPanel.MODELS_PARAMETER)
    assertEquals(24 * 3_600_000L, TruthPanel.FINALITY_MILLIS)
    assertEquals(Instant.parse("2022-11-24T00:00:00Z").toEpochMilli(), TruthPanel.AVAILABLE_FROM_MILLIS)
  }

  @Test
  fun `nessuna famiglia in classifica siede in giuria`() {
    val competitors = listOf("best_match", "icon", "ecmwf", "gfs", "jma")
    assertTrue(TruthPanel.MODELS.none { model -> competitors.any { model.startsWith(it) } })
  }

  @Test
  fun `lo slot vale la mediana dei tre`() {
    assertEquals(0.4, TruthPanel.slotValue(mapOf(france to 0.0, uk to 0.4, canada to 3.0))!!, 0.0)
    // Il modello che da solo inventa un temporale non sposta niente.
    assertEquals(0.0, TruthPanel.slotValue(mapOf(france to 0.0, uk to 0.0, canada to 12.0))!!, 0.0)
    // Voci fuori dal pannello: ignorate, non allargano la giuria.
    val withIntruder = mapOf(france to 0.0, uk to 0.4, canada to 3.0, "icon_seamless" to 9.0, "gfs_seamless" to 9.0)
    assertEquals(0.4, TruthPanel.slotValue(withIntruder)!!, 0.0)
  }

  @Test
  fun `senza tutti i giudici lo slot resta senza verita'`() {
    assertNull(TruthPanel.slotValue(mapOf(france to 0.0, uk to 0.4)))
    assertNull(TruthPanel.slotValue(mapOf(france to 0.0, uk to 0.4, canada to null)))
    assertNull(TruthPanel.slotValue(mapOf(france to Double.NaN, uk to 0.4, canada to 0.1)))
  }

  @Test
  fun `la serie combinata tiene solo gli slot col quorum, in ordine`() {
    val h = 3_600_000L
    val combined = TruthPanel.combine(
      mapOf(
        france to mapOf(3 * h to 0.0, h to 0.2, 2 * h to 1.0),
        uk to mapOf(h to 0.4, 2 * h to 0.0, 3 * h to 0.5),
        canada to mapOf(h to 0.3, 3 * h to 0.1),
      ),
    )
    assertEquals(listOf(h, 3 * h), combined.keys.toList())
    assertEquals(0.3, combined.getValue(h), 0.0)
    assertEquals(0.1, combined.getValue(3 * h), 0.0)
    // Un giudice assente del tutto: nessuna verita'.
    assertTrue(TruthPanel.combine(mapOf(france to mapOf(h to 0.0), uk to mapOf(h to 0.0))).isEmpty())
  }

  @Test
  fun `la mediana di una lista pari e' la media dei due di mezzo`() {
    assertEquals(1.5, TruthPanel.median(listOf(3.0, 1.0, 2.0, 0.0)), 0.0)
    assertTrue(TruthPanel.median(emptyList()).isNaN())
  }
}
