package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.verdict.Tree
import dev.pampa.fluidweather.nowcast.verdict.TreeEnsemble
import java.util.Locale
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln

/** Un punto della griglia degli alberi. */
data class GbmConfig(
  val depth: Int,
  val learningRate: Double,
  val lambda: Double,
  val minHessian: Double,
  val subsample: Double = 0.8,
  val maxRounds: Int = if (learningRate < 0.05) 3000 else 1000,
  val patience: Int = 100,
  val seed: Long = 42L,
) {
  val label: String get() = String.format(Locale.ROOT, "d%d lr%.2f l%.0f h%.0f", depth, learningRate, lambda, minHessian)

  companion object {
    /** La griglia del piano: profondita' {2,3,4} x passo {0,03; 0,1} x lambda {1, 10} x hessiana minima {10, 50}. */
    val GRID: List<GbmConfig> = buildList {
      for (depth in listOf(2, 3, 4)) for (lr in listOf(0.03, 0.1)) for (lambda in listOf(1.0, 10.0)) for (h in listOf(10.0, 50.0)) {
        add(GbmConfig(depth, lr, lambda, h))
      }
    }
  }
}

/**
 * I bordi degli istogrammi di ogni colonna usata: al massimo [GbmV3.MAX_BINS] cestini per quantile sulle
 * righe di addestramento, piu' uno per il NaN. I bordi sono valori `Float` delle righe stesse: lo split
 * "cestino <= b" e' esattamente "x <= bordo[b]", che e' cio' che il file esportato confronta.
 */
class GbmBins(val edges: Array<FloatArray>) {

  /** Quanti bordi ha ogni colonna: gli split possibili. */
  val edgeCounts: IntArray get() = IntArray(edges.size) { edges[it].size }

  /** Il cestino di un valore: il primo bordo che lo contiene, [GbmV3.NAN_BIN] per il NaN. */
  fun binOf(column: Int, value: Float): Int {
    if (value.isNaN()) return GbmV3.NAN_BIN
    val e = edges[column]
    var low = 0
    var high = e.size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (value > e[mid]) low = mid + 1 else high = mid
    }
    return low
  }

  /** La matrice dei cestini di [design], riga per riga. */
  fun bin(design: CellDesign): ByteArray {
    val d = design.d
    val out = ByteArray(design.n * d)
    for (i in 0 until design.n) for (j in 0 until d) out[i * d + j] = binOf(j, design.raw[i * d + j]).toByte()
    return out
  }

  companion object {
    fun of(design: CellDesign, maxBins: Int = GbmV3.MAX_BINS): GbmBins {
      val d = design.d
      val edges = Array(d) { j ->
        val values = FloatArray(design.n)
        var count = 0
        for (i in 0 until design.n) {
          val v = design.raw[i * d + j]
          if (!v.isNaN()) values[count++] = v
        }
        val sorted = values.copyOf(count).also { it.sort() }
        val chosen = java.util.TreeSet<Float>()
        if (count > 0) {
          for (q in 1 until maxBins) {
            chosen += sorted[((q.toLong() * (count - 1)) / maxBins).toInt()]
          }
          // Il massimo non fa da bordo: tutto starebbe a sinistra.
          chosen.remove(sorted[count - 1])
        }
        chosen.toFloatArray()
      }
      return GbmBins(edges)
    }
  }
}

/** Un albero in memoria, in preordine come nel file: colonna locale, cestino di split, NaN a sinistra, valore (gia' per il passo). */
class GbmTree(
  val leaf: BooleanArray,
  val column: IntArray,
  val splitBin: IntArray,
  val nanLeft: BooleanArray,
  val value: DoubleArray,
  val right: IntArray,
) {
  val size: Int get() = leaf.size

  fun predictBinned(bins: ByteArray, base: Int): Double {
    var node = 0
    while (!leaf[node]) {
      val b = bins[base + column[node]].toInt() and 0xFF
      val left = if (b == GbmV3.NAN_BIN) nanLeft[node] else b <= splitBin[node]
      node = if (left) node + 1 else right[node]
    }
    return value[node]
  }
}

/** Il risultato di un addestramento degli alberi: gli alberi fino al giro migliore e la curva di validazione. */
class GbmV3Result(
  val config: GbmConfig,
  val anchor: Int?,
  val baseScore: Double,
  val offsetMean: Double,
  val trees: List<GbmTree>,
  val bestRounds: Int,
  val bestValidationLogLoss: Double,
  val roundsPlayed: Int,
  /** Guadagno totale degli split per colonna locale: l'importanza per il rapporto. */
  val gainByColumn: DoubleArray,
)

