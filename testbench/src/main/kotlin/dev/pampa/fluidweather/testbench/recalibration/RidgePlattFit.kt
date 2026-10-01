package dev.pampa.fluidweather.testbench.recalibration

import dev.pampa.fluidweather.nowcast.learning.PlattParams
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Una mappa stimata: logit(p') = a * logit(p) + b, su quanti casi, e se la pendenza e' stata fermata al bordo. */
data class RecalibrationFit(
  val a: Double,
  val b: Double,
  val samples: Int,
  val positives: Int,
  /** La pendenza libera usciva da [RidgePlattFit.A_MIN, RidgePlattFit.A_MAX] ed e' stata fermata li'. */
  val clamped: Boolean,
) {
  /** La stessa mappa nella forma del motore: stessa formula, stessi ritagli. */
  val params: PlattParams get() = PlattParams(a, b)

  fun apply(probability: Double): Double = params.apply(probability)

  /** La mappa con i coefficienti arrotondati come finiscono nel file generato. */
  fun rounded(decimals: Int = 6): RecalibrationFit {
    val scale = Math.pow(10.0, decimals.toDouble())
    return copy(a = Math.round(a * scale) / scale, b = Math.round(b * scale) / scale)
  }

  companion object {
    val IDENTITY = RecalibrationFit(1.0, 0.0, 0, 0, clamped = false)
  }
}

/**
 * La stima di una mappa di Platt per la ricalibrazione v2r: logit(p') = a * logit(p) + b sulla
 * probabilita' grezza del modello, a massima verosimiglianza con una cresta verso l'identita'
 * (a = 1, b = 0) e la pendenza tenuta in [[A_MIN], [A_MAX]].
 *
 * Non e' [dev.pampa.fluidweather.nowcast.learning.PlattCalibration] (quella del telefono) per due
 * ragioni dichiarate: gli esiti qui non si ammorbidiscono (con decine di migliaia di casi le correzioni
 * di Platt valgono zero e confondono chi rilegge i numeri), e la pendenza ha un recinto. Una pendenza
 * sotto 0,3 schiaccerebbe tutto verso il tasso base (il modello non direbbe piu' niente), sopra 3 lo
 * spingerebbe verso lo 0/1 (il modello direbbe cose che non sa): se la stima libera esce dal recinto
 * la pendenza si ferma al bordo e l'intercetta si ristima da sola.
 *
 * Newton su (a, b) con dimezzamento del passo se la perdita non scende; la cresta
 * `ridge / 2 * ((a - 1)^2 + b^2)` e' sulla somma, non sulla media: con [RIDGE] = 1 e cinquantamila
 * casi non sposta niente, esiste perche' una cella piccola non produca una mappa folle.
 */
object RidgePlattFit {

  const val A_MIN: Double = 0.3
  const val A_MAX: Double = 3.0
  const val RIDGE: Double = 1.0

  /** Lo stesso ritaglio di [PlattParams]: una probabilita' 0 o 1 non ha logit. */
  private const val EPS = 1e-4
  private const val MAX_ITERATIONS = 100
  private const val TOLERANCE = 1e-10

  fun fit(
    probabilities: DoubleArray,
    outcomes: BooleanArray,
    ridge: Double = RIDGE,
    aMin: Double = A_MIN,
    aMax: Double = A_MAX,
  ): RecalibrationFit {
    require(probabilities.size == outcomes.size) { "probabilita' ed esiti non allineati" }
    require(aMin < 1.0 && aMax > 1.0) { "il recinto della pendenza deve contenere l'identita'" }
    val n = probabilities.size
    val positives = outcomes.count { it }
    if (n == 0) return RecalibrationFit.IDENTITY
    val xs = DoubleArray(n) { logit(probabilities[it]) }
    val ys = DoubleArray(n) { if (outcomes[it]) 1.0 else 0.0 }

    var (a, b) = newton(xs, ys, ridge, 1.0, 0.0, fixedA = false)
    var clamped = false
    if (a < aMin || a > aMax) {
      a = a.coerceIn(aMin, aMax)
      b = newton(xs, ys, ridge, a, b, fixedA = true).second
      clamped = true
    }
    return RecalibrationFit(a, b, n, positives, clamped)
  }

  /** La perdita: log-loss sommata piu' la cresta verso l'identita'. */
  internal fun loss(xs: DoubleArray, ys: DoubleArray, ridge: Double, a: Double, b: Double): Double {
    var sum = 0.0
    for (i in xs.indices) {
      val z = a * xs[i] + b
      // log(1 + e^z) - y z, scritto per non traboccare.
      sum += (if (z > 0) z + ln(1 + exp(-z)) else ln(1 + exp(z))) - ys[i] * z
    }
    return sum + ridge / 2 * ((a - 1) * (a - 1) + b * b)
  }

  private fun newton(
    xs: DoubleArray,
    ys: DoubleArray,
    ridge: Double,
    startA: Double,
    startB: Double,
    fixedA: Boolean,
  ): Pair<Double, Double> {
    var a = startA
    var b = startB
    var current = loss(xs, ys, ridge, a, b)
    repeat(MAX_ITERATIONS) {
      var ga = ridge * (a - 1)
      var gb = ridge * b
      var haa = ridge
      var hab = 0.0
      var hbb = ridge
      for (i in xs.indices) {
        val x = xs[i]
        val p = sigmoid(a * x + b)
        val residual = p - ys[i]
        val curvature = p * (1 - p)
        ga += residual * x
        gb += residual
        haa += curvature * x * x
        hab += curvature * x
        hbb += curvature
      }
      val stepA: Double
      val stepB: Double
      if (fixedA) {
        stepA = 0.0
        stepB = gb / hbb
      } else {
        val determinant = haa * hbb - hab * hab
        if (abs(determinant) < 1e-15) return a to b
        stepA = (hbb * ga - hab * gb) / determinant
        stepB = (haa * gb - hab * ga) / determinant
      }
      // Dimezzamento: Newton su una log-loss convessa scende quasi sempre, ma "quasi" non basta.
      var scale = 1.0
      var nextA = a - stepA
      var nextB = b - stepB
      var next = loss(xs, ys, ridge, nextA, nextB)
      while (next > current && scale > 1e-6) {
        scale /= 2
        nextA = a - scale * stepA
        nextB = b - scale * stepB
        next = loss(xs, ys, ridge, nextA, nextB)
      }
      val moved = abs(nextA - a) + abs(nextB - b)
      a = nextA
      b = nextB
      current = next
      if (moved < TOLERANCE) return a to b
    }
    return a to b
  }

  private fun logit(p: Double): Double {
    val q = p.coerceIn(EPS, 1 - EPS)
    return ln(q / (1 - q))
  }

  private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))
}
