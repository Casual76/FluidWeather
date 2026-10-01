package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Una stima logistica del v3: [beta] = [intercetta, coefficienti delle colonne usate], su scala standardizzata. */
class LogisticV3Fit(
  val beta: DoubleArray,
  val iterations: Int,
  /** Norma del gradiente penalizzato diviso per la somma dei pesi, all'ultimo passo. */
  val gradientNorm: Double,
) {
  val converged: Boolean get() = gradientNorm < CONVERGENCE

  companion object {
    const val CONVERGENCE: Double = 1e-6
  }
}

/**
 * La regressione logistica del v3 per un (livello, finestra): Newton pesato (IRLS) con penalita' L2 sui
 * coefficienti (mai sull'intercetta), solo sulle colonne del sottoinsieme del livello
 * ([dev.pampa.fluidweather.nowcast.features.FeatureSubsets]), con un'**ancora** facoltativa: una
 * colonna di baseline (in log-odds) che entra nel punteggio con coefficiente uno fisso.
 *
 * `punteggio = ancora + b0 + somma(b_j z_j)`. Con l'ancora il modello e' "la baseline piu' correzioni
 * ristrette": con la penalita' che cresce le correzioni vanno a zero e resta la baseline (spostata di
 * una costante), cioe' esattamente l'avversaria del gate — il punto da cui si parte per batterla.
 *
 * All'esportazione l'ancora sparisce dentro la tabella: `ancora = media + sd z`, quindi il suo
 * coefficiente standardizzato cresce di `sd` e l'intercetta di `media` ([toFullWeights]). Il telefono
 * vede una tabella [dev.pampa.fluidweather.nowcast.verdict.NowcastModel] come le altre.
 *
 * Il passo di Newton ha una salvaguardia (dimezzamento se la perdita penalizzata sale): con le colonne di
 * baseline molto correlate fra loro la hessiana e' mal condizionata, e un passo pieno puo' scavalcare.
 */
object LogisticV3 {

  const val MAX_ITERATIONS: Int = 60

  fun fit(
    design: CellDesign,
    offsets: DoubleArray,
    weights: DoubleArray,
    lambda: Double,
    start: DoubleArray? = null,
  ): LogisticV3Fit {
    val n = design.n
    val d = design.d
    val size = d + 1
    val z = design.z
    val y = design.y
    val totalWeight = weights.sum()
    val beta = start?.copyOf() ?: DoubleArray(size)
    var loss = penalizedLoss(design, offsets, weights, lambda, beta, totalWeight)
    var gradientNorm = Double.MAX_VALUE
    var used = 0
    val gradient = DoubleArray(size)
    val hessian = Array(size) { DoubleArray(size) }
    for (iteration in 1..MAX_ITERATIONS) {
      gradient.fill(0.0)
      for (row in hessian) row.fill(0.0)
      for (i in 0 until n) {
        val w = weights[i]
        if (w == 0.0) continue
        val base = i * d
        var score = offsets[i] + beta[0]
        for (j in 0 until d) score += beta[j + 1] * z[base + j]
        val p = sigmoid(score)
        val error = w * (p - y[i])
        val curvature = w * p * (1 - p)
        gradient[0] += error
        hessian[0][0] += curvature
        for (j in 0 until d) {
          val zj = z[base + j]
          gradient[j + 1] += error * zj
          val cz = curvature * zj
          hessian[0][j + 1] += cz
          val hj = hessian[j + 1]
          for (k in j until d) hj[k + 1] += cz * z[base + k]
        }
      }
      for (j in 1 until size) {
        gradient[j] += lambda * totalWeight * beta[j]
        hessian[j][j] += lambda * totalWeight
      }
      for (a in 0 until size) for (b in 0 until a) hessian[a][b] = hessian[b][a]
      gradientNorm = sqrt(gradient.sumOf { it * it }) / totalWeight
      used = iteration
      if (gradientNorm < LogisticV3Fit.CONVERGENCE) break
      val step = solve(hessian, gradient) ?: break
      var scale = 1.0
      var accepted = false
      repeat(12) {
        if (accepted) return@repeat
        val trial = DoubleArray(size) { beta[it] - scale * step[it] }
        val trialLoss = penalizedLoss(design, offsets, weights, lambda, trial, totalWeight)
        if (trialLoss <= loss + 1e-12) {
          trial.copyInto(beta)
          loss = trialLoss
          accepted = true
        } else {
          scale /= 2
        }
      }
      if (!accepted) break
    }
    return LogisticV3Fit(beta, used, gradientNorm)
  }

