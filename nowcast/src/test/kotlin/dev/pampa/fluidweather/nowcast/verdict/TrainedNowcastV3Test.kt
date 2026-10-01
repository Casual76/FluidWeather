package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.climatology.PooledPriors
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'artefatto v3 generato da `train-v3 emit`: forma, contratto, risorse. */
class TrainedNowcastV3Test {

  private fun parts(tier: ContextTier): Triple<DoubleArray, DoubleArray, Pair<Map<String, IntArray>, List<WindowCoefficients>>> = when (tier) {
    ContextTier.FRESH -> Triple(TrainedNowcastV3Fresh.means, TrainedNowcastV3Fresh.sds, TrainedNowcastV3Fresh.used to TrainedNowcastV3Fresh.windows)
    ContextTier.STALE -> Triple(TrainedNowcastV3Stale.means, TrainedNowcastV3Stale.sds, TrainedNowcastV3Stale.used to TrainedNowcastV3Stale.windows)
    ContextTier.NONE -> Triple(TrainedNowcastV3None.means, TrainedNowcastV3None.sds, TrainedNowcastV3None.used to TrainedNowcastV3None.windows)
    ContextTier.NONE_NOCLIMA -> Triple(TrainedNowcastV3NoneNoClima.means, TrainedNowcastV3NoneNoClima.sds, TrainedNowcastV3NoneNoClima.used to TrainedNowcastV3NoneNoClima.windows)
  }

  @Test
  fun `i nomi delle feature sono il contratto del v3`() {
    assertEquals(FeatureExtractorV3.names, TrainedNowcastV3.FEATURE_NAMES)
    assertEquals(FeatureExtractorV3.VERSION, TrainedNowcastV3.FEATURES_VERSION)
    assertTrue(Regex("v3-\\d{4}-\\d{2}-\\d{2}").matches(TrainedNowcastV3.VERSION))
    assertTrue(TrainedNowcastV3.FAMILY in ModelFamily.entries.map { it.name })
  }

  @Test
  fun `ogni tabella ha 42 medie, 42 deviazioni positive e cinque bag da 43 per finestra`() {
    for (tier in ContextTier.entries) {
      val (means, sds, rest) = parts(tier)
      val (used, windows) = rest
      assertEquals(FeatureExtractorV3.COUNT, means.size)
      assertEquals(FeatureExtractorV3.COUNT, sds.size)
      assertTrue(sds.all { it > 0 && !it.isNaN() })
      assertEquals(RainWindows.ALL.map { it.label }, windows.map { it.window })
      for (w in windows) {
        assertEquals(5, w.bags.size)
        assertTrue(w.bags.all { it.size == FeatureExtractorV3.COUNT + 1 && it.none { v -> v.isNaN() } })
        val free = used.getValue(w.window).toSet()
        assertTrue("$tier ${w.window}: nessuna colonna libera", free.isNotEmpty())
        // Fuori dalle colonne libere i pesi sono zero: il mascheramento e' nei numeri, non solo nel codice.
        for (bag in w.bags) for (c in 0 until FeatureExtractorV3.COUNT) if (c !in free) assertEquals("$tier ${w.window} $c", 0.0, bag[c + 1], 0.0)
      }
    }
  }

  @Test
  fun `i livelli senza contesto non vedono il contesto`() {
    for (tier in listOf(ContextTier.NONE, ContextTier.NONE_NOCLIMA)) {
      val used = parts(tier).third.first
      for ((window, columns) in used) {
        assertTrue("$tier $window", columns.none { it in 7..17 || it in FeatureExtractorV3.CONTEXT_AGE..FeatureExtractorV3.TEMPERATURE_TREND })
        assertTrue("$tier $window", columns.none { it in FeatureExtractorV3.PERSISTENCE until FeatureExtractorV3.PERSISTENCE + 3 })
      }
    }
  }

  @Test
  fun `il modello spedito si carica con la famiglia scelta e ogni risorsa torna con la sua impronta`() {
    val model = TieredNowcastModel.trained()
    assertNull("problema di caricamento: ${model.loadProblem}", model.loadProblem)
    assertEquals(ModelFamily.valueOf(TrainedNowcastV3.FAMILY), model.activeFamily)
    assertEquals(TrainedNowcastV3.VERSION, model.version)
    if (TrainedNowcastV3.FAMILY == ModelFamily.GBM.name) {
      assertEquals(TieredNowcastModel.RESOURCE_NAMES, TrainedNowcastV3.GBM_RESOURCES)
      for (name in TrainedNowcastV3.GBM_RESOURCES) {
        val bytes = javaClass.classLoader.getResourceAsStream(TieredNowcastModel.RESOURCE_DIR + name)!!.use { it.readBytes() }
        assertEquals(name, TrainedNowcastV3.GBM_SHA256.getValue(name), TieredNowcastModel.sha256(bytes))
      }
      assertTrue(TrainedNowcastV3.GBM_RESOURCES.sumOf { javaClass.classLoader.getResourceAsStream(TieredNowcastModel.RESOURCE_DIR + it)!!.use { s -> s.readBytes().size } } <= 1_500_000)
    } else {
      assertTrue(TrainedNowcastV3.GBM_RESOURCES.isEmpty())
    }
  }

  @Test
  fun `senza risorse il modello spedito ripiega sulla logistica`() {
    val model = TieredNowcastModel.trained { null }
    assertEquals(ModelFamily.LOGISTICA, model.activeFamily)
    if (TrainedNowcastV3.FAMILY == ModelFamily.GBM.name) assertNotNull(model.loadProblem) else assertNull(model.loadProblem)
  }

  @Test
  fun `il riferimento di tutti i posti si legge e copre le tre finestre`() {
    val pooled = PooledPriors.decode(TrainedNowcastV3.POOLED)
    assertNotNull(pooled)
    assertTrue(pooled!!.covers(RainWindows.ALL))
  }

  @Test
  fun `il modello spedito da' probabilita' sensate su un vettore tutto mancante`() {
    val model = TieredNowcastModel.trained()
    val empty = DoubleArray(FeatureExtractorV3.COUNT) { Double.NaN }
    for (tier in ContextTier.entries) {
      for (w in model.verdict(tier, empty).windows) {
        assertTrue("$tier ${w.window}: ${w.probability}", w.probability in 0.01..0.9)
      }
    }
  }
}
