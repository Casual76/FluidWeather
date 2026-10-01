package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.TreeEnsemble
import dev.pampa.fluidweather.nowcast.verdict.WindowCoefficients
import dev.pampa.fluidweather.testbench.tiers.TierHalf
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La logistica e gli alberi del v3 su un mondo finto: righe FRESH con una tendenza che conta, un'ancora
 * (la persistenza, in log-odds) che conta con coefficiente uno, e una colonna che quando manca (NaN) vuol
 * dire pioggia.
 */
class V3TrainersTest {

  private val trend = 1
  private val cloud = 10
  private val anchor = FeatureExtractorV3.PERSISTENCE
  private val columns = intArrayOf(trend, cloud, anchor)

  private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

  /** Righe sintetiche: y ~ Bernoulli(sigmoid(ancora + 1,2 tendenza + 1,5 [copertura NaN] - 0,5)). */
  private fun rows(n: Int, seed: Long, dayOffset: Int = 0): V3Rows {
    val random = SplittableRandom(seed)
    val count = FeatureExtractorV3.COUNT
    val features = FloatArray(n * count) { Float.NaN }
    val panel = ByteArray(n * 3) { -1 }
    val t0 = LongArray(n)
    for (i in 0 until n) {
      val t = random.nextGaussian()
      val a = -1.5 + random.nextGaussian()
      val missing = random.nextDouble() < 0.2
      features[i * count + trend] = t.toFloat()
      features[i * count + cloud] = if (missing) Float.NaN else (random.nextDouble() * 100).toFloat()
      features[i * count + anchor] = a.toFloat()
      val p = sigmoid(a + 1.2 * t + (if (missing) 1.5 else 0.0) - 0.5)
      panel[i * 3] = if (random.nextDouble() < p) 1 else 0
      t0[i] = (dayOffset + i / 24L) * 86_400_000L + (i % 24) * 3_600_000L
    }
    return V3Rows(
      listOf("sesto-fiorentino", "singapore"), features, panel, panel.copyOf(),
      ShortArray(n) { (it % 2).toShort() }, ByteArray(n) { ContextTier.FRESH.ordinal.toByte() }, t0, t0.copyOf(),
      ByteArray(n) { 3 },
    )
  }

  private val trainRows = rows(6000, 1)
  private val validationRows = rows(3000, 2, dayOffset = 1000)
  private val std = TierStandardization.fit(trainRows, ContextTier.FRESH)
  private val train = CellDesign.of(trainRows, ContextTier.FRESH, 0, columns.sortedArray(), std, withHalves = false)
  private val validation = CellDesign.of(validationRows, ContextTier.FRESH, 0, columns.sortedArray(), std, withHalves = true)
  private val validationAll = IntArray(validation.n) { it }

  // ------------------------------------------------------------------ logistica

  @Test
  fun `la tabella esportata ha zeri fuori dalle colonne e riproduce il punteggio della stima`() {
    val fit = LogisticV3.fit(train, train.offsets(anchor), train.weights(1.0), 1e-3)
    assertTrue(fit.converged)
    val full = LogisticV3.toFullWeights(fit.beta, train.columns, anchor, std)
    for (c in 0 until FeatureExtractorV3.COUNT) if (c !in columns) assertEquals(0.0, full[c + 1], 0.0)
    val model = NowcastModel(std.means, std.sds, listOf(WindowCoefficients("0-1h", listOf(full))), FeatureExtractorV3.names)
    for (row in listOf(0, 17, 999, 4321)) {
      val expected = sigmoid(LogisticV3.score(train, row, fit.beta, train.offsets(anchor)[row]))
      assertEquals(expected, model.verdict(train.fullRow(row)).windows.single().probability, 1e-5)
    }
  }

  @Test
  fun `con la penalita' enorme l'ancora e' la baseline piu' una costante`() {
    val fit = LogisticV3.fit(train, train.offsets(anchor), train.weights(1.0), 1e6)
    for (j in 1 until fit.beta.size) assertEquals(0.0, fit.beta[j], 1e-4)
    val offsets = train.offsets(anchor)
    for (row in 0 until 50) assertEquals(offsets[row] + fit.beta[0], LogisticV3.score(train, row, fit.beta, offsets[row]), 1e-4)
  }

  @Test
  fun `la stima ritrova i coefficienti veri`() {
    val fit = LogisticV3.fit(train, train.offsets(anchor), train.weights(1.0), 1e-5)
    val full = LogisticV3.toFullWeights(fit.beta, train.columns, anchor, std)
    // In scala grezza: tendenza 1,2 per hPa/h; l'ancora ha gia' coefficiente uno (sd assorbita), quindi il suo peso libero e' ~0.
    assertEquals(1.2, full[trend + 1] / std.sds[trend], 0.15)
    assertEquals(1.0, full[anchor + 1] / std.sds[anchor], 0.15)
  }

  @Test
  fun `i bag per blocchi di giorni sono deterministici`() {
    val a = LogisticV3.blockBootstrap(train, 42)
    val b = LogisticV3.blockBootstrap(train, 42)
    val c = LogisticV3.blockBootstrap(train, 43)
    assertArrayEquals(a, b, 0.0)
    assertFalse(a.contentEquals(c))
    // Le righe dello stesso blocco pesano uguale.
    for (i in 0 until train.n) for (j in i + 1 until minOf(train.n, i + 30)) if (train.block[i] == train.block[j]) assertEquals(a[i], a[j], 0.0)
    val f1 = LogisticV3.fit(train, train.offsets(anchor), train.weights(1.0, a), 1e-3)
    val f2 = LogisticV3.fit(train, train.offsets(anchor), train.weights(1.0, b), 1e-3)
    assertArrayEquals(f1.beta, f2.beta, 0.0)
  }

