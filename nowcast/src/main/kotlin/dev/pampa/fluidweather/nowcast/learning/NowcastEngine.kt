package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import dev.pampa.fluidweather.nowcast.verdict.alertLevelOf

/** Cio' che il telefono ha imparato finora: le mappe di ricalibrazione e l'archivio degli analoghi. */
data class LearningState(
  val platt: Map<String, PlattParams> = emptyMap(),
  val cases: List<AnalogCase> = emptyList(),
) {
  companion object {
    val EMPTY = LearningState()
  }
}

/** Il verdetto e la sua spiegazione: cos'ha detto il modello, cosa ha corretto il telefono. */
data class NowcastExplanation(
  val verdict: NowcastVerdict,
  val rawVerdict: NowcastVerdict,
  /** finestra -> analoghi trovati e loro esito. */
  val analogs: Map<String, AnalogSummary>,
  /** Le finestre la cui probabilita' e' passata dalla ricalibrazione personale. */
  val recalibrated: Set<String>,
)

/**
 * Stadio 5 piu' l'apprendimento on-device (fase 16): il modello del banco da' la probabilita'
 * grezza, la ricalibrazione personale la porta sulla frequenza vera del microclima, gli
 * analoghi storici la spostano verso com'e' finita nelle situazioni simili. Tutto puro, tutto
 * spiegabile: la pagina mostra i tre numeri, non uno solo.
 */
class NowcastEngine(
  private val model: NowcastModel,
  private val featureMeans: DoubleArray,
  private val featureSds: DoubleArray,
) {

  fun evaluate(features: DoubleArray, learning: LearningState): NowcastExplanation {
    val raw = model.verdict(features)
    val neighbours = if (learning.cases.isEmpty()) {
      emptyList()
    } else {
      Analogs.nearest(features, learning.cases, featureMeans, featureSds)
    }
    val analogs = raw.windows.associate { it.window to Analogs.summarize(neighbours, it.window) }
      .filterValues { it.neighbours > 0 }
    val recalibrated = mutableSetOf<String>()

    val windows = raw.windows.map { window ->
      val platt = learning.platt[window.window]
      val calibrated = if (platt != null) {
        recalibrated += window.window
        window.copy(
          probability = platt.apply(window.probability),
          probabilityLow = platt.apply(window.probabilityLow),
          probabilityHigh = platt.apply(window.probabilityHigh),
        )
      } else {
        window
      }
      val blended = Analogs.blend(calibrated.probability, analogs[window.window])
      val shift = blended - calibrated.probability
      calibrated.copy(
        probability = blended.coerceIn(0.0, 1.0),
        probabilityLow = (calibrated.probabilityLow + shift).coerceIn(0.0, 1.0),
        probabilityHigh = (calibrated.probabilityHigh + shift).coerceIn(0.0, 1.0),
      )
    }
    val verdict = NowcastVerdict(windows = windows, level = alertLevelOf(windows))
    return NowcastExplanation(verdict, raw, analogs, recalibrated)
  }

  companion object {
    fun trained(): NowcastEngine = NowcastEngine(
      model = NowcastModel.trained(),
      featureMeans = dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1.means,
      featureSds = dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1.sds,
    )
  }
}

/** Le tre probabilita' grezze di un verdetto, nell'ordine delle finestre: per l'archivio degli analoghi. */
fun NowcastVerdict.rawProbabilities(): Map<String, Double> = windows.associate { it.window to it.probability }

/** Per i test e per la pagina: una finestra con la sola probabilita' cambiata. */
fun WindowVerdict.withProbability(probability: Double): WindowVerdict = copy(probability = probability)
