package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.learning.PlattParams
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecalibrationV2rTest {

  @Test
  fun `la versione viene dal modello su cui poggia, e con un altro modello non vale`() {
    assertEquals(TrainedNowcastV1.VERSION, RecalibrationV2r.BASE_MODEL_VERSION)
    assertEquals("v2r-" + TrainedNowcastV1.VERSION.removePrefix("v2-"), RecalibrationV2r.VERSION)
    // Si etichetta come ogni altra versione del modello.
    val tag = ModelVersions.tag(model = RecalibrationV2r.VERSION)
    assertEquals(RecalibrationV2r.VERSION, ModelVersions.parse(tag)!!.model)
  }

  @Test
  fun `ci sono sei mappe, una per finestra e famiglia, dentro il recinto della pendenza`() {
    for (hasContext in listOf(true, false)) {
      val table = if (hasContext) RecalibrationV2r.WITH_CONTEXT else RecalibrationV2r.WITHOUT_CONTEXT
      assertEquals(RainWindows.ALL.map { it.label }.toSet(), table.keys)
      for ((_, map) in table) {
        assertTrue("pendenza ${map.a}", map.a in 0.3..3.0)
        assertTrue(map.samples > 0)
      }
    }
  }

  @Test
  fun `la mappa e' quella di PlattParams, crescente e dentro (0, 1)`() {
    for (hasContext in listOf(true, false)) {
      for (window in RainWindows.ALL) {
        val map = RecalibrationV2r.coefficients(window.label, hasContext)
        var previous = 0.0
        for (i in 0..100) {
          val p = i / 100.0
          val q = RecalibrationV2r.apply(window.label, hasContext, p)
          assertEquals(PlattParams(map.a, map.b).apply(p), q, 1e-15)
          assertTrue(q > 0.0 && q < 1.0)
          assertTrue(q >= previous)
          previous = q
        }
      }
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun `una finestra sconosciuta non passa in silenzio`() {
    RecalibrationV2r.apply("0-2h", hasContext = true, probability = 0.3)
  }
}
