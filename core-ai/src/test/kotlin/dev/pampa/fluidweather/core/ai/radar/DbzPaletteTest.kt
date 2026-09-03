package dev.pampa.fluidweather.core.ai.radar

import dev.pampa.fluidweather.core.weather.RainViewerPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class DbzPaletteTest {

  @Test
  fun `ogni gradino della palette torna al suo dBZ`() {
    RainViewerPalette.universalBlue.forEach { stop ->
      assertEquals("stop ${stop.dbz}", stop.dbz, DbzPalette.dbzOf(stop.argb.toInt()))
    }
  }

  @Test
  fun `i colori quasi uguali (arrotondamento del PNG) contano come il gradino`() {
    val light = 0xFF00A3E0L.toInt() // 20 dBZ
    val nudged = (light and 0xFF000000.toInt()) or (0x02 shl 16) or (0xA1 shl 8) or 0xDE
    assertEquals(20, DbzPalette.dbzOf(nudged))
  }

  @Test
  fun `trasparente e' niente, un colore estraneo e' ignoto`() {
    assertEquals(DbzPalette.NONE, DbzPalette.dbzOf(0x00000000))
    assertEquals(DbzPalette.NONE, DbzPalette.dbzOf(0x10FFFFFF))
    assertEquals(DbzPalette.UNKNOWN, DbzPalette.dbzOf(0xFF3C8A2EL.toInt()))
  }

  @Test
  fun `intensita' e tasso di pioggia seguono le soglie della legenda`() {
    assertEquals(DbzPalette.Intensity.NONE, DbzPalette.intensity(5))
    assertEquals(DbzPalette.Intensity.DRIZZLE, DbzPalette.intensity(15))
    assertEquals(DbzPalette.Intensity.LIGHT, DbzPalette.intensity(25))
    assertEquals(DbzPalette.Intensity.MODERATE, DbzPalette.intensity(35))
    assertEquals(DbzPalette.Intensity.HEAVY, DbzPalette.intensity(45))
    assertEquals(DbzPalette.Intensity.VERY_HEAVY, DbzPalette.intensity(55))
    assertEquals(DbzPalette.Intensity.HAIL, DbzPalette.intensity(65))
    assertEquals(0.65, DbzPalette.rateMmPerHour(20), 0.05)
    assertEquals(2.7, DbzPalette.rateMmPerHour(30), 0.1)
    assertEquals(11.5, DbzPalette.rateMmPerHour(40), 0.3)
    assertEquals(48.6, DbzPalette.rateMmPerHour(50), 1.0)
    assertEquals(0.0, DbzPalette.rateMmPerHour(0), 0.0)
  }
}
