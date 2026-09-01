package dev.pampa.fluidweather.nowcast.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeaLevelTest {

  @Test
  fun `a quota zero la riduzione e' l'identita'`() {
    assertEquals(1000.0, SeaLevel.reduce(1000.0, 0.0), 1e-12)
  }

  @Test
  fun `valore noto - 1000 hPa a 100 m in atmosfera standard fanno circa 1011,9`() {
    // Verificato a mano con la formula ipsometrica: fattore 1,011915 a 100 m e 15 gradi.
    assertEquals(1011.92, SeaLevel.reduce(1000.0, 100.0, 15.0), 0.1)
  }

  @Test
  fun `aria piu' calda, colonna piu' leggera, riduzione minore`() {
    val calda = SeaLevel.reduce(1000.0, 500.0, 30.0)
    val fredda = SeaLevel.reduce(1000.0, 500.0, 0.0)
    assertTrue(calda < fredda)
  }

  @Test
  fun `piu' quota, piu' colonna da aggiungere`() {
    val bassa = SeaLevel.reduce(1000.0, 100.0)
    val alta = SeaLevel.reduce(1000.0, 200.0)
    assertTrue(alta > bassa)
  }

  @Test
  fun `l'ordine di grandezza e' quello del gradiente barometrico - circa 0,12 hPa al metro`() {
    val delta = SeaLevel.reduce(1013.0, 10.0) - 1013.0
    assertEquals(1.2, delta, 0.15)
  }
}
