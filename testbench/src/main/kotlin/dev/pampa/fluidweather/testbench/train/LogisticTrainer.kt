package dev.pampa.fluidweather.testbench.train

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Medie e deviazioni per feature, stimate sul solo train: il contratto di standardizzazione.
 *
 * **La feature costante.** Se una feature non varia nel set di addestramento, la sua deviazione
 * non e' "piccola": non esiste. Prima finiva su un pavimento di 1e-6, e siccome il modello
 * spedito divide per quella deviazione, sul telefono — dove la stessa feature *varia* — il
 * rapporto fra peso e deviazione la trasformava in un moltiplicatore diretto sui log-odds. Una
 * feature che a banco non diceva niente diventava, in produzione, la piu' forte di tutte.
 * Adesso una feature degenere viene dichiarata tale e neutralizzata: media al suo valore,
 * deviazione uno. Chi la legge ottiene circa zero, che e' esattamente quanto vale.
 */
class Standardization(
  val means: DoubleArray,
  val sds: DoubleArray,
  /** Gli indici delle feature che nel train non variavano: il rapporto le nomina. */
  val degenerate: List<Int> = emptyList(),
) {

  /** NaN -> 0 dopo la standardizzazione: il neutro esatto, mai un'invenzione. */
  fun apply(features: DoubleArray): DoubleArray = DoubleArray(features.size) { i ->
    if (features[i].isNaN()) 0.0 else (features[i] - means[i]) / sds[i]
  }

  companion object {
    /** Sotto questa deviazione una feature e' una costante col rumore numerico attorno. */
    const val DEGENERATE_SD = 1e-3

    fun fit(rows: List<DoubleArray>, featureCount: Int): Standardization {
      val means = DoubleArray(featureCount)
      val sds = DoubleArray(featureCount)
      val degenerate = mutableListOf<Int>()
      for (i in 0 until featureCount) {
        val values = rows.mapNotNull { row -> row[i].takeIf { !it.isNaN() } }
        val mean = if (values.isEmpty()) 0.0 else values.average()
        val variance = if (values.size < 2) 0.0 else values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        val sd = sqrt(variance)
        means[i] = mean
        if (sd < DEGENERATE_SD) {
          degenerate += i
          sds[i] = 1.0
        } else {
          sds[i] = sd
        }
      }
      return Standardization(means, sds, degenerate)
    }
  }
}

/** Come e' finita una stima: i pesi, e le due misure che dicono se e' finita davvero. */
data class LogisticFit(
  val weights: DoubleArray,
  val logLoss: Double,
  val gradientNorm: Double,
  val iterations: Int,
) {
  val converged: Boolean get() = gradientNorm < CONVERGENCE_GRADIENT_NORM

  companion object {
    const val CONVERGENCE_GRADIENT_NORM = 1e-6
  }
}

/**
 * Regressione logistica con L2, stimata per Newton (IRLS): nessuna dipendenza, nessuna sorpresa,
 * riproducibile al bit. Il bagging (5 ricampionamenti con seme fisso) produce il comitato del
 * modello spedito: media = verdetto, disaccordo = banda.
 *
 * **Perche' Newton e non piu' la discesa del gradiente.** La versione precedente faceva 400 passi
 * a passo fisso e si fermava li', senza guardare se fosse arrivata. Con feature molto correlate
 * fra loro — e le tendenze a 1, 3, 6 e 12 ore lo sono per costruzione — la discesa del gradiente
 * si muove lungo la valle a passi piccolissimi: l'intercetta e le feature forti convergono, le
 * altre restano in viaggio, e i loro coefficienti si leggono come "questa feature non conta"
 * quando invece vuol dire "non abbiamo aspettato". Con sedici feature la hessiana e' una matrice
 * 17x17: risolverla per intero costa meno di duecento passi di gradiente, e arriva davvero.
 */
