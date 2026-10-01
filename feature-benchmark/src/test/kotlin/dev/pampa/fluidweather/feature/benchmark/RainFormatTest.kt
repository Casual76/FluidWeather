package dev.pampa.fluidweather.feature.benchmark

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RainFormatTest {

  @Test
  fun `in italiano il Brier ha la virgola e tre decimali`() {
    assertEquals("0,053", fmtDecimals(0.0533, 3, Locale.ITALY))
    assertEquals("0,012", fmtDecimals(0.0118, 3, Locale.ITALY))
  }

  @Test
  fun `in inglese ha il punto`() {
    assertEquals("0.053", fmtDecimals(0.0533, 3, Locale.US))
  }

  @Test
  fun `la MAE ha due decimali e un numero tondo li tiene`() {
    assertEquals("0,18", fmtDecimals(0.1834, 2, Locale.ITALY))
    assertEquals("0,00", fmtDecimals(0.0, 2, Locale.ITALY))
    assertEquals("1,000", fmtDecimals(1.0, 3, Locale.ITALY))
  }

  @Test
  fun `senza lingua esplicita segue quella del telefono`() {
    val precedente = Locale.getDefault()
    try {
      Locale.setDefault(Locale.ITALY)
      assertEquals("0,250", fmtDecimals(0.25, 3))
    } finally {
      Locale.setDefault(precedente)
    }
  }
}
