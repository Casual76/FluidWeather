package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import kotlin.math.abs
import kotlin.math.exp

/** Il livello che l'app racconta: tre parole, non un numero nudo. */
enum class AlertLevel { QUIETE, SORVEGLIANZA, ALLERTA }

/** Un fattore del verdetto: nome leggibile e contributo (log-odds) con segno. */
data class Factor(
  val name: String,
  val contribution: Double,
)

/** Il verdetto per una finestra: probabilita', banda, livello, e i fattori che l'hanno fatto. */
data class WindowVerdict(
  val window: String,
  val probability: Double,
  val probabilityLow: Double,
  val probabilityHigh: Double,
  val topFactors: List<Factor>,
)

data class NowcastVerdict(
  val windows: List<WindowVerdict>,
  val level: AlertLevel,
) {
  fun forWindow(label: String): WindowVerdict? = windows.firstOrNull { it.window == label }
}

/**
 * I pesi di una finestra: un comitato di regressioni logistiche (bagging), non una sola.
 *
 * Ogni membro e' stato addestrato su un ricampionamento del set di addestramento: la media delle
 * loro probabilita' e' il verdetto, il loro disaccordo e' la banda di incertezza. Cinque tabelle
 * di numeri leggibili al posto di una — e nessuna scatola nera.
 */
data class WindowCoefficients(
  val window: String,
  /** Ogni riga: [intercetta, coefficiente-per-feature...] nell'ordine di [FeatureExtractor.names]. */
  val bags: List<DoubleArray>,
)

/**
 * Stadio 5: dalle feature al verdetto probabilistico.
 *
 * Modello fisicamente vincolato nel senso promesso dal piano: le feature sono grandezze
 * meteorologiche nominate, i coefficienti sono numeri leggibili stimati offline sul banco di
 * prova (fase 4), e ogni verdetto porta con se' i fattori che l'hanno prodotto — il contributo
 * in log-odds di ciascuna feature, ordinato per peso.
 *
 * Le feature NaN (contesto mancante, storia corta) vengono imputate alla media di
 * addestramento, che dopo la standardizzazione e' il neutro esatto: non spingono il verdetto
 * da nessuna parte, e non compaiono fra i fattori.
 */
class NowcastModel(
  private val featureMeans: DoubleArray,
  private val featureSds: DoubleArray,
  private val windows: List<WindowCoefficients>,
) {

  fun verdict(rawFeatures: DoubleArray): NowcastVerdict {
    val standardized = DoubleArray(rawFeatures.size) { i ->
      if (rawFeatures[i].isNaN()) {
        0.0
      } else {
        (rawFeatures[i] - featureMeans[i]) / featureSds[i]
      }
    }

    val verdicts = windows.map { coefficients ->
      val probabilities = coefficients.bags.map { weights -> sigmoid(score(weights, standardized)) }
      val mean = probabilities.average()

      // I fattori dal primo membro del comitato: i bag differiscono per rumore di campionamento,
      // non per opinione, e una sola tabella e' piu' onesta di una media di tabelle.
      val reference = coefficients.bags.first()
      val factors = standardized.indices
        .map { i -> Factor(FeatureExtractor.names[i], reference[i + 1] * standardized[i]) }
        .filter { abs(it.contribution) > FACTOR_FLOOR }
        .sortedByDescending { abs(it.contribution) }
        .take(4)

      WindowVerdict(
        window = coefficients.window,
        probability = mean,
        probabilityLow = probabilities.min(),
        probabilityHigh = probabilities.max(),
        topFactors = factors,
      )
    }

    return NowcastVerdict(windows = verdicts, level = levelOf(verdicts))
  }

  private fun score(weights: DoubleArray, standardized: DoubleArray): Double {
    var sum = weights[0]
    for (i in standardized.indices) sum += weights[i + 1] * standardized[i]
    return sum
  }

  private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

  /**
   * La mappa dichiarata fra probabilita' e parole. Soglie iniziali ragionevoli, da difendere o
   * correggere col banco: ALLERTA quando la pioggia a breve e' piu' probabile che no, e
   * SORVEGLIANZA quando una finestra qualsiasi esce chiaramente dalla climatologia.
   */
  private fun levelOf(verdicts: List<WindowVerdict>): AlertLevel {
    val shortTerm = verdicts.firstOrNull { it.window == "0-1h" }?.probability ?: 0.0
    val medium = verdicts.firstOrNull { it.window == "1-3h" }?.probability ?: 0.0
    val maxAny = verdicts.maxOfOrNull { it.probability } ?: 0.0
    return when {
      shortTerm >= 0.55 || medium >= 0.6 -> AlertLevel.ALLERTA
      maxAny >= 0.35 -> AlertLevel.SORVEGLIANZA
      else -> AlertLevel.QUIETE
    }
  }

  companion object {
    private const val FACTOR_FLOOR = 0.05

    /** Il modello spedito: i coefficienti generati dall'addestramento della fase 4-5. */
    fun trained(): NowcastModel = NowcastModel(
      featureMeans = TrainedNowcastV1.means,
      featureSds = TrainedNowcastV1.sds,
      windows = TrainedNowcastV1.windows,
    )
  }
}