/**
 * Il gradient boosting del v3 per un (livello, finestra), scritto qui per non imbarcare dipendenze:
 * alberi di regressione sul log-odds, istogrammi a 64 cestini per colonna piu' uno per il NaN, foglie
 * col passo di Newton e penalita' L2, hessiana minima per figlio, sottocampionamento per blocchi
 * (localita', giorno) a ogni giro, arresto anticipato sulla log-loss di VALIDATION A.
 *
 * - **Il NaN impara la sua strada.** A ogni split si provano entrambe: il NaN con i piccoli o con i
 *   grandi; vince il guadagno. Senza NaN nel nodo, va dal figlio con piu' hessiana (il ramo "normale").
 * - **L'ancora.** Come nella logistica: il margine di partenza e' la baseline scelta (in log-odds) piu'
 *   la costante che la tara sulle righe di addestramento; gli alberi imparano solo la correzione.
 * - **Determinismo.** Il sottocampionamento ha il seme per giro; con gli stessi dati e la stessa
 *   configurazione gli alberi sono gli stessi.
 */
object GbmV3 {

  const val MAX_BINS: Int = 64
  const val NAN_BIN: Int = 64
  private const val WIDTH = NAN_BIN + 1
  private const val MIN_GAIN = 1e-9

  /**
   * Addestra. [trainBins]/[validationBins] sono le matrici dei cestini (con i bordi di [bins]),
   * [validationRows] le righe di VALIDATION su cui si ferma (A, Europa), [weights] i pesi delle righe di
   * addestramento, [anchor] la colonna (0..41) dell'ancora o null.
   */
  fun train(
    train: CellDesign,
    trainBins: ByteArray,
    validation: CellDesign,
    validationBins: ByteArray,
    validationRows: IntArray,
    weights: DoubleArray,
    anchor: Int?,
    config: GbmConfig,
    edgeCounts: IntArray,
  ): GbmV3Result {
    val n = train.n
    val d = train.d
    val offsets = train.offsets(anchor)
    val offsetMean = if (anchor == null) 0.0 else weightedMean(offsets, weights)
    val baseScore = interceptGiven(offsets, train.y, weights)
    val scores = DoubleArray(n) { offsets[it] + baseScore }
    val validationOffsets = validation.offsets(anchor, offsetMean)
    val validationScores = DoubleArray(validation.n) { validationOffsets[it] + baseScore }

    val gradient = DoubleArray(n)
    val hessian = DoubleArray(n)
    val trees = ArrayList<GbmTree>()
    val gains = DoubleArray(d)
    val gainsAtBest = DoubleArray(d)
    var bestLoss = logLoss(validation, validationRows, validationScores)
    var bestRounds = 0
    var played = 0
    val sample = IntArray(n)

    for (round in 1..config.maxRounds) {
      played = round
      for (i in 0 until n) {
        val p = sigmoid(scores[i])
        gradient[i] = weights[i] * (p - train.y[i])
        hessian[i] = weights[i] * p * (1 - p)
      }
      val random = SplittableRandom(config.seed * 1_000_003L + round)
      val keep = BooleanArray(train.blockCount) { random.nextDouble() < config.subsample }
      var m = 0
      for (i in 0 until n) if (keep[train.block[i]] && weights[i] > 0) sample[m++] = i
      if (m == 0) break

      val tree = grow(trainBins, d, edgeCounts, sample, m, gradient, hessian, config, gains)
      trees += tree
      for (i in 0 until n) scores[i] += tree.predictBinned(trainBins, i * d)
      for (i in 0 until validation.n) validationScores[i] += tree.predictBinned(validationBins, i * d)
      val loss = logLoss(validation, validationRows, validationScores)
      if (loss < bestLoss - 1e-12) {
        bestLoss = loss
        bestRounds = round
        gains.copyInto(gainsAtBest)
      } else if (round - bestRounds >= config.patience) {
        break
      }
    }
    return GbmV3Result(config, anchor, baseScore, offsetMean, trees.subList(0, bestRounds).toList(), bestRounds, bestLoss, played, gainsAtBest)
  }

