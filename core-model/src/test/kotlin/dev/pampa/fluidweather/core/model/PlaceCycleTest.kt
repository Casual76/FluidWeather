package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceCycleTest {

  private val places = listOf(
    Place.gps(),
    Place(10, "Sesto Fiorentino", "Toscana, Italia", 43.83, 11.20),
    Place(20, "Reykjavik", "Islanda", 64.15, -21.94),
  )

  @Test
  fun `lo swipe scorre in avanti e torna a capo`() {
    assertEquals(10L, PlaceCycle.next(places, Place.GPS_ID)!!.id)
    assertEquals(20L, PlaceCycle.next(places, 10)!!.id)
    assertEquals(Place.GPS_ID, PlaceCycle.next(places, 20)!!.id)
  }

  @Test
  fun `indietro fa il giro opposto`() {
    assertEquals(20L, PlaceCycle.previous(places, Place.GPS_ID)!!.id)
    assertEquals(Place.GPS_ID, PlaceCycle.previous(places, 10)!!.id)
  }

  @Test
  fun `un id sconosciuto riparte dal GPS, un elenco vuoto risponde null`() {
    assertEquals(10L, PlaceCycle.next(places, 999)!!.id)
    assertNull(PlaceCycle.next(emptyList(), Place.GPS_ID))
  }
}
