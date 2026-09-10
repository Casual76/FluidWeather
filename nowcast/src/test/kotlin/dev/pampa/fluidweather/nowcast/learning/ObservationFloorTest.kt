package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.WindowCoefficients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il caso che ha fatto nascere tutto questo: pioveva da due ore e la tessera diceva "fra 1 e 3
 * ore", con percentuali basse. Il motore non aveva torto sul suo mestiere — nessuno gli aveva
 * mai detto che stava piovendo.
 */
class ObservationFloorTest {

  private val featureCount = FeatureExtractor.names.size

  /** Un modello che dice sempre la stessa cosa, cosi' si misura il pavimento e non il modello. */
  private fun engine(probability: Double): NowcastEngine {
    val intercept = Math.log(probability / (1 - probability))
    val bag = DoubleArray(featureCount + 1).also { it[0] = intercept }
    val model = NowcastModel(
      featureMeans = DoubleArray(featureCount),
      featureSds = DoubleArray(featureCount) { 1.0 },
      windows = listOf("0-1h", "1-3h", "3-6h").map { WindowCoefficients(it, listOf(bag)) },
    )
    return NowcastEngine(model, DoubleArray(featureCount), DoubleArray(featureCount) { 1.0 })
  }

  private fun features() = DoubleArray(featureCount) { 0.0 }

  @Test
  fun `sta piovendo, e la finestra a breve non puo' piu' stare sotto la persistenza misurata`() {
    val explained = engine(probability = 0.08).evaluate(
      features(),
      LearningState.EMPTY,
      RainObservation.raining(intensityMmPerHour = 3.0, source = "quarto d'ora"),
    )

    assertEquals(0.65, explained.verdict.forWindow("0-1h")!!.probability, 1e-9)
    assertEquals(0.60, explained.verdict.forWindow("1-3h")!!.probability, 1e-9)
    assertEquals(0.50, explained.verdict.forWindow("3-6h")!!.probability, 1e-9)
    assertEquals(setOf("0-1h", "1-3h", "3-6h"), explained.observed)
    // E il livello segue: con la pioggia addosso, "quiete" era la parola sbagliata.
    assertEquals(AlertLevel.ALLERTA, explained.verdict.level)
  }

  @Test
  fun `il pavimento alza e basta - un modello gia' convinto non viene abbassato`() {
    val explained = engine(probability = 0.92).evaluate(
      features(),
      LearningState.EMPTY,
      RainObservation.raining(intensityMmPerHour = 0.4, source = "radar"),
    )

    assertEquals(0.92, explained.verdict.forWindow("0-1h")!!.probability, 1e-6)
    assertTrue(explained.observed.isEmpty())
  }

  @Test
  fun `non vedere pioggia non autorizza a escluderla`() {
    val dry = engine(probability = 0.45).evaluate(features(), LearningState.EMPTY, RainObservation.dry("radar"))
    val blind = engine(probability = 0.45).evaluate(features(), LearningState.EMPTY)

    assertEquals(blind.verdict, dry.verdict)
    assertTrue(dry.observed.isEmpty())
    assertFalse(dry.observation!!.rainingNow)
  }

  @Test
  fun `il verdetto grezzo resta quello del modello, qualunque cosa si veda`() {
    // La pagina mostra i tre numeri: modello, ricalibrato, corretto. Il primo non deve mentire.
    val explained = engine(probability = 0.08).evaluate(
      features(),
      LearningState.EMPTY,
      RainObservation.raining(intensityMmPerHour = 3.0, source = "quarto d'ora"),
    )

    assertEquals(0.08, explained.rawVerdict.forWindow("0-1h")!!.probability, 1e-6)
  }
}
