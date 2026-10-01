package dev.pampa.fluidweather.strings

import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.learning.PlattStatus
import dev.pampa.fluidweather.nowcast.learning.PlattVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LabelsTest {

  @Test
  fun `ogni feature del modello ha una parola`() {
    FeatureExtractorV3.names.forEach { name ->
      assertNotEquals("feature senza etichetta: $name", R.string.feature_unknown, featureLabelRes(name))
    }
  }

  @Test
  fun `le condizioni note hanno un'etichetta e l'ignota no`() {
    WeatherKind.entries.filter { it != WeatherKind.UNKNOWN }.forEach { kind ->
      assertNotEquals(0, kind.labelRes())
    }
    assertNull(WeatherKind.UNKNOWN.labelRes())
    assertEquals(R.string.kind_snow, WeatherKind.HEAVY_SNOW.precipitationNounRes())
    assertEquals(R.string.kind_rain, null.precipitationNounRes())
  }

  @Test
  fun `ogni riga locale della classifica pioggia ha una parola, e un provider vero no`() {
    val locali = setOf(RainBoardIds.BAROMETER, RainBoardIds.BAROMETER_SOLO) + RainBoardIds.REFERENCES
    locali.forEach { id -> assertNotNull("riga senza etichetta: $id", rainRowLabelRes(id)) }
    assertEquals(R.string.your_barometer, rainRowLabelRes(RainBoardIds.BAROMETER))
    // I provider hanno la loro etichetta nel registro: di qui passa solo chi non ce l'ha.
    assertNull(rainRowLabelRes("open-meteo"))
    assertNull(rainRowLabelRes("met-norway"))
  }

  @Test
  fun `le finestre del verdetto trovano titolo e frase`() {
    assertEquals(R.string.window_1_3, windowLabelRes("1-3h"))
    assertEquals(R.string.window_phrase_3_6, windowPhraseRes("3-6h"))
    assertEquals(R.string.window_phrase_other, windowPhraseRes("6-12h"))
  }

  @Test
  fun `ogni livello di contesto, variante di Platt e stato di una mappa ha una parola`() {
    ContextTier.entries.forEach { assertNotEquals(0, it.labelRes()) }
    assertEquals(ContextTier.entries.size, ContextTier.entries.map { it.labelRes() }.toSet().size)
    PlattVariant.entries.forEach { assertNotEquals(0, it.labelRes()) }
    PlattStatus.entries.forEach { assertNotNull("stato senza etichetta: $it", plattStatusLabelRes(it.name)) }
    // Un nome scritto da una versione piu' nuova non rompe la pagina: niente frase.
    assertNull(plattStatusLabelRes("STATO_DAL_FUTURO"))
  }
}
