package dev.pampa.fluidweather.feature.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La mappa del radar su MapLibre e OpenFreeMap: le scelte che si possono provare senza un telefono.
 *
 * Prima c'era OverzoomTest, per il ritaglio a mano dei tile oltre lo zoom nativo: ora lo fa
 * MapLibre da se' con il `maxzoom` della sorgente, e qui si prova che il massimo e' quello vero.
 */
class RadarMapCatalogTest {

  @Test
  fun `lo stile segue il tema e nessuno chiede una chiave`() {
    assertTrue(RadarMapCatalog.styleUrl(dark = true).endsWith("/dark"))
    assertTrue(RadarMapCatalog.styleUrl(dark = false).endsWith("/positron"))
    for (dark in listOf(true, false)) {
      val url = RadarMapCatalog.styleUrl(dark)
      assertTrue(url.startsWith("https://tiles.openfreemap.org/"))
      assertFalse("niente chiavi nell'indirizzo: $url", "key" in url.lowercase())
    }
  }

  @Test
  fun `gli zoom di Google diventano quelli di MapLibre a parita' di inquadratura`() {
    // Tile da 512 contro tile da 256: un livello di differenza, sempre.
    assertEquals(4.0, RadarMapCatalog.zoom(5.0), 0.0)
    assertEquals(6.0, RadarMapCatalog.zoom(7.0), 0.0)
    assertEquals(2.0, RadarMapCatalog.MIN_ZOOM, 0.0)
    assertEquals(15.0, RadarMapCatalog.MAX_ZOOM, 0.0)
  }

  @Test
  fun `RainViewer si chiede solo fin dove disegna`() {
    // Oltre il 7 risponde con un'immagine "Zoom Level Not Supported" (provato il 2026-11-02):
    // chiederla vorrebbe dire incollarla sulla mappa.
    assertEquals(7f, RadarMapCatalog.RAINVIEWER_MAX_ZOOM)
    assertTrue(RadarMapCatalog.OWM_MAX_ZOOM > RadarMapCatalog.RAINVIEWER_MAX_ZOOM)
  }

  @Test
  fun `si vede un fotogramma alla volta, e nessuno fuori dalle precipitazioni`() {
    val count = 13
    for (shown in 0 until count) {
      val visible = (0 until count).filter { RadarMapCatalog.frameOpacity(it, shown, precipitation = true) > 0f }
      assertEquals(listOf(shown), visible)
    }
    assertTrue((0 until count).all { RadarMapCatalog.frameOpacity(it, 5, precipitation = false) == 0f })
    assertEquals(0.85f, RadarMapCatalog.frameOpacity(5, 5, precipitation = true))
  }

  @Test
  fun `i tile di OWM portano livello e chiave dell'utente`() {
    assertEquals(
      "https://tile.openweathermap.org/map/temp_new/{z}/{x}/{y}.png?appid=abc",
      RadarMapCatalog.owmTemplate("temp_new", "abc"),
    )
  }

  @Test
  fun `i nomi dei posti si traducono solo fuori dall'inglese`() {
    assertNull(RadarMapCatalog.labelLanguage("en"))
    assertNull(RadarMapCatalog.labelLanguage(""))
    assertEquals("it", RadarMapCatalog.labelLanguage("it"))
    assertEquals("it", RadarMapCatalog.labelLanguage("IT"))
  }

  @Test
  fun `gli identificativi degli strati non si pestano`() {
    val ids = (0 until 20).flatMap { listOf(RadarMapCatalog.frameSourceId(it), RadarMapCatalog.frameLayerId(it)) } +
      listOf(RadarMapCatalog.OWM_SOURCE, RadarMapCatalog.OWM_LAYER)
    assertEquals(ids.size, ids.toSet().size)
  }
}