class LogisticTrainer(
  private val l2: Double = 1e-3,
  private val iterations: Int = 50,
  private val bags: Int = 5,
  private val seed: Long = 42L,
) {

  /**
   * Restituisce [bags] vettori [intercetta, pesi...] su feature gia' standardizzate.
   *
   * [degenerate] sono gli indici che nel train non variavano: i loro coefficienti vengono messi
   * a zero, e zero restano. E' la rete di sicurezza che mancava — una feature costante qui puo'
   * variare eccome sul telefono, e un coefficiente "quasi zero" moltiplicato per un valore
   * grande non e' quasi niente: e' una feature mai addestrata che vota.
   */
  fun fitBagged(
    features: List<DoubleArray>,
    labels: List<Boolean>,
    degenerate: List<Int> = emptyList(),
  ): List<LogisticFit> {
    val random = Random(seed)
    val draws = (0 until bags).map { IntArray(features.size) { random.nextInt(features.size) } }
    return draws.parallelStream()
      .map { indices -> fit(indices.map { features[it] }, indices.map { labels[it] }) }
      .map { fit ->
        if (degenerate.isEmpty()) {
          fit
        } else {
          val muted = fit.weights.copyOf()
          degenerate.forEach { muted[it + 1] = 0.0 }
          fit.copy(weights = muted)
        }
      }
      .toList()
  }

  fun fit(features: List<DoubleArray>, labels: List<Boolean>): LogisticFit {
    val n = features.size
    val d = features.first().size
    val size = d + 1
    val weights = DoubleArray(size)
    val targets = DoubleArray(n) { if (labels[it]) 1.0 else 0.0 }

    var gradientNorm = Double.MAX_VALUE
    var used = 0
    for (iteration in 1..iterations) {
      val gradient = DoubleArray(size)
      val hessian = Array(size) { DoubleArray(size) }
      for (row in 0 until n) {
        val x = features[row]
        var score = weights[0]
        for (i in 0 until d) score += weights[i + 1] * x[i]
        val p = sigmoid(score)
        val error = p - targets[row]
        val curvature = p * (1 - p)
        gradient[0] += error
        for (i in 0 until d) gradient[i + 1] += error * x[i]
        // Hessiana X^T W X, solo il triangolo superiore: la matrice e' simmetrica.
        hessian[0][0] += curvature
        for (i in 0 until d) {
          val wxi = curvature * x[i]
          hessian[0][i + 1] += wxi
          for (j in i until d) hessian[i + 1][j + 1] += wxi * x[j]
        }
      }
      // L2 sui pesi, mai sull'intercetta: regolarizzare il tasso base sarebbe una bugia.
      for (i in 1 until size) {
        gradient[i] += l2 * n * weights[i]
        hessian[i][i] += l2 * n
      }
      for (i in 0 until size) for (j in 0 until i) hessian[i][j] = hessian[j][i]

      gradientNorm = sqrt(gradient.sumOf { it * it }) / n
      used = iteration
      if (gradientNorm < LogisticFit.CONVERGENCE_GRADIENT_NORM) break

      val step = solve(hessian, gradient) ?: break
      for (i in 0 until size) weights[i] -= step[i]
    }

    return LogisticFit(weights, logLoss(features, targets, weights), gradientNorm, used)
  }

  private fun logLoss(features: List<DoubleArray>, targets: DoubleArray, weights: DoubleArray): Double {
    val d = features.first().size
    var sum = 0.0
    for (row in features.indices) {
      val x = features[row]
      var score = weights[0]
      for (i in 0 until d) score += weights[i + 1] * x[i]
      val p = sigmoid(score).coerceIn(1e-12, 1 - 1e-12)
      sum += if (targets[row] > 0.5) -ln(p) else -ln(1 - p)
    }
    return sum / features.size
  }

  /** Eliminazione di Gauss con pivot parziale: null se la matrice e' singola. */
  private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val size = rhs.size
    val a = Array(size) { i -> DoubleArray(size + 1) { j -> if (j < size) matrix[i][j] else rhs[i] } }
    for (column in 0 until size) {
      var pivot = column
      for (row in column + 1 until size) if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
      if (abs(a[pivot][column]) < 1e-12) return null
      val swap = a[column]; a[column] = a[pivot]; a[pivot] = swap
      for (row in 0 until size) {
        if (row == column) continue
        val factor = a[row][column] / a[column][column]
        if (factor == 0.0) continue
        for (col in column..size) a[row][col] -= factor * a[column][col]
      }
    }
    return DoubleArray(size) { a[it][size] / a[it][it] }
  }

  private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
