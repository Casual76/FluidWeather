package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import dev.pampa.fluidweather.nowcast.verdict.alertLevelOf

/**
 * Cio' che il telefono ha imparato finora: le mappe di ricalibrazione e l'archivio degli analoghi.
 *
 * [platt] e' la mappa unica storica (il banco, e chi non dice il livello di contesto al motore);
 * [plattByVariant] le mappe per famiglia di contesto, le sole che l'app carica.
 */
data class LearningState(
  val platt: Map<String, PlattParams> = emptyMap(),
  val cases: List<AnalogCase> = emptyList(),
  val plattByVariant: Map<PlattVariant, Map<String, PlattParams>> = emptyMap(),
  /**
   * Le finestre in cui, senza contesto, questo telefono ha dimostrato che la regola barometrica locale
   * batte il modello ([PlattRefitPolicy.ruleGuard]): li' il motore dice la regola.
   */
  val ruleWindows: Set<String> = emptySet(),
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
  /** Cosa si vedeva davvero fuori, se qualcuno l'ha guardato. */
  val observation: RainObservation? = null,
  /** Le finestre in cui l'osservazione ha alzato la probabilita' del modello. */
  val observed: Set<String> = emptySet(),
  /** Il livello di contesto con cui e' stato emesso il verdetto, se il chiamante lo ha detto. */
  val tier: ContextTier? = null,
  /** La famiglia di mappe di Platt consultata per quel livello. */
  val plattVariant: PlattVariant? = null,
  /** Le finestre in cui ha parlato la regola barometrica locale al posto del modello. */
  val ruleFallback: Set<String> = emptySet(),
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
  /** Gli analoghi: il banco li vuole ([AnalogPolicy.LEGACY]), l'app li tiene spenti ([AnalogPolicy.OFF]). */
  val analogPolicy: AnalogPolicy = AnalogPolicy.LEGACY,
  /**
   * Il nucleo indipendente v3, un modello per livello di contesto. Quando c'e' e il chiamante dice il
   * livello, le feature sono le quarantadue di [dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3]
   * e il verdetto grezzo viene da qui; [model] resta per chi parla ancora il v2 (il banco, i test).
   * Col v3 niente analoghi (vivono sullo spazio delle venti feature del v2) e niente pavimenti
   * dell'osservazione: il modello vede gia' "piove adesso", e a banco il pavimento peggiorava lo
   * STALE 0-1h senza aiutare altrove ([dev.pampa.fluidweather.nowcast.verdict.FloorPoliciesV3]).
   */
  private val tiered: TieredNowcastModel? = null,
) {

  /** Il v3 parla quando c'e' e quando il livello e' noto. */
  val speaksV3: Boolean get() = tiered != null

  /**
   * [tier] dice in che regime di contesto e' stato prodotto il verdetto e sceglie la mappa di
   * Platt da usare; null (il default) conserva il comportamento storico, la mappa unica. Non c'e'
   * ricaduta dall'una all'altra: una mappa di un altro regime corregge errori che qui non ci sono.
   */
  fun evaluate(
    features: DoubleArray,
    learning: LearningState,
    /** Cio' che si vede dalla finestra: puo' alzare il verdetto, mai abbassarlo. */
    observation: RainObservation? = null,
    tier: ContextTier? = null,
  ): NowcastExplanation {
    val v3 = tiered?.takeIf { tier != null }
    val raw = if (v3 != null) v3.verdict(tier!!, features) else model.verdict(features)
    val variant = tier?.let(PlattVariant::of)
    val neighbours = if (v3 != null || !analogPolicy.enabled || learning.cases.isEmpty()) {
      emptyList()
    } else {
      Analogs.nearest(features, learning.cases, featureMeans, featureSds, k = analogPolicy.neighbours)
    }
    val analogs = raw.windows.associate { it.window to Analogs.summarize(neighbours, it.window) }
      .filterValues { it.neighbours > 0 }
    val recalibrated = mutableSetOf<String>()

    val ruleTier = v3 != null && (tier == ContextTier.NONE || tier == ContextTier.NONE_NOCLIMA)
    val ruled = mutableSetOf<String>()
    val windows = raw.windows.map { window ->
      if (ruleTier && window.window in learning.ruleWindows) {
        val rule = PlattRefitPolicy.ruleProbabilityOf(features.toList(), window.window)
        if (rule != null) {
          ruled += window.window
          // La regola e' un numero solo: niente banda dei bag, si dichiara com'e'.
          return@map window.copy(probability = rule, probabilityLow = rule, probabilityHigh = rule)
        }
      }
      val platt = if (variant == null) {
        learning.platt[window.window]
      } else {
        learning.plattByVariant[variant]?.get(window.window)
      }
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
      val blended = Analogs.blend(calibrated.probability, analogs[window.window], analogPolicy.priorStrength)
      val shift = blended - calibrated.probability
      calibrated.copy(
        probability = blended.coerceIn(0.0, 1.0),
        probabilityLow = (calibrated.probabilityLow + shift).coerceIn(0.0, 1.0),
        probabilityHigh = (calibrated.probabilityHigh + shift).coerceIn(0.0, 1.0),
      )
    }

    // Il pavimento dell'osservazione, per ultimo: dopo il modello, dopo la ricalibrazione, dopo
    // gli analoghi. Alza e basta — quello che si vede non puo' essere argomentato al ribasso da
    // una statistica, e quello che non si vede non autorizza nessuno a dire che non c'e'.
    val observed = mutableSetOf<String>()
    val floored = windows.map { window ->
      val floor = if (v3 != null) 0.0 else observation?.floorFor(window.window) ?: 0.0
      if (floor <= window.probability) {
        window
      } else {
        observed += window.window
        window.copy(
          probability = floor,
          probabilityHigh = maxOf(window.probabilityHigh, floor),
          probabilityLow = maxOf(window.probabilityLow, floor * LOW_BAND_SHARE),
        )
      }
    }

    val level = if (v3 != null) v3.thresholdsFor(tier!!).levelOf(floored) else alertLevelOf(floored)
    val verdict = NowcastVerdict(windows = floored, level = level)
    return NowcastExplanation(verdict, raw, analogs, recalibrated, observation, observed, tier, variant, ruled)
  }

  companion object {
    /**
     * Il motore dell'app col v3: il modello a livelli, gli analoghi spenti, il v2 come ripiego per
     * chi non sa dire il livello. Si costruisce fuori dal thread principale (legge le risorse).
     */
    fun v3(
      tiered: TieredNowcastModel = TieredNowcastModel.trained(),
      analogPolicy: AnalogPolicy = AnalogPolicy.OFF,
    ): NowcastEngine = NowcastEngine(
      model = NowcastModel.trained(),
      featureMeans = dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1.means,
      featureSds = dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1.sds,
      analogPolicy = analogPolicy,
      tiered = tiered,
    )

    /** La banda bassa quando comanda l'osservazione: incerti sul quanto, non sul se. */
    private const val LOW_BAND_SHARE = 0.8

    fun trained(analogPolicy: AnalogPolicy = AnalogPolicy.LEGACY): NowcastEngine = NowcastEngine(
      model = NowcastModel.trained(),
      featureMeans = dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1.means,
      featureSds = dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1.sds,
      analogPolicy = analogPolicy,
    )
  }
}

/** Le tre probabilita' grezze di un verdetto, nell'ordine delle finestre: per l'archivio degli analoghi. */
fun NowcastVerdict.rawProbabilities(): Map<String, Double> = windows.associate { it.window to it.probability }

/** Per i test e per la pagina: una finestra con la sola probabilita' cambiata. */
fun WindowVerdict.withProbability(probability: Double): WindowVerdict = copy(probability = probability)