  @Test
  fun `il peso fuori Europa zero toglie le righe fuori Europa`() {
    val w = train.weights(0.0)
    for (i in 0 until train.n) assertEquals(train.europe[i], w[i] > 0)
    assertEquals(train.europe.count { it }.toDouble(), w.sum(), 1e-6)
  }

  // ------------------------------------------------------------------ alberi

  private fun gbm(config: GbmConfig): Triple<GbmV3Result, GbmBins, ByteArray> {
    val bins = GbmBins.of(train)
    val trainBins = bins.bin(train)
    val validationBins = bins.bin(validation)
    val result = GbmV3.train(train, trainBins, validation, validationBins, validationAll, train.weights(1.0), anchor, config, bins.edgeCounts)
    return Triple(result, bins, GbmV3.export(result, ContextTier.FRESH.ordinal, 0, train.columns, bins).encode())
  }

  private val config = GbmConfig(depth = 3, learningRate = 0.1, lambda = 1.0, minHessian = 10.0, maxRounds = 400, patience = 30)

  private fun logLoss(ensemble: TreeEnsemble): Double {
    var sum = 0.0
    for (i in 0 until validation.n) {
      val p = sigmoid(ensemble.score(validation.fullRow(i))).coerceIn(1e-12, 1 - 1e-12)
      sum += if (validation.y[i].toInt() == 1) -ln(p) else -ln(1 - p)
    }
    return sum / validation.n
  }

  @Test
  fun `l'arresto anticipato si ferma al minimo e il file esportato lo riproduce`() {
    val (result, _, bytes) = gbm(config)
    assertTrue(result.bestRounds in 1 until config.maxRounds)
    assertTrue(result.roundsPlayed == result.bestRounds + config.patience || result.roundsPlayed == config.maxRounds)
    val ensemble = TreeEnsemble.parse(bytes, FeatureExtractorV3.COUNT)
    assertEquals(result.bestRounds, ensemble.trees.size)
    // Dopo la quantizzazione a Float (soglie e valori) la log-loss e' la stessa a meno del rumore.
    assertEquals(result.bestValidationLogLoss, logLoss(ensemble), 1e-5)
  }

  @Test
  fun `il file esportato predice come gli alberi in memoria`() {
    val (result, bins, bytes) = gbm(config)
    val ensemble = TreeEnsemble.parse(bytes, FeatureExtractorV3.COUNT)
    val binned = bins.bin(validation)
    for (i in 0 until 300) {
      var inMemory = result.baseScore + validation.fullRow(i)[anchor]
      for (tree in result.trees) inMemory += tree.predictBinned(binned, i * validation.d)
      assertEquals(inMemory, ensemble.score(validation.fullRow(i)), 1e-4)
      val explanation = ensemble.explain(validation.fullRow(i))
      assertEquals(explanation.score, explanation.bias + explanation.contributions.sum(), 1e-9)
    }
  }

  @Test
  fun `il NaN impara la strada della pioggia`() {
    val (_, _, bytes) = gbm(config)
    val ensemble = TreeEnsemble.parse(bytes, FeatureExtractorV3.COUNT)
    val base = DoubleArray(FeatureExtractorV3.COUNT) { Double.NaN }.also {
      it[trend] = 0.0
      it[anchor] = -1.5
    }
    val withCloud = base.copyOf().also { it[cloud] = 50.0 }
    // Nel mondo finto la copertura mancante vuol dire +1,5 in log-odds: gli alberi l'hanno imparato.
    assertTrue(ensemble.score(base) - ensemble.score(withCloud) > 0.8)
    assertTrue(ensemble.trees.any { tree -> (0 until tree.size).any { !tree.isLeaf(it) && tree.features[it] == cloud } })
  }

  @Test
  fun `gli alberi sono deterministici`() {
    val (_, _, a) = gbm(config)
    val (_, _, b) = gbm(config)
    assertArrayEquals(a, b)
    val other = gbm(config.copy(seed = 7)).third
    assertFalse(a.contentEquals(other))
  }

  @Test
  fun `la scelta sotto il limite di peso scende dove costa meno`() {
    fun point(loss: Double, size: Int) = GbmGridPoint(config, 1, 1, loss, TreeEnsemble(42, 0, 0, -1, 0.0, 0.0, emptyList()), ByteArray(size), DoubleArray(42))
    fun cell(tier: ContextTier, vararg points: GbmGridPoint) = CellResult(tier, 0, IntArray(0), 0, 0, LogisticChoice(null, 1e-3, 1.0), emptyList(), emptyList(), 0, points.toList())
    val cells = listOf(
      cell(ContextTier.FRESH, point(0.300, 800), point(0.301, 200)),
      cell(ContextTier.STALE, point(0.400, 800), point(0.420, 200)),
    )
    val free = V3Trainer.selectTrees(cells, 2000)
    assertEquals(0.300, free.getValue("FRESH.0-1h").validationLogLoss, 0.0)
    val tight = V3Trainer.selectTrees(cells, 1000)
    assertEquals(0.301, tight.getValue("FRESH.0-1h").validationLogLoss, 0.0)
    assertEquals(0.400, tight.getValue("STALE.0-1h").validationLogLoss, 0.0)
  }

  @Test
  fun `le meta' di VALIDATION si portano nella cella`() {
    assertTrue(validation.half!!.toSet() == setOf(TierHalf.A, TierHalf.B))
    assertTrue(abs(validation.n - validationRows.size) == 0)
    assertEquals(RainWindows.ALL.size, 3)
  }
}
