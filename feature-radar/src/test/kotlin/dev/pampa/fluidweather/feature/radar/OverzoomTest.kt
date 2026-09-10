package dev.pampa.fluidweather.feature.radar

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class OverzoomTest {

  @Test
  fun `al livello nativo o sotto il tile e' se stesso`() {
    assertEquals(Overzoom(33, 23, 0, 0, 256), Overzoom.of(33, 23, 6, 12))
    assertEquals(Overzoom(4096, 2048, 0, 0, 256), Overzoom.of(4096, 2048, 12, 12))
  }

  @Test
  fun `un livello oltre il padre e' la meta' delle coordinate e il quadrante e' di 128 px`() {
    // (2049, 1400) a zoom 13: padre (1024, 700), colonna dispari e riga pari.
    assertEquals(Overzoom(1024, 700, 128, 0, 128), Overzoom.of(2049, 1400, 13, 12))
    assertEquals(Overzoom(1024, 700, 0, 128, 128), Overzoom.of(2048, 1401, 13, 12))
  }

  @Test
  fun `quattro livelli oltre il quadrante e' di 16 px e sta al posto giusto`() {
    // x = 16*5 + 3, y = 16*7 + 15 a zoom 16: padre (5, 7), quadrante (3, 15) di 16 px.
    assertEquals(Overzoom(5, 7, 48, 240, 16), Overzoom.of(83, 127, 16, 12))
  }

  @Test
  fun `oltre otto livelli non resta nemmeno un pixel`() {
    assertNull(Overzoom.of(0, 0, 21, 12))
  }
}
