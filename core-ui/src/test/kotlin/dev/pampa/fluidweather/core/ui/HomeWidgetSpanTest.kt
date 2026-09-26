package dev.pampa.fluidweather.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** La griglia della home su telefono e su tablet (2026-09-26): quante colonne, e chi ne prende quante. */
class HomeWidgetSpanTest {

  @Test
  fun `sul telefono niente cambia`() {
    assertEquals(2, HomeWidget.columnsFor(411f))
    HomeWidget.entries.forEach { assertEquals(it.span, it.spanIn(2)) }
  }

  @Test
  fun `da seicento dp le colonne sono quattro`() {
    assertEquals(2, HomeWidget.columnsFor(599f))
    assertEquals(4, HomeWidget.columnsFor(600f))
    assertEquals(4, HomeWidget.columnsFor(1180f))
  }

  @Test
  fun `su tablet le larghe fanno mezza riga, le compatte un quarto, l'orario tutta`() {
    assertEquals(2, HomeWidget.DAILY.spanIn(4))
    assertEquals(2, HomeWidget.NOWCAST.spanIn(4))
    assertEquals(1, HomeWidget.PRESSURE.spanIn(4))
    assertEquals(1, HomeWidget.AIR_QUALITY.spanIn(4))
    assertEquals(4, HomeWidget.HOURLY.spanIn(4))
  }
}
