package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ObservedCondition
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il vocabolario delle osservazioni. Come l'occhio dell'utente entra nella verita' della pioggia
 * (bagnato vince, asciutto azzera il suo slot, solo entro 3 km) lo provano i test del giudice del
 * pannello, [TruthPanelSettlerTest].
 */
class ObservationTruthTest {

  @Test
  fun `il vocabolario delle osservazioni sa cosa e' pioggia`() {
    assertTrue(ObservedCondition.entries.filter { it.wet }.map { it.name }.containsAll(listOf("DRIZZLE", "RAIN", "SNOW", "THUNDERSTORM", "HAIL")))
    assertTrue(ObservedCondition.entries.filter { !it.wet }.map { it.name }.containsAll(listOf("CLEAR", "CLOUDY", "FOG")))
  }
}
