package dev.pampa.fluidweather.nowcast.learning

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** La mappa di ricalibrazione di una finestra: p' = sigma(a * logit(p) + b). Identita' = (1, 0). */
data class PlattParams(val a: Double, val b: Double) {

  fun apply(probability: Double): Double {
    val p = probability.coerceIn(EPS, 1 - EPS)
    return sigmoid(a * logit(p) + b).coerceIn(EPS, 1 - EPS)
  }

  companion object {
    val IDENTITY = PlattParams(1.0, 0.0)
    private const val EPS = 1e-4
    internal fun logit(p: Double): Double = ln(p / (1 - p))
    internal fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
  }
}

/** Una coppia (probabilita' detta, esito accaduto): la materia prima della ricalibrazione. */
data class CalibrationSample(val probability: Double, val rained: Boolean)

/**
 * La ricalibrazione personale del piano (fase 16): sul dispositivo NON si riaddestra il
 * modello, si ricalibra la sua probabilita' sulle verifiche locali (Platt scaling), cosi' il
 * "60%" dichiarato e' un 60% vero nel microclima di chi lo legge.
 *
 * Regressione logistica su una sola variabile — il logit della probabilita' del modello — con
 * due parametri, stimata per Newton su tutte le coppie raccolte. Due accortezze da Platt (1999)
 * e da buon senso: gli esiti sono ammorbiditi ((N+ + 1)/(N+ + 2) e 1/(N- + 2)) per non
 * inseguire i casi estremi, e una piccola penalita' tira i parametri verso l'identita': con
 * pochi dati la ricalibrazione e' timida, con molti si fida di se'.
 */
object PlattCalibration {

  /** Sotto trenta verifiche il modello del banco resta com'e': una curva su dieci punti e' rumore. */
  const val MIN_SAMPLES: Int = 30

  fun fit(samples: List<CalibrationSample>, ridge: Double = 0.05, iterations: Int = 50): PlattParams? {
    if (samples.size < MIN_SAMPLES) return null
    val positives = samples.count { it.rained }
    val negatives = samples.size - positives
    if (positives == 0 || negatives == 0) return null
    val targetPositive = (positives + 1.0) / (positives + 2.0)
    val targetNegative = 1.0 / (negatives + 2.0)

    val xs = samples.map { PlattParams.logit(it.probability.coerceIn(1e-4, 1 - 1e-4)) }
    val ys = samples.map { if (it.rained) targetPositive else targetNegative }

    var a = 1.0
    var b = 0.0
    repeat(iterations) {
      // Gradiente e hessiana della log-loss (+ ridge verso l'identita').
      var ga = ridge * (a - 1.0)
      var gb = ridge * b
      var haa = ridge
      var hab = 0.0
      var hbb = ridge
      for (i in xs.indices) {
        val x = xs[i]
        val p = PlattParams.sigmoid(a * x + b)
        val residual = p - ys[i]
        val curvature = p * (1 - p)
        ga += residual * x
        gb += residual
        haa += curvature * x * x
        hab += curvature * x
        hbb += curvature
      }
      val determinant = haa * hbb - hab * hab
      if (abs(determinant) < 1e-12) return@repeat
      val stepA = (hbb * ga - hab * gb) / determinant
      val stepB = (haa * gb - hab * ga) / determinant
      a -= stepA
      b -= stepB
      if (abs(stepA) < 1e-7 && abs(stepB) < 1e-7) return PlattParams(a, b)
    }
    return PlattParams(a, b)
  }
}
