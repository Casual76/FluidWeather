package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.Place
import org.junit.Assert.*
import org.junit.Test

class WidgetPlaceTargetTest {
  private val saved = Place(42, "Saved", null, 44.0, 11.0)
  @Test fun savedIdentityMustExistInRepository() {
    assertEquals(42L, WidgetPlaceTarget.resolve(42, listOf(Place.gps(), saved)))
    assertEquals(Place.GPS_ID, WidgetPlaceTarget.resolve(42, listOf(Place.gps())))
    assertEquals(Place.GPS_ID, WidgetPlaceTarget.resolve(Long.MAX_VALUE, emptyList()))
  }
  @Test fun explicitGpsAndLegacyHaveDifferentMeaning() {
    assertEquals(Place.GPS_ID, WidgetPlaceTarget.resolve(Place.GPS_ID, listOf(saved)))
    assertNull(WidgetPlaceTarget.resolve(null, listOf(saved)))
  }
}
