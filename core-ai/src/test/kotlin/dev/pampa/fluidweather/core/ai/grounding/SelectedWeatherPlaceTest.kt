package dev.pampa.fluidweather.core.ai.grounding

import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import org.junit.Assert.*
import org.junit.Test

class SelectedWeatherPlaceTest {
  private val here = ResolvedPlace("Here", null, 43.8, 11.2, "gps", 0, true, false, true)

  @Test fun `meter-scale GPS jitter and geocoder label changes preserve context`() {
    assertTrue(sameSelectedWeatherPlace(here, here.copy(latitude = 43.800009, name = "Street nearby")))
  }
  @Test fun `travel beyond the shared weather distance bound invalidates context`() {
    assertFalse(sameSelectedWeatherPlace(here, here.copy(latitude = 43.83)))
  }
  @Test fun `a loss of location or fresh fix cannot be masked by matching coordinates`() {
    assertFalse(sameSelectedWeatherPlace(here, null))
    assertFalse(sameSelectedWeatherPlace(here, here.copy(fromFreshFix = false)))
    assertFalse(sameSelectedWeatherPlace(here, here.copy(isGps = false)))
  }
  @Test fun `selected identity and timezone are still publication barriers`() {
    assertFalse(sameSelectedWeatherPlace(here, here.copy(placeId = 123)))
    assertFalse(sameSelectedWeatherPlace(here, here.copy(snapshotKey = "other")))
    assertFalse(sameSelectedWeatherPlace(here, here.copy(zoneId = "Asia/Tokyo")))
  }
  @Test fun `invalid coordinates fail closed`() {
    assertFalse(sameSelectedWeatherPlace(here, here.copy(latitude = Double.NaN)))
    assertFalse(sameSelectedWeatherPlace(here, here.copy(longitude = 181.0)))
  }
  @Test fun `saved place changes still require exact stable metadata`() {
    val saved = here.copy(isGps = false, isSaved = true)
    assertTrue(sameSelectedWeatherPlace(saved, saved.copy()))
    assertFalse(sameSelectedWeatherPlace(saved, saved.copy(latitude = saved.latitude + .000001)))
  }
}