  /**
   * Riaddestra a giri fissi (il riaddestramento finale: iperparametri e giri congelati), senza
   * validazione.
   */
  fun trainFixed(
    train: CellDesign,
    trainBins: ByteArray,
    weights: DoubleArray,
    anchor: Int?,
    config: GbmConfig,
    rounds: Int,
    edgeCounts: IntArray,
  ): GbmV3Result {
    val n = train.n
    val d = train.d
    val offsets = train.offsets(anchor)
    val offsetMean = if (anchor == null) 0.0 else weightedMean(offsets, weights)
    val baseScore = interceptGiven(offsets, train.y, weights)
    val scores = DoubleArray(n) { offsets[it] + baseScore }
    val gradient = DoubleArray(n)
    val hessian = DoubleArray(n)
    val trees = ArrayList<GbmTree>()
    val gains = DoubleArray(d)
    val sample = IntArray(n)
    for (round in 1..rounds) {
      for (i in 0 until n) {
        val p = sigmoid(scores[i])
        gradient[i] = weights[i] * (p - train.y[i])
        hessian[i] = weights[i] * p * (1 - p)
      }
      val random = SplittableRandom(config.seed * 1_000_003L + round)
      val keep = BooleanArray(train.blockCount) { random.nextDouble() < config.subsample }
      var m = 0
      for (i in 0 until n) if (keep[train.block[i]] && weights[i] > 0) sample[m++] = i
      if (m == 0) break
      val tree = grow(trainBins, d, edgeCounts, sample, m, gradient, hessian, config, gains)
      trees += tree
      for (i in 0 until n) scores[i] += tree.predictBinned(trainBins, i * d)
    }
    return GbmV3Result(config, anchor, baseScore, offsetMean, trees, trees.size, Double.NaN, trees.size, gains)
  }

  private class Builder {
    val leaf = ArrayList<Boolean>()
    val column = ArrayList<Int>()
    val splitBin = ArrayList<Int>()
    val nanLeft = ArrayList<Boolean>()
    val value = ArrayList<Double>()
    val right = ArrayList<Int>()

    fun add(): Int {
      leaf += true
      column += -1
      splitBin += -1
      nanLeft += false
      value += 0.0
      right += -1
      return leaf.size - 1
    }

    fun build() = GbmTree(
      leaf.toBooleanArray(), column.toIntArray(), splitBin.toIntArray(), nanLeft.toBooleanArray(), value.toDoubleArray(), right.toIntArray(),
    )
  }

  private fun grow(
    bins: ByteArray,
    d: Int,
    edgeCounts: IntArray,
    sample: IntArray,
    m: Int,
    gradient: DoubleArray,
    hessian: DoubleArray,
    config: GbmConfig,
    gains: DoubleArray,
  ): GbmTree {
    val builder = Builder()
    val rows = sample.copyOf(m)
    node(builder, bins, d, edgeCounts, rows, 0, m, 0, gradient, hessian, config, gains)
    return builder.build()
  }

  private fun node(
    builder: Builder,
    bins: ByteArray,
    d: Int,
    edgeCounts: IntArray,
    rows: IntArray,
    from: Int,
    to: Int,
    depth: Int,
    gradient: DoubleArray,
    hessian: DoubleArray,
    config: GbmConfig,
    gains: DoubleArray,
  ) {
    val index = builder.add()
    var g = 0.0
    var h = 0.0
    for (k in from until to) {
      g += gradient[rows[k]]
      h += hessian[rows[k]]
    }
    builder.value[index] = -g / (h + config.lambda) * config.learningRate
    if (depth >= config.depth || h < 2 * config.minHessian) return

    val histG = DoubleArray(d * WIDTH)
    val histH = DoubleArray(d * WIDTH)
    for (k in from until to) {
      val i = rows[k]
      val gi = gradient[i]
      val hi = hessian[i]
      val base = i * d
      for (j in 0 until d) {
        val cell = j * WIDTH + (bins[base + j].toInt() and 0xFF)
        histG[cell] += gi
        histH[cell] += hi
      }
    }
    val parentScore = g * g / (h + config.lambda)
    var bestGain = MIN_GAIN
    var bestColumn = -1
    var bestBin = -1
    var bestNanLeft = false
    for (j in 0 until d) {
      val offset = j * WIDTH
      val nanG = histG[offset + NAN_BIN]
      val nanH = histH[offset + NAN_BIN]
      var leftG = 0.0
      var leftH = 0.0
      for (b in 0 until edgeCounts[j]) {
        leftG += histG[offset + b]
        leftH += histH[offset + b]
        if (histH[offset + b] == 0.0 && histG[offset + b] == 0.0 && b > 0) continue
        val rightG = g - nanG - leftG
        val rightH = h - nanH - leftH
        // NaN a destra.
        if (leftH >= config.minHessian && rightH + nanH >= config.minHessian) {
          val gain = leftG * leftG / (leftH + config.lambda) + (rightG + nanG) * (rightG + nanG) / (rightH + nanH + config.lambda) - parentScore
          if (gain > bestGain) {
            bestGain = gain
            bestColumn = j
            bestBin = b
            bestNanLeft = false
          }
        }
        // NaN a sinistra: solo se ce n'e'.
        if (nanH > 0.0 && leftH + nanH >= config.minHessian && rightH >= config.minHessian) {
          val gain = (leftG + nanG) * (leftG + nanG) / (leftH + nanH + config.lambda) + rightG * rightG / (rightH + config.lambda) - parentScore
          if (gain > bestGain) {
            bestGain = gain
            bestColumn = j
            bestBin = b
            bestNanLeft = true
          }
        }
      }
    }
    if (bestColumn < 0) return
    // Nessun NaN nel nodo: la strada del NaN e' quella del figlio con piu' hessiana.
    val offset = bestColumn * WIDTH
    if (histH[offset + NAN_BIN] == 0.0) {
      var leftH = 0.0
      for (b in 0..bestBin) leftH += histH[offset + b]
      bestNanLeft = leftH >= h - leftH
    }
    gains[bestColumn] += bestGain

    // Partizione in place: prima i sinistri.
    var i = from
    var j = to - 1
    while (i <= j) {
      val bin = bins[rows[i] * d + bestColumn].toInt() and 0xFF
      val left = if (bin == NAN_BIN) bestNanLeft else bin <= bestBin
      if (left) {
        i++
      } else {
        val swap = rows[i]
        rows[i] = rows[j]
        rows[j] = swap
        j--
      }
    }
    builder.leaf[index] = false
    builder.column[index] = bestColumn
    builder.splitBin[index] = bestBin
    builder.nanLeft[index] = bestNanLeft
    node(builder, bins, d, edgeCounts, rows, from, i, depth + 1, gradient, hessian, config, gains)
    builder.right[index] = builder.leaf.size
    node(builder, bins, d, edgeCounts, rows, i, to, depth + 1, gradient, hessian, config, gains)
  }

