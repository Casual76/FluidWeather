package dev.pampa.fluidweather.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regola della fase 24: il nowcast e' il barometro di QUESTO telefono, quindi su una localita'
 * lontana la sua tessera non c'e'. La regola sta in [HomeWidget.visibleIds] proprio per poterla
 * chiedere qui invece di leggerla dentro una griglia.
 */
class HomeWidgetVisibilityTest {

  private val ordine = HomeWidget.defaultOrder.map { it.id }

  @Test
  fun `dove sei ci sono tutte`() {
    assertEquals(ordine, HomeWidget.visibleIds(ordine, barometerApplies = true))
  }

  @Test
  fun `su una localita' lontana il nowcast sparisce`() {
    val visibili = HomeWidget.visibleIds(ordine, barometerApplies = false)

    assertTrue(HomeWidget.NOWCAST.id !in visibili)
    assertEquals(ordine.size - 1, visibili.size)
  }

  @Test
  fun `sparisce il nowcast, non l'ordine`() {
    // L'ordine e' una preferenza dell'utente: cambiare localita' non deve rimescolarlo. Qui il
    // nowcast e' stato spostato in mezzo, ed e' l'unica cosa che deve mancare.
    val mescolato = listOf(
      HomeWidget.HOURLY.id,
      HomeWidget.PRESSURE.id,
      HomeWidget.NOWCAST.id,
      HomeWidget.MOON.id,
    )

    assertEquals(
      listOf(HomeWidget.HOURLY.id, HomeWidget.PRESSURE.id, HomeWidget.MOON.id),
      HomeWidget.visibleIds(mescolato, barometerApplies = false),
    )
  }

  @Test
  fun `un ordine senza nowcast resta identico`() {
    // Chi ha nascosto il nowcast dalle impostazioni non deve vedere differenze fra qui e altrove.
    val senza = ordine.filterNot { it == HomeWidget.NOWCAST.id }

    assertEquals(senza, HomeWidget.visibleIds(senza, barometerApplies = false))
    assertEquals(senza, HomeWidget.visibleIds(senza, barometerApplies = true))
  }
}
