package dev.pampa.fluidweather.nowcast.verdict

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Il verdetto di un livello del v3 dagli alberi: un [TreeEnsemble] per finestra, e accanto la logistica
 * dello stesso livello, che qui serve solo a dire quanto essere incerti.
 *
 * - **Probabilita'**: la sigmoide del punteggio degli alberi.
 * - **Banda**: "banda dal comitato logistico", un'euristica dichiarata. Gli alberi sono uno solo e non
 *   hanno un comitato da cui misurare il disaccordo; la logistica si'. La banda degli alberi e' la
 *   stessa distanza, in log-odds, fra la media del comitato logistico e il suo membro piu' basso (e piu'
 *   alto), spostata sul punteggio degli alberi.
 * - **Fattori**: i quattro contributi di cammino piu' grandi ([TreeEnsemble.explain]), sopra 0,05 in
 *   log-odds, con il nome della feature: la stessa forma dei fattori della logistica.
 */
class TreeNowcastModel(
  /** Un comitato per finestra, nell'ordine dei verdetti. */
  val ensembles: List<TreeEnsemble>,
  /** Le etichette delle finestre, nello stesso ordine. */
  val windowLabels: List<String>,
  /** La logistica dello stesso livello: la banda. */
  val logistic: NowcastModel,
  val featureNames: List<String>,
) : RainModel {

  init {
    require(ensembles.size == windowLabels.size) { "un comitato per finestra" }
    require(ensembles.all { it.featureCount == featureNames.size }) { "comitati e nomi non hanno lo stesso numero di feature" }
    require(logistic.windowLabels == windowLabels) { "la logistica della banda non ha le stesse finestre" }
  }

  override fun verdict(features: DoubleArray): NowcastVerdict {
    require(features.size == featureNames.size) { "attese ${featureNames.size} feature, arrivate ${features.size}" }
    val band = logistic.verdict(features)
    val windows = ensembles.mapIndexed { w, ensemble ->
      val explanation = ensemble.explain(features)
      val score = explanation.score
      val reference = band.windows[w]
      val center = logit(reference.probability)
      val factors = explanation.contributions.indices
        .map { i -> Factor(featureNames[i], explanation.contributions[i]) }
        .filter { abs(it.contribution) > FACTOR_FLOOR }
        .sortedByDescending { abs(it.contribution) }
        .take(4)
      WindowVerdict(
        window = windowLabels[w],
        probability = sigmoid(score),
        probabilityLow = sigmoid(score + logit(reference.probabilityLow) - center),
        probabilityHigh = sigmoid(score + logit(reference.probabilityHigh) - center),
        topFactors = factors,
      )
    }
    return NowcastVerdict(windows, alertLevelOf(windows))
  }

  companion object {
    private const val FACTOR_FLOOR = 0.05
    private const val EPS = 1e-6

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun logit(p: Double): Double {
      val q = p.coerceIn(EPS, 1 - EPS)
      return ln(q / (1 - q))
    }
  }
}
