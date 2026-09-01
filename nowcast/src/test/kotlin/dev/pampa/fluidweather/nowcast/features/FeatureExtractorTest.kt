package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {

  private val start = 1_700_000_000_000L
  private val pipeline = CleaningPipeline()

  private fun series(hours: Int, pressureAt: (Double) -> Double): List<PressureSample> =
    (0..hours * 4).map { i ->
      val h = i * 0.25
      PressureSample(
        timestampMillis = start + (h * 3_600_000).toLong(),
        pressureHpa = pressureAt(h),
        source = SampleSource.PERIODIC,
      )
    }

  private fun index(name: String) = FeatureExtractor.names.indexOf(name)

  @Test
  fun `le tendenze multi-orizzonte leggono la rampa`() {
    val cleaning = pipeline.process(series(24) { h -> 1013.0 - h * 0.5 })
    val features = FeatureExtractor.extract(cleaning, null, null, start + 24 * 3_600_000L)!!

    assertEquals(-0.5, features[index("tendenza-1h")], 0.15)
    assertEquals(-0.5, features[index("tendenza-3h")], 0.1)
    assertEquals(-0.5, features[index("tendenza-12h")], 0.05)
    // Rampa costante: niente accelerazione.
    assertEquals(0.0, features[index("accelerazione-3h")], 0.1)
  }

  @Test
  fun `un fronte che accelera si vede nell'accelerazione`() {
    // Piatta per 18 ore, poi giu' sempre piu' in fretta (quadratica).
    val cleaning = pipeline.process(
      series(24) { h -> if (h < 18) 1013.0 else 1013.0 - (h - 18) * (h - 18) * 0.1 },
    )
    val features = FeatureExtractor.extract(cleaning, null, null, start + 24 * 3_600_000L)!!

    assertTrue(features[index("accelerazione-3h")] < -0.15)
  }

  @Test
  fun `senza contesto le feature sinottiche sono NaN, non zero`() {
    val cleaning = pipeline.process(series(24) { 1013.0 })
    val features = FeatureExtractor.extract(cleaning, null, null, start + 24 * 3_600_000L)!!

    assertTrue(features[index("umidita'")].isNaN())
    assertTrue(features[index("pioggia-ultima-ora")].isNaN())
    assertTrue(features[index("anomalia-livello")].isNaN())
  }

  @Test
  fun `col contesto le feature sinottiche ci sono`() {
    val cleaning = pipeline.process(series(24) { 1013.0 })
    val context = NowcastContext(
      relativeHumidityPercent = 85.0,
      dewPointSpreadC = 1.5,
      windDirectionDeg = 10.0,
      windDirectionDeg3hAgo = 350.0,
      rainLastHourMm = 0.4,
    )
    val features =
      FeatureExtractor.extract(cleaning, context, normalHpa = 1015.0, nowMillis = start + 24 * 3_600_000L)!!

    assertEquals(85.0, features[index("umidita'")], 1e-9)
    // La rotazione avvolge i 360: da 350 a 10 sono +20 gradi, non -340.
    assertEquals(20.0, features[index("rotazione-vento-3h")], 1e-9)
    assertEquals(-2.0, features[index("anomalia-livello")], 0.1)
  }

  @Test
  fun `con poca storia non si estrae niente`() {
    val cleaning = pipeline.process(series(6) { 1013.0 })
    assertNull(FeatureExtractor.extract(cleaning, null, null, start + 6 * 3_600_000L))
  }

  @Test
  fun `l'ora entra come seno e coseno, mai come gradino`() {
    val cleaning = pipeline.process(series(24) { 1013.0 })
    val features = FeatureExtractor.extract(cleaning, null, null, start + 24 * 3_600_000L)!!
    val sin = features[index("ora-sin")]
    val cos = features[index("ora-cos")]
    assertEquals(1.0, sin * sin + cos * cos, 1e-9)
  }
}
