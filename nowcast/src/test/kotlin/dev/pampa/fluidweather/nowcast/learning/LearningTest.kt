package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTest {

  private val random = Random(7)

  /** Coppie in cui la frequenza vera per una probabilita' detta p e' [truth](p). */
  private fun samples(n: Int, truth: (Double) -> Double): List<CalibrationSample> = List(n) {
    val p = 0.05 + 0.9 * random.nextDouble()
    CalibrationSample(p, random.nextDouble() < truth(p))
  }

  @Test
  fun `dati gia' calibrati lasciano la mappa vicina all'identita'`() {
    val params = PlattCalibration.fit(samples(2000) { it })!!
    assertEquals(1.0, params.a, 0.15)
    assertEquals(0.0, params.b, 0.15)
    assertEquals(0.6, params.apply(0.6), 0.05)
  }

  @Test
  fun `un modello troppo sicuro di se' viene riportato alla frequenza vera`() {
    // Quando dice 60%, piove il 30% delle volte: la curva vera e' p^2 circa.
    val params = PlattCalibration.fit(samples(3000) { it * it })!!
    assertEquals(0.36, params.apply(0.6), 0.08)
    assertEquals(0.09, params.apply(0.3), 0.06)
    assertTrue(params.apply(0.9) > 0.7)
  }

  @Test
  fun `sotto trenta verifiche, o senza entrambi gli esiti, niente ricalibrazione`() {
    assertNull(PlattCalibration.fit(samples(20) { it }))
    assertNull(PlattCalibration.fit(List(50) { CalibrationSample(0.5, true) }))
  }

  // ------------------------------------------------------------------------- analoghi

  private fun features(trend3: Double, hourSin: Double = 0.0): DoubleArray =
    DoubleArray(FeatureExtractor.names.size) { Double.NaN }.also {
      it[0] = trend3 * 0.9
      it[1] = trend3
      it[2] = trend3 * 0.8
      it[3] = trend3 * 0.5
      it[4] = 0.0
      it[6] = 0.1
      it[14] = hourSin
      it[15] = 0.0
    }

  @Test
  fun `gli analoghi trovano le situazioni simili e raccontano com'erano finite`() {
    val means = TrainedNowcastV1.means
    val sds = TrainedNowcastV1.sds
    val day = 86_400_000L
    // Trenta episodi in caduta (piove nel 80% dei casi a 1-3h), trenta stabili (piove nel 10%).
    val cases = buildList {
      repeat(30) { i -> add(AnalogCase(i * day, features(-1.2), mapOf("1-3h" to (i % 5 != 0)))) }
      repeat(30) { i -> add(AnalogCase(100 * day + i * day, features(0.05), mapOf("1-3h" to (i % 10 == 0)))) }
      // Duplicati dello stesso episodio: a un quarto d'ora, non devono contare due volte.
      repeat(3) { i -> add(AnalogCase(i * day + 15 * 60_000L, features(-1.2), mapOf("1-3h" to true))) }
    }
    val neighbours = Analogs.nearest(features(-1.1), cases, means, sds, k = 20)
    assertEquals(20, neighbours.size)
    // Nessuna coppia di vicini nello stesso episodio di sei ore.
    assertTrue(neighbours.map { it.first.issuedAtMillis }.sorted().zipWithNext().all { (a, b) -> b - a >= Analogs.EPISODE_MILLIS })
    val summary = Analogs.summarize(neighbours, "1-3h")
    assertTrue("frequenza ${summary.frequency}", summary.frequency > 0.6)

    val calm = Analogs.summarize(Analogs.nearest(features(0.0), cases, means, sds, k = 20), "1-3h")
    assertTrue("frequenza calma ${calm.frequency}", calm.frequency < 0.3)

    // La fusione pesa gli analoghi N/(N+40): con 20 vicini, un terzo.
    val blended = Analogs.blend(0.20, summary)
    assertTrue(blended > 0.20 && blended < summary.frequency)
    assertEquals(0.20, Analogs.blend(0.20, null), 1e-9)
  }

  @Test
  fun `il motore applica ricalibrazione e analoghi e ricalcola il livello`() {
    val engine = NowcastEngine.trained()
    val raw = NowcastModel.trained().verdict(features(-1.5))
    val nothingLearned = engine.evaluate(features(-1.5), LearningState.EMPTY)
    assertEquals(raw, nothingLearned.verdict)
    assertTrue(nothingLearned.recalibrated.isEmpty())

    // Una mappa che spinge tutto verso il basso: il livello non puo' che scendere o restare.
    val timid = LearningState(platt = mapOf("0-1h" to PlattParams(1.0, -2.0), "1-3h" to PlattParams(1.0, -2.0), "3-6h" to PlattParams(1.0, -2.0)))
    val explained = engine.evaluate(features(-1.5), timid)
    assertEquals(setOf("0-1h", "1-3h", "3-6h"), explained.recalibrated)
    explained.verdict.windows.zip(raw.windows).forEach { (calibrated, original) ->
      assertTrue(calibrated.probability < original.probability)
      assertTrue(calibrated.probabilityLow <= calibrated.probability && calibrated.probability <= calibrated.probabilityHigh + 1e-9)
    }
    assertTrue(explained.verdict.level.ordinal <= raw.level.ordinal)
    assertEquals(raw, explained.rawVerdict)
    assertTrue(abs(explained.verdict.windows[1].probability - PlattParams(1.0, -2.0).apply(raw.windows[1].probability)) < 1e-9)
    assertTrue(explained.verdict.level in AlertLevel.entries)
  }
}
