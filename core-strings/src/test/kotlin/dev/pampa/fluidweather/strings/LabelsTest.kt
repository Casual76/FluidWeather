package dev.pampa.fluidweather.strings

import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelsTest {

  @Test
  fun `ogni feature del modello ha una parola`() {
    FeatureExtractor.names.forEach { name ->
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
  fun `le finestre del verdetto trovano titolo e frase`() {
    assertEquals(R.string.window_1_3, windowLabelRes("1-3h"))
    assertEquals(R.string.window_phrase_3_6, windowPhraseRes("3-6h"))
    assertEquals(R.string.window_phrase_other, windowPhraseRes("6-12h"))
  }
}
