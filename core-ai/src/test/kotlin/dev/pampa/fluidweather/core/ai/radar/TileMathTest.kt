package dev.pampa.fluidweather.core.ai.radar

import org.junit.Assert.assertEquals
import org.junit.Test

class TileMathTest {

  @Test
  fun `l'origine sta al centro del piano e le tile si contano da li'`() {
    val g = TileMath.toGlobalPixel(0.0, 0.0, 8)
    assertEquals(32768.0, g.x, 1e-6)
    assertEquals(32768.0, g.y, 1e-6)
    assertEquals(TileMath.TileCoord(128, 128), TileMath.tileOf(g))
  }

  @Test
  fun `Firenze a zoom 8 cade nella tile 136 in x e in una riga fra 93 e 94`() {
    val g = TileMath.toGlobalPixel(43.77, 11.25, 8)
    val tile = TileMath.tileOf(g)
    assertEquals(136, tile.x)
    assert(tile.y in 93..94) { "riga inattesa ${tile.y}" }
  }

  @Test
  fun `andata e ritorno`() {
    val (lat, lon) = TileMath.toLatLon(TileMath.toGlobalPixel(43.77, 11.25, 9).let { it.x }, TileMath.toGlobalPixel(43.77, 11.25, 9).y, 9)
    assertEquals(43.77, lat, 1e-6)
    assertEquals(11.25, lon, 1e-6)
  }

  @Test
  fun `metri per pixel all'equatore e a 44 gradi`() {
    assertEquals(611.496, TileMath.metersPerPixel(0.0, 8), 0.01)
    assertEquals(439.9, TileMath.metersPerPixel(44.0, 8), 0.5)
    assertEquals(219.9, TileMath.metersPerPixel(44.0, 9), 0.5)
  }

  @Test
  fun `l'antimeridiano riavvolge le tile`() {
    assertEquals(0, TileMath.wrapX(256, 8))
    assertEquals(255, TileMath.wrapX(-1, 8))
    assertEquals(5, TileMath.wrapX(5, 8))
  }
}