  /** Gli alberi come [TreeEnsemble] (il formato `.fwgb`): colonne globali, soglie = bordi, valori in Float. */
  fun export(result: GbmV3Result, tierOrdinal: Int, window: Int, columns: IntArray, bins: GbmBins): TreeEnsemble {
    val trees = result.trees.map { tree ->
      val flags = ByteArray(tree.size) { node ->
        ((if (tree.leaf[node]) Tree.LEAF else 0) or (if (!tree.leaf[node] && tree.nanLeft[node]) Tree.NAN_LEFT else 0)).toByte()
      }
      Tree(
        flags = flags,
        values = FloatArray(tree.size) { tree.value[it].toFloat() },
        features = IntArray(tree.size) { if (tree.leaf[it]) -1 else columns[tree.column[it]] },
        thresholds = FloatArray(tree.size) { if (tree.leaf[it]) Float.NaN else bins.edges[tree.column[it]][tree.splitBin[it]] },
        rights = IntArray(tree.size) { if (tree.leaf[it]) -1 else tree.right[it] },
      )
    }
    return TreeEnsemble(
      featureCount = FeatureExtractorV3.COUNT,
      tier = tierOrdinal,
      window = window,
      offsetFeature = result.anchor ?: -1,
      baseScore = result.baseScore,
      offsetMean = result.offsetMean,
      trees = trees,
    )
  }

  /** La costante che, sopra l'ancora, minimizza la log-loss pesata: il margine di partenza degli alberi. */
  fun interceptGiven(offsets: DoubleArray, y: ByteArray, weights: DoubleArray): Double {
    var c = 0.0
    repeat(50) {
      var g = 0.0
      var h = 0.0
      for (i in offsets.indices) {
        val w = weights[i]
        if (w == 0.0) continue
        val p = sigmoid(offsets[i] + c)
        g += w * (p - y[i])
        h += w * p * (1 - p)
      }
      if (h <= 0) return c
      val step = g / h
      c -= step
      if (kotlin.math.abs(step) < 1e-12) return c
    }
    return c
  }

  private fun weightedMean(values: DoubleArray, weights: DoubleArray): Double {
    var s = 0.0
    var w = 0.0
    for (i in values.indices) {
      s += weights[i] * values[i]
      w += weights[i]
    }
    return if (w == 0.0) 0.0 else s / w
  }

  private fun logLoss(design: CellDesign, rows: IntArray, scores: DoubleArray): Double {
    var sum = 0.0
    for (i in rows) {
      val p = sigmoid(scores[i]).coerceIn(1e-12, 1 - 1e-12)
      sum += if (design.y[i].toInt() == 1) -ln(p) else -ln(1 - p)
    }
    return sum / rows.size
  }

  fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
