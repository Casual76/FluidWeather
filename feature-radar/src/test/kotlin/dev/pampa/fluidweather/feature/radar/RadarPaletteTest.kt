package dev.pampa.fluidweather.feature.radar

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * La tavolozza del radar segue il tema (fase 24).
 *
 * Prima erano cinque costanti scure, perche' la mappa era in tema scuro fisso: chi scegliera'
 * "Chiaro" ora vede la mappa chiara, e questi test dicono che legenda, pin e barra del tempo
 * restano leggibili sopra — che era esattamente il modo di rompere la cosa.
 */
class RadarPaletteTest {

  @Test
  fun `il testo si stacca dal pannello in entrambi i temi`() {
    for (dark in listOf(true, false)) {
      val palette = RadarPalette(dark)
      val stacco = abs(palette.onPanel.luminance() - palette.panel.luminance())

      assertTrue("tema dark=$dark: stacco $stacco troppo poco", stacco > 0.4f)
    }
  }

  @Test
  fun `il tema chiaro e' chiaro e lo scuro e' scuro`() {
    assertTrue(RadarPalette(dark = false).ink.luminance() > 0.6f)
    assertTrue(RadarPalette(dark = true).ink.luminance() < 0.05f)
  }

  @Test
  fun `il colore d'accento si vede sul pannello`() {
    // L'accento disegna il passato sulla barra del tempo: sul pannello chiaro l'azzurro pallido
    // del tema scuro sarebbe sparito, ed e' per questo che ne esistono due.
    for (dark in listOf(true, false)) {
      val palette = RadarPalette(dark)
      val stacco = abs(palette.accent.luminance() - palette.panel.luminance())

      assertTrue("tema dark=$dark: accento invisibile ($stacco)", stacco > 0.15f)
    }
  }

  @Test
  fun `lo sbiadito e' lo stesso colore del testo, solo piu' timido`() {
    for (dark in listOf(true, false)) {
      val palette = RadarPalette(dark)

      assertTrue(palette.faint.alpha < palette.onPanel.alpha)
      assertTrue(palette.faint.red == palette.onPanel.red)
      assertTrue(palette.faint.blue == palette.onPanel.blue)
    }
  }
}
