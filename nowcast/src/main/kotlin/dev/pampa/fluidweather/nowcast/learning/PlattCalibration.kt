package dev.pampa.fluidweather.nowcast.learning

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

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

  /**
   * La stima con le regole del telefono (P2a), senza toccare [fit] che il banco usa com'e'.
   *
   * Stesso Newton e stesso ammorbidimento degli esiti di [fit], ma con la penalita' ([PlattFitRules.ridge])
   * tarata per contare davvero. Con p(1-p) ~ 0,12 la curvatura dell'intercetta vale ~0,12 n: a n = 100
   * e' ~12, e una penalita' di 5 la accorcia del 30% circa; a n = 1000 e' ~120 e l'accorcia del 4%.
   * Cioe': con pochi dati la mappa resta vicina all'identita', con molti si fida dei dati.
   *
   * Poi la pendenza `a` si tiene in [[PlattFitRules.minSlope], [PlattFitRules.maxSlope]]: fuori, la
   * mappa non sta correggendo una taratura ma riscrivendo il modello a partire da poche giornate
   * fortunate. Se la pendenza e' stata limitata, `b` si ricalcola con `a` fissa, perche' il `b`
   * trovato con la `a` libera non e' piu' quello giusto per la `a` limitata.
   *
   * Nome distinto da `fit` e non un overload: due firme con argomenti di default sarebbero ambigue
   * per chi chiama `fit(samples)`.
   */
  fun fitWithRules(samples: List<CalibrationSample>, rules: PlattFitRules = PlattFitRules.DEFAULT): PlattFitResult {
    val wet = samples.count { it.rained }
    val dry = samples.size - wet
    fun rejected(status: PlattStatus) = PlattFitResult(null, samples.size, wet, dry, status)
    if (samples.size < rules.minSamples) return rejected(PlattStatus.TOO_FEW_SAMPLES)
    if (wet < rules.minWet || wet == 0) return rejected(PlattStatus.TOO_FEW_WET)
    if (dry < rules.minDry || dry == 0) return rejected(PlattStatus.TOO_FEW_DRY)

    val targetPositive = (wet + 1.0) / (wet + 2.0)
    val targetNegative = 1.0 / (dry + 2.0)
    val xs = DoubleArray(samples.size) { PlattParams.logit(samples[it].probability.coerceIn(1e-4, 1 - 1e-4)) }
    val ys = DoubleArray(samples.size) { if (samples[it].rained) targetPositive else targetNegative }
    val ridge = rules.ridge

    var a = 1.0
    var b = 0.0
    for (iteration in 0 until rules.iterations) {
      var ga = ridge * (a - 1.0)
      var gb = ridge * b
      var haa = ridge
      var hab = 0.0
      var hbb = ridge
      for (i in xs.indices) {
        val p = PlattParams.sigmoid(a * xs[i] + b)
        val residual = p - ys[i]
        val curvature = p * (1 - p)
        ga += residual * xs[i]
        gb += residual
        haa += curvature * xs[i] * xs[i]
        hab += curvature * xs[i]
        hbb += curvature
      }
      val determinant = haa * hbb - hab * hab
      if (abs(determinant) < 1e-12) break
      val stepA = (hbb * ga - hab * gb) / determinant
      val stepB = (haa * gb - hab * ga) / determinant
      a -= stepA
      b -= stepB
      if (!a.isFinite() || !b.isFinite()) return rejected(PlattStatus.NOT_CONVERGED)
      if (abs(stepA) < 1e-9 && abs(stepB) < 1e-9) break
    }

    if (a < rules.minSlope || a > rules.maxSlope) {
      a = min(max(a, rules.minSlope), rules.maxSlope)
      // Newton monodimensionale sull'obiettivo penalizzato con `a` fissa: e' convesso in b.
      for (iteration in 0 until rules.iterations) {
        var gb = ridge * b
        var hbb = ridge
        for (i in xs.indices) {
          val p = PlattParams.sigmoid(a * xs[i] + b)
          gb += p - ys[i]
          hbb += p * (1 - p)
        }
        val step = gb / hbb
        b -= step
        if (!b.isFinite()) return rejected(PlattStatus.NOT_CONVERGED)
        if (abs(step) < 1e-9) break
      }
    }
    if (!a.isFinite() || !b.isFinite()) return rejected(PlattStatus.NOT_CONVERGED)
    return PlattFitResult(PlattParams(a, b), samples.size, wet, dry, PlattStatus.ACTIVE)
  }
}

/**
 * Le regole con cui il telefono accetta una stima di Platt.
 *
 * - [minSamples], [minWet], [minDry]: cento verifiche, dieci con pioggia e dieci senza. Sotto, una
 *   curva a due parametri descrive il caso, non il microclima.
 * - [ridge]: la penalita' verso l'identita' (a = 1, b = 0); vedi [PlattCalibration.fitWithRules].
 * - [minSlope], [maxSlope]: il recinto della pendenza.
 */
data class PlattFitRules(
  val minSamples: Int = 100,
  val minWet: Int = 10,
  val minDry: Int = 10,
  val ridge: Double = 5.0,
  val minSlope: Double = 0.5,
  val maxSlope: Double = 2.0,
  val iterations: Int = 50,
) {
  companion object {
    val DEFAULT = PlattFitRules()
  }
}

/**
 * L'esito di una stima: la mappa (null se rifiutata), i conteggi, e il perche'. [status] e'
 * [PlattStatus.ACTIVE] quando la stima e' valida; se la mappa poi si applica lo decide il
 * controllo sugli ultimi dati, non questo.
 */
data class PlattFitResult(
  val params: PlattParams?,
  val samples: Int,
  val wet: Int,
  val dry: Int,
  val status: PlattStatus,
)