  /** Il punteggio (log-odds) di una riga di [design] con [beta] e l'ancora [offset]. */
  fun score(design: CellDesign, row: Int, beta: DoubleArray, offset: Double): Double {
    var s = offset + beta[0]
    val base = row * design.d
    for (j in 0 until design.d) s += beta[j + 1] * design.z[base + j]
    return s
  }

  /**
   * Il vettore di 43 numeri della tabella esportata ([intercetta, 42 coefficienti] su scala
   * standardizzata), con l'ancora assorbita: coefficiente dell'ancora + sd, intercetta + media.
   */
  fun toFullWeights(beta: DoubleArray, columns: IntArray, anchor: Int?, standardization: TierStandardization): DoubleArray {
    val full = DoubleArray(FeatureExtractorV3.COUNT + 1)
    full[0] = beta[0]
    for ((j, c) in columns.withIndex()) full[c + 1] = beta[j + 1]
    if (anchor != null) {
      full[anchor + 1] += standardization.sds[anchor]
      full[0] += standardization.means[anchor]
    }
    return full
  }

  /**
   * I moltiplicatori di un ricampionamento a blocchi (localita', giorno): si estraggono con
   * reinserimento tanti blocchi quanti ce ne sono, e ogni riga pesa quante volte il suo blocco e' uscito.
   * Seme fisso: stesso seme, stessi pesi.
   */
  fun blockBootstrap(design: CellDesign, seed: Long): DoubleArray {
    val random = SplittableRandom(seed)
    val draws = IntArray(design.blockCount)
    repeat(design.blockCount) { draws[random.nextInt(design.blockCount)]++ }
    return DoubleArray(design.n) { draws[design.block[it]].toDouble() }
  }

  /** La log-loss media (pesata) sulle righe [rows] di [design]. */
  fun logLoss(design: CellDesign, rows: IntArray, beta: DoubleArray, offsets: DoubleArray): Double {
    var sum = 0.0
    for (i in rows) {
      val p = sigmoid(score(design, i, beta, offsets[i])).coerceIn(1e-12, 1 - 1e-12)
      sum += if (design.y[i].toInt() == 1) -ln(p) else -ln(1 - p)
    }
    return sum / rows.size
  }

  private fun penalizedLoss(
    design: CellDesign,
    offsets: DoubleArray,
    weights: DoubleArray,
    lambda: Double,
    beta: DoubleArray,
    totalWeight: Double,
  ): Double {
    var sum = 0.0
    for (i in 0 until design.n) {
      val w = weights[i]
      if (w == 0.0) continue
      val s = score(design, i, beta, offsets[i])
      // log(1 + e^s) - y s, stabile per |s| grandi.
      val softplus = if (s > 0) s + ln(1 + exp(-s)) else ln(1 + exp(s))
      sum += w * (softplus - design.y[i] * s)
    }
    var penalty = 0.0
    for (j in 1 until beta.size) penalty += beta[j] * beta[j]
    return sum + 0.5 * lambda * totalWeight * penalty
  }

  /** Eliminazione di Gauss con pivot parziale: null se la matrice e' singolare. */
  internal fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val size = rhs.size
    val a = Array(size) { i -> DoubleArray(size + 1) { j -> if (j < size) matrix[i][j] else rhs[i] } }
    for (column in 0 until size) {
      var pivot = column
      for (row in column + 1 until size) if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
      if (abs(a[pivot][column]) < 1e-14) return null
      val swap = a[column]
      a[column] = a[pivot]
      a[pivot] = swap
      for (row in 0 until size) {
        if (row == column) continue
        val factor = a[row][column] / a[column][column]
        if (factor == 0.0) continue
        for (col in column..size) a[row][col] -= factor * a[column][col]
      }
    }
    return DoubleArray(size) { a[it][size] / a[it][it] }
  }

  fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
