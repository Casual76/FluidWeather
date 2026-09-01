package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowcastModelTest {

  private val featureCount = FeatureExtractor.names.size

  private fun model(vararg bags: DoubleArray): NowcastModel = NowcastModel(
    featureMeans = DoubleArray(featureCount),
    featureSds = DoubleArray(featureCount) { 1.0 },
    windows = listOf(WindowCoefficients("1-3h", bags.toList())),
  )

  private fun weights(intercept: Double, vararg pairs: Pair<String, Double>): DoubleArray {
    val w = DoubleArray(featureCount + 1)
    w[0] = intercept
    for ((name, value) in pairs) w[FeatureExtractor.names.indexOf(name) + 1] = value
    return w
  }

  @Test
  fun `la probabilita' e' la sigmoide del punteggio, a mano`() {
    val m = model(weights(-1.0, "tendenza-3h" to -2.0))
    val features = DoubleArray(featureCount) { Double.NaN }
    features[FeatureExtractor.names.indexOf("tendenza-3h")] = -1.0 // caduta di 1 hPa/h

    val verdict = m.verdict(features).forWindow("1-3h")!!
    // punteggio = -1 + (-2)(-1) = 1 -> sigmoide = 0,731
    assertEquals(1.0 / (1.0 + exp(-1.0)), verdict.probability, 1e-9)
  }

  @Test
  fun `le feature mancanti sono neutre e non compaiono fra i fattori`() {
    val m = model(weights(0.0, "tendenza-3h" to -2.0, "umidita'" to 1.5))
    val features = DoubleArray(featureCount) { Double.NaN } // tutto mancante

    val verdict = m.verdict(features).forWindow("1-3h")!!
    assertEquals(0.5, verdict.probability, 1e-9)
    assertTrue(verdict.topFactors.isEmpty())
  }

  @Test
  fun `i fattori sono ordinati per peso e nominati`() {
    val m = model(weights(0.0, "tendenza-3h" to -2.0, "umidita'" to 0.5))
    val features = DoubleArray(featureCount) { Double.NaN }
    features[FeatureExtractor.names.indexOf("tendenza-3h")] = -1.5
    features[FeatureExtractor.names.indexOf("umidita'")] = 1.0

    val factors = m.verdict(features).forWindow("1-3h")!!.topFactors
    assertEquals("tendenza-3h", factors.first().name)
    assertEquals(3.0, factors.first().contribution, 1e-9)
    assertEquals("umidita'", factors[1].name)
  }

  @Test
  fun `il disaccordo del comitato e' la banda`() {
    val m = model(
      weights(0.0),
      weights(1.0),
      weights(-1.0),
    )
    val verdict = m.verdict(DoubleArray(featureCount) { Double.NaN }).forWindow("1-3h")!!

    assertTrue(verdict.probabilityLow < verdict.probability)
    assertTrue(verdict.probabilityHigh > verdict.probability)
    assertEquals(0.5, verdict.probability, 0.01)
  }

  @Test
  fun `i livelli seguono le soglie dichiarate`() {
    fun levelFor(probability: Double): AlertLevel {
      val intercept = -kotlin.math.ln(1 / probability - 1)
      val m = NowcastModel(
        DoubleArray(featureCount),
        DoubleArray(featureCount) { 1.0 },
        listOf(WindowCoefficients("1-3h", listOf(weights(intercept)))),
      )
      return m.verdict(DoubleArray(featureCount) { Double.NaN }).level
    }

    assertEquals(AlertLevel.QUIETE, levelFor(0.15))
    assertEquals(AlertLevel.SORVEGLIANZA, levelFor(0.45))
    assertEquals(AlertLevel.ALLERTA, levelFor(0.7))
  }
}
