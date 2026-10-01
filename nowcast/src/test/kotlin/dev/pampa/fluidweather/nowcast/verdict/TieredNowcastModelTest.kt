package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TieredNowcastModelTest {

  private val count = FeatureExtractorV3.COUNT
  private val trend = FeatureExtractorV3.names.indexOf("tendenza-3h")
  private val cloud = FeatureExtractorV3.names.indexOf("copertura")

  /** Una tabella per livello: intercetta diversa per livello, un solo coefficiente libero (tendenza), cinque bag. */
  private fun table(tier: ContextTier): NowcastModel {
    val windows = RainWindows.ALL.map { window ->
      WindowCoefficients(
        window.label,
        List(5) { b ->
          DoubleArray(count + 1).also {
            it[0] = -2.0 + tier.ordinal * 0.5 + (b - 2) * 0.1
            it[trend + 1] = -1.0
          }
        },
      )
    }
    return NowcastModel(DoubleArray(count), DoubleArray(count) { 1.0 }, windows, FeatureExtractorV3.names)
  }

  private val tables = ContextTier.entries.associateWith { table(it) }

  /** Alberi: uno stump sulla copertura (+0,4 sopra 50%), base diversa per livello. */
  private fun trees(tier: ContextTier, window: Int, featureCount: Int = count): TreeEnsemble = TreeEnsemble(
    featureCount = featureCount,
    tier = tier.ordinal,
    window = window,
    offsetFeature = -1,
    baseScore = -1.0 - tier.ordinal * 0.25,
    offsetMean = 0.0,
    trees = listOf(
      Tree(
        flags = byteArrayOf(0, Tree.LEAF.toByte(), Tree.LEAF.toByte()),
        values = floatArrayOf(0f, -0.1f, 0.4f),
        features = intArrayOf(cloud, -1, -1),
        thresholds = floatArrayOf(50f, Float.NaN, Float.NaN),
        rights = intArrayOf(2, -1, -1),
      ),
    ),
  )

  private val bytes: Map<String, ByteArray> = ContextTier.entries.flatMap { tier ->
    RainWindows.ALL.indices.map { w -> TieredNowcastModel.resourceName(tier, w) to trees(tier, w).encode() }
  }.toMap()

  private val sha = bytes.mapValues { TieredNowcastModel.sha256(it.value) }

  private fun model(
    family: ModelFamily,
    source: Map<String, ByteArray> = bytes,
    expected: Map<String, String>? = sha,
  ) = TieredNowcastModel.assemble("v3-2026-01-01", family, tables, { source[it] }, expected, pooled = null)

  private fun features(trendValue: Double = -1.0, cloudValue: Double = 80.0) = DoubleArray(count) { Double.NaN }.also {
    it[trend] = trendValue
    it[cloud] = cloudValue
  }

  private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

  @Test
  fun `ogni livello parla con la sua tabella`() {
    val m = model(ModelFamily.LOGISTICA)
    assertEquals(ModelFamily.LOGISTICA, m.activeFamily)
    val x = features()
    for (tier in ContextTier.entries) {
      val expected = (0 until 5).map { b -> sigmoid(-2.0 + tier.ordinal * 0.5 + (b - 2) * 0.1 + 1.0) }.average()
      assertEquals(expected, m.verdict(tier, x).forWindow("0-1h")!!.probability, 1e-12)
    }
    val fresh = m.verdict(ContextTier.FRESH, x).forWindow("1-3h")!!.probability
    val none = m.verdict(ContextTier.NONE, x).forWindow("1-3h")!!.probability
    assertTrue(none > fresh)
  }

  @Test
  fun `gli alberi parlano quando ci sono tutti e tornano con l'impronta`() {
    val m = model(ModelFamily.GBM)
    assertEquals(ModelFamily.GBM, m.activeFamily)
    assertNull(m.loadProblem)
    val verdict = m.verdict(ContextTier.STALE, features(cloudValue = 80.0)).forWindow("3-6h")!!
    assertEquals(sigmoid(-1.25 + 0.4), verdict.probability, 1e-6)
    // Il fattore e' la copertura, con il suo merito di cammino (0,4 - 0).
    assertEquals("copertura", verdict.topFactors.single().name)
    assertEquals(0.4, verdict.topFactors.single().contribution, 1e-6)
    // La banda: la distanza del comitato logistico, spostata sugli alberi.
    assertTrue(verdict.probabilityLow < verdict.probability && verdict.probability < verdict.probabilityHigh)
  }

  @Test
  fun `le colonne mascherate non diventano mai fattori`() {
    val m = model(ModelFamily.LOGISTICA)
    val x = DoubleArray(count) { 3.0 } // tutto presente e lontano dalla media
    for (tier in ContextTier.entries) {
      for (window in m.verdict(tier, x).windows) {
        assertTrue(window.topFactors.all { it.name == "tendenza-3h" })
      }
    }
  }

  @Test
  fun `un'impronta che non torna fa parlare la logistica, in tutti i livelli`() {
    val tampered = bytes.toMutableMap()
    val name = TieredNowcastModel.resourceName(ContextTier.NONE, 2)
    tampered[name] = tampered.getValue(name).copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    val m = model(ModelFamily.GBM, source = tampered)
    assertEquals(ModelFamily.LOGISTICA, m.activeFamily)
    assertEquals(ModelFamily.GBM, m.requestedFamily)
    assertNotNull(m.loadProblem)
    assertTrue(m.loadProblem!!.contains(name))
    for (tier in ContextTier.entries) assertTrue(m.forTier(tier) is NowcastModel)
  }

  @Test
  fun `un file mancante, troncato o di un altro contratto fa parlare la logistica`() {
    val missing = bytes - TieredNowcastModel.resourceName(ContextTier.FRESH, 0)
    assertEquals(ModelFamily.LOGISTICA, model(ModelFamily.GBM, source = missing).activeFamily)

    val truncatedName = TieredNowcastModel.resourceName(ContextTier.STALE, 1)
    val truncated = bytes + (truncatedName to bytes.getValue(truncatedName).copyOf(20))
    val truncatedSha = sha + (truncatedName to TieredNowcastModel.sha256(truncated.getValue(truncatedName)))
    val t = model(ModelFamily.GBM, source = truncated, expected = truncatedSha)
    assertEquals(ModelFamily.LOGISTICA, t.activeFamily)
    assertNotNull(t.loadProblem)

    val otherName = TieredNowcastModel.resourceName(ContextTier.NONE_NOCLIMA, 0)
    val other = bytes + (otherName to trees(ContextTier.NONE_NOCLIMA, 0, featureCount = 20).let {
      TreeEnsemble(20, it.tier, it.window, -1, it.baseScore, 0.0, listOf(Tree(byteArrayOf(Tree.LEAF.toByte()), floatArrayOf(0f), intArrayOf(-1), floatArrayOf(Float.NaN), intArrayOf(-1)))).encode()
    })
    val otherSha = sha + (otherName to TieredNowcastModel.sha256(other.getValue(otherName)))
    val o = model(ModelFamily.GBM, source = other, expected = otherSha)
    assertEquals(ModelFamily.LOGISTICA, o.activeFamily)
    assertTrue(o.loadProblem!!.contains("20"))

    // Un file al posto di un altro (livello sbagliato nell'intestazione) non passa.
    val swapped = bytes + (TieredNowcastModel.resourceName(ContextTier.FRESH, 0) to bytes.getValue(TieredNowcastModel.resourceName(ContextTier.STALE, 0)))
    val swappedSha = swapped.mapValues { TieredNowcastModel.sha256(it.value) }
    assertEquals(ModelFamily.LOGISTICA, model(ModelFamily.GBM, source = swapped, expected = swappedSha).activeFamily)
  }

  @Test
  fun `la famiglia si puo' forzare sulla logistica, non sugli alberi che non ci sono`() {
    val gbm = model(ModelFamily.GBM)
    assertEquals(ModelFamily.LOGISTICA, gbm.withFamily(ModelFamily.LOGISTICA).activeFamily)
    val logistic = model(ModelFamily.LOGISTICA)
    try {
      logistic.withFamily(ModelFamily.GBM)
      throw AssertionError("doveva rifiutare")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.isNotEmpty())
    }
  }

  @Test
  fun `il verdetto vuole le quarantadue feature e usa le soglie del livello`() {
    val m = model(ModelFamily.LOGISTICA)
    try {
      m.verdict(ContextTier.FRESH, DoubleArray(20))
      throw AssertionError("doveva rifiutare")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("42"))
    }
    val v = m.verdict(ContextTier.NONE_NOCLIMA, features(trendValue = -5.0))
    assertEquals(AlertThresholds.DEFAULT.levelOf(v.windows), v.level)
    assertFalse(m.floorsFor(ContextTier.NONE).whenRainingNow.isNotEmpty())
  }
}
