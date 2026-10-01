package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.features.NowcastContext
import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.baselines.LegacyRules
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.tiers.AsOfContext
import dev.pampa.fluidweather.testbench.tiers.IssueSimulator
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Un caso giudicato: una emissione, un livello di contesto, una finestra, e la probabilita' di ogni
 * predittore (in [TierReplayResult.predictorNames], NaN dove il predittore non si applica al livello:
 * la persistenza non esiste senza contesto). Tutti i predittori di un caso rispondono alla stessa
 * domanda sulla stessa emissione: e' cio' che rende i confronti appaiati.
 *
 * Gli esiti sono due, uno per verita' ([TruthKind]): -1 = la finestra non si puo' giudicare con
 * quella verita', 0 = asciutta, 1 = bagnata. Si tiene un solo record con entrambi invece di due,
 * cosi' "stesso caso, due verita'" e' vero per costruzione.
 */
class CaseRecord(
  val location: String,
  /** L'istante di emissione del telefono: `t0` meno il minuto casuale. */
  val issueMillis: Long,
  /** L'ancora delle finestre: l'emissione arrotondata per eccesso, cioe' `t0`. */
  val anchorMillis: Long,
  val kind: TierKind,
  /** Indice in [RainWindows.ALL]. */
  val windowIndex: Int,
  private val panelOutcome: Int,
  private val era5Outcome: Int,
  val probabilities: DoubleArray,
) {
  val window: RainWindow get() = RainWindows.ALL[windowIndex]

  /** L'esito sotto [truth]: -1 ingiudicabile, 0 asciutta, 1 bagnata. */
  fun outcome(truth: TruthKind): Int = when (truth) {
    TruthKind.PANEL -> panelOutcome
    TruthKind.ERA5 -> era5Outcome
  }
}

/** Un caso srotolato per predittore: la forma "riga di log" (localita', emissione, livello, finestra, predittore, p, esiti). */
data class PredictionRow(
  val location: String,
  val issueMillis: Long,
  val anchorMillis: Long,
  val kind: TierKind,
  val window: String,
  val predictor: String,
  val probability: Double,
  val panelOutcome: Boolean?,
  val era5Outcome: Boolean?,
)

/** Quanto di una localita' e' entrato nel replay e perche' il resto no: niente sparisce in silenzio. */
class LocationCoverage(
  val location: String,
  /** Le emissioni `t0` del periodo. */
  val anchors: Int,
  /** Emissioni scartate perche' nessuna finestra e' giudicabile ne' dal pannello ne' da ERA5. */
  val noTruth: Int,
  /** Emissioni scartate perche' il telefono non ha un verdetto barometrico (meno di 13 ore di storia pulita). */
  val noBarometer: Int,
  /** Emissioni tenute. */
  val issues: Int,
  /** Per livello con contesto: emissioni senza contesto (riga best_match assente) che il livello non puo' giocare. */
  val droppedNoContext: Map<TierKind, Int>,
  /** Casi (emissione x livello x finestra) in cui la tendenza del telefono mancava e la regola barometrica ha detto la climatologia. */
  val barometricFallbacks: Int,
  val records: Int,
)

/** Tutto il replay di un periodo: i casi, dove e' stato scartato qualcosa, e le baseline usate. */
class TierReplayResult(
  val period: TierPeriod,
  val predictorNames: List<String>,
  val records: List<CaseRecord>,
  val coverage: List<LocationCoverage>,
  val baselines: Map<String, HonestBaselines>,
  /** Localita' non giocate e perche' (dati mancanti, nessuna storia pre-periodo). */
  val skipped: Map<String, String>,
) {

  /** La posizione di un predittore in [CaseRecord.probabilities], o -1 se il replay non lo ha. */
  fun indexOf(predictor: String): Int = predictorNames.indexOf(predictor)

  /** Le localita' effettivamente giocate, nell'ordine dei dati. */
  val locations: List<String> get() = coverage.map { it.location }

  /** I casi srotolati per predittore; salta i predittori che non si applicano (NaN). */
  fun predictionRows(): Sequence<PredictionRow> = records.asSequence().flatMap { record ->
    predictorNames.indices.asSequence().mapNotNull { k ->
      val p = record.probabilities[k]
      if (p.isNaN()) return@mapNotNull null
      PredictionRow(
        location = record.location,
        issueMillis = record.issueMillis,
        anchorMillis = record.anchorMillis,
        kind = record.kind,
        window = record.window.label,
        predictor = predictorNames[k],
        probability = p,
        panelOutcome = record.outcome(TruthKind.PANEL).let { if (it < 0) null else it == 1 },
        era5Outcome = record.outcome(TruthKind.ERA5).let { if (it < 0) null else it == 1 },
      )
    }
  }
}

/**
 * Il replay onesto, per livello di contesto.
 *
 * Per ogni localita', ogni emissione `t0` (una ogni tre ore) e ogni livello di [TierKind]:
 *
 * 1. **Emissione.** `t0 - U(0, 60) min` ([TierScenarios]); l'ancora delle finestre resta `t0`.
 * 2. **Barometro.** I campioni del telefono ([SamplingProfile.TELEFONO], pressione di stazione di
 *    ERA5 con il rumore del sensore) per le 24 ore prima dell'emissione, puliti una volta sola
 *    dalla pipeline di pulizia: la parte barometrica e' la stessa per tutti i livelli, cambia il
 *    contesto. Se ne occupa l'[IssueSimulator], lo stesso che costruisce le righe di addestramento del v3.
 * 3. **Contesto.** Com'era al fetch (`emissione - eta'`), da best_match, solo slot chiusi
 *    ([dev.pampa.fluidweather.testbench.tiers.ContextSources]); i livelli NONE non ne hanno.
 * 4. **Predittori.** sempre-0, climatologia, persistenza, regola barometrica (tutti [HonestBaselines],
 *    fuori campione), le tre righe informative dei vecchi numeri fissi, se [model] e' dato il
 *    verdetto grezzo del modello, e le colonne dei predittori in piu' ([extras]: la pipeline del
 *    telefono, le PoP dei provider). Senza baseline ([withBaselines] = false) restano solo modello ed
 *    extra: e' il modo di giocare un periodo che non ha storia prima (TRAIN), dove si impara e basta.
 * 5. **Esiti.** Le finestre si giudicano con [RainWindows] sulla verita' PANEL (primaria) e su ERA5
 *    (secondaria): stessa definizione, due sorgenti. Ai predittori in piu' gli esiti del pannello
 *    arrivano solo dalla [FinalityQueue], alla prima emissione a cui sono definitivi.
 *
 * Il ciclo di una localita' e' sequenziale e non condivide stato: le localita' corrono su un pool di
 * thread fisso e i risultati si ricompongono nell'ordine dei dati, quindi l'output e' identico con
 * uno o con dodici thread.
 */
class TierReplayer(
  private val period: TierPeriod,
  /** Il modello da mettere in classifica accanto alle baseline; null = solo baseline. */
  private val model: NowcastModel? = null,
  private val kinds: List<TierKind> = TierKind.entries,
  private val threads: Int = defaultThreads(),
  private val log: (String) -> Unit = {},
  /** Le colonne in piu', dopo quella del modello: vedi [CasePredictor]. */
  private val extras: List<CasePredictor> = emptyList(),
  /** false = niente baseline e niente storia richiesta (per i periodi su cui si impara). */
  private val withBaselines: Boolean = true,
  /** true = il barometro sintetico estrapola dalle ore chiuse invece di interpolare: solo per la misura di sensibilita'. */
  private val causalPressure: Boolean = false,
) {

  /** I predittori di questo replay, nell'ordine delle colonne di [CaseRecord.probabilities]. */
  val predictorNames: List<String> = buildList {
    if (withBaselines) {
      add(PredictorNames.SEMPRE_0)
      add(PredictorNames.CLIMATOLOGIA)
      add(PredictorNames.PERSISTENZA)
      add(PredictorNames.REGOLA_BAROMETRICA)
      add(PredictorNames.INFO_CLIMA_FUTURO)
      add(PredictorNames.INFO_PERSISTENZA_FISSA)
      add(PredictorNames.INFO_REGOLA_FISSA)
    }
    if (model != null) add(PredictorNames.MODELLO)
    for (extra in extras) addAll(extra.columns)
  }

  init {
    require(predictorNames.toSet().size == predictorNames.size) { "colonne duplicate: $predictorNames" }
  }

  private val idxSempre0 = predictorNames.indexOf(PredictorNames.SEMPRE_0)
  private val idxClimatologia = predictorNames.indexOf(PredictorNames.CLIMATOLOGIA)
  private val idxPersistenza = predictorNames.indexOf(PredictorNames.PERSISTENZA)
  private val idxBarometrica = predictorNames.indexOf(PredictorNames.REGOLA_BAROMETRICA)
  private val idxInfoFuturo = predictorNames.indexOf(PredictorNames.INFO_CLIMA_FUTURO)
  private val idxInfoPersistenza = predictorNames.indexOf(PredictorNames.INFO_PERSISTENZA_FISSA)
  private val idxInfoRegola = predictorNames.indexOf(PredictorNames.INFO_REGOLA_FISSA)
  private val idxModello = predictorNames.indexOf(PredictorNames.MODELLO)

  /** La prima colonna di ogni predittore in piu'. */
  private val extraOffsets: IntArray = IntArray(extras.size) { predictorNames.indexOf(extras[it].columns.first()) }

  fun replay(inputs: List<LocationInputs>): TierReplayResult {
    val baselines = if (withBaselines) HonestBaselines.buildAll(period, inputs) else emptyMap()
    val skipped = LinkedHashMap<String, String>()
    val playable = inputs.filter { input ->
      if (!withBaselines) return@filter true
      val honest = baselines.getValue(input.location.name)
      if (!honest.hasHistory) {
        skipped[input.location.name] = "nessuna storia del pannello prima di ${period.name}: le baseline non si tarano"
      }
      honest.hasHistory
    }

    val pool = Executors.newFixedThreadPool(threads.coerceIn(1, maxOf(1, playable.size)))
    val outcomes = try {
      val futures = playable.map { input ->
        pool.submit(Callable { replayLocation(input, baselines[input.location.name]) })
      }
      futures.map { future ->
        try {
          future.get()
        } catch (e: ExecutionException) {
          throw e.cause ?: e
        }
      }
    } finally {
      pool.shutdown()
    }

    return TierReplayResult(
      period = period,
      predictorNames = predictorNames,
      records = outcomes.flatMap { it.records },
      coverage = outcomes.map { it.coverage },
      baselines = baselines,
      skipped = skipped,
    )
  }

  private class LocationOutcome(val records: List<CaseRecord>, val coverage: LocationCoverage)

  private fun replayLocation(input: LocationInputs, honest: HonestBaselines?): LocationOutcome {
    val name = input.location.name
    val windows = RainWindows.ALL
    val simulator = IssueSimulator(input, causalPressure)
    val futureRates = if (withBaselines) periodBaseRates(input) else DoubleArray(windows.size) { Double.NaN }

    // Un telefono per scenario e per predittore in piu', e la coda degli esiti che diventano noti.
    val sessionContext = SessionContext(honest)
    val sessions: List<Map<TierKind, CaseSession>> =
      extras.map { extra -> kinds.associateWith { extra.session(input, it, sessionContext) } }
    val finality: Map<TierKind, FinalityQueue> = if (extras.isEmpty()) emptyMap() else kinds.associateWith { FinalityQueue() }

    val records = ArrayList<CaseRecord>()
    val dropped = kinds.filter { it.hasContext }.associateWith { 0 }.toMutableMap()
    var noTruth = 0
    var noBarometer = 0
    var issues = 0
    var fallbacks = 0
    val anchors = period.issueAnchors()
    val needFeatures = model != null || extras.isNotEmpty()

    for (t0 in anchors) {
      val issue = simulator.issueMillis(t0)

      val panelOutcomes = IntArray(windows.size) { encode(input.panelTruth.outcome(issue, windows[it])) }
      val era5Outcomes = IntArray(windows.size) { encode(input.era5Truth.outcome(issue, windows[it])) }
      if (panelOutcomes.all { it < 0 } && era5Outcomes.all { it < 0 }) {
        noTruth++
        continue
      }

      // Il barometro e' lo stesso per tutti i livelli: se manca qui, manca per tutti (meno di 24 campioni
      // o di 13 ore di storia pulita).
      val sim = simulator.simulate(t0)
      if (sim == null) {
        noBarometer++
        continue
      }
      issues++
      val trend = sim.trendHpaPerHour
      val kalmanTrend = sim.kalmanTrendHpaPerHour

      for (kind in kinds) {
        var context: NowcastContext? = null
        var lastHourMm: Double? = null
        var asOf: AsOfContext? = null
        if (kind.hasContext) {
          asOf = simulator.context(kind, t0, issue)
          lastHourMm = asOf?.context?.rainLastHourMm
          if (asOf == null || lastHourMm == null) {
            dropped[kind] = dropped.getValue(kind) + 1
            continue
          }
          context = asOf.context
        }
        val features = when {
          !needFeatures -> null
          context == null -> sim.barometerOnly
          else -> FeatureExtractor.extract(sim.cleaning, context, sim.normalHpa, issue)
        }
        val verdict = if (model != null && features != null) model.verdict(features) else null

        // I predittori in piu' vedono ogni emissione giocata del livello, prima del conto per finestra:
        // prima ricevono gli esiti diventati definitivi, poi rispondono, poi l'emissione entra in coda.
        val extraProbabilities: List<Array<DoubleArray>> = if (extras.isEmpty() || features == null) {
          emptyList()
        } else {
          val queue = finality.getValue(kind)
          queue.release(issue) { outcome -> sessions.forEach { it.getValue(kind).onFinalOutcome(outcome) } }
          val view = IssueView(
            input, kind, t0, issue, features, context, lastHourMm,
            cleaning = sim.cleaning, normalHpa = sim.normalHpa, asOf = asOf, honest = honest,
          )
          val answers = sessions.map { it.getValue(kind).predict(view) }
          queue.enqueue(issue, panelOutcomes)
          answers
        }

        for ((w, window) in windows.withIndex()) {
          val probabilities = DoubleArray(predictorNames.size) { Double.NaN }
          if (withBaselines) {
            val baselines = honest!!
            val clima = baselines.climatology(window, issue, kind.tier) ?: continue
            probabilities[idxSempre0] = 0.0
            probabilities[idxClimatologia] = clima
            if (kind.hasContext) {
              probabilities[idxPersistenza] =
                baselines.persistence(window, issue, lastHourMm, asOf!!.slotEndMillis, kind.tier) ?: continue
            }
            val barometric = baselines.barometric(window, issue, trend, kind.tier)
            if (barometric == null) fallbacks++
            probabilities[idxBarometrica] = barometric ?: clima

            probabilities[idxInfoFuturo] = futureRates[w]
            if (kind.hasContext) {
              probabilities[idxInfoPersistenza] = LegacyRules.fixedPersistence(lastHourMm, clima)
            }
            probabilities[idxInfoRegola] = LegacyRules.fixedBarometric(clima, kalmanTrend)
          }
          if (idxModello >= 0) {
            probabilities[idxModello] = verdict?.forWindow(window.label)?.probability ?: Double.NaN
          }
          for ((e, answer) in extraProbabilities.withIndex()) {
            for (c in answer.indices) probabilities[extraOffsets[e] + c] = answer[c][w]
          }
          for (k in probabilities.indices) {
            if (!probabilities[k].isNaN()) probabilities[k] = probabilities[k].coerceIn(0.0, 1.0)
          }
          records += CaseRecord(
            location = name,
            issueMillis = issue,
            anchorMillis = t0,
            kind = kind,
            windowIndex = w,
            panelOutcome = panelOutcomes[w],
            era5Outcome = era5Outcomes[w],
            probabilities = probabilities,
          )
        }
      }
    }

    val coverage = LocationCoverage(
      location = name,
      anchors = anchors.size,
      noTruth = noTruth,
      noBarometer = noBarometer,
      issues = issues,
      droppedNoContext = dropped,
      barometricFallbacks = fallbacks,
      records = records.size,
    )
    log("  $name: ${records.size} casi da $issues emissioni (${anchors.size} t0)")
    return LocationOutcome(records, coverage)
  }

  /**
   * Il tasso bagnato dell'intero periodo giudicato dal pannello, per finestra: e' il vecchio
   * "climatologia" del banco, che vedeva il proprio futuro. Riga informativa, mai una baseline.
   */
  private fun periodBaseRates(input: LocationInputs): DoubleArray {
    val windows = RainWindows.ALL
    val wet = IntArray(windows.size)
    val total = IntArray(windows.size)
    var issue = period.firstMillis
    while (issue < period.endExclusiveMillis) {
      for ((w, window) in windows.withIndex()) {
        val outcome = input.panelTruth.outcome(issue, window) ?: continue
        total[w]++
        if (outcome) wet[w]++
      }
      issue += RainWindows.HOUR_MILLIS
    }
    return DoubleArray(windows.size) { if (total[it] == 0) Double.NaN else wet[it].toDouble() / total[it] }
  }

  private fun encode(outcome: Boolean?): Int = when (outcome) {
    null -> -1
    false -> 0
    true -> 1
  }

  companion object {
    /** Ventiquattro ore: la finestra di storia che il telefono tiene. */
    const val HISTORY_MILLIS: Long = 24 * 3_600_000L

    /** Sotto questa storia il filtro non ha ancora un'opinione seria: si salta l'emissione (come il Replayer). */
    const val MIN_HISTORY_SAMPLES = 24

    /** La tendenza a tre ore che misura il telefono: e' la colonna su cui si applica la regola barometrica. */
    const val TREND_FEATURE = "tendenza-3h"

    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 10)

    /**
     * I campioni che il telefono ha all'emissione: le [HISTORY_MILLIS] prima, e niente dopo. Il giro di
     * campionamento parte al quarto d'ora e la raffica dura cinque secondi: un giro cominciato un
     * istante prima dell'emissione avrebbe campioni *dopo* l'emissione, che il telefono non ha ancora.
     */
    internal fun phoneHistory(synthesizer: SampleSynthesizer, issueMillis: Long): List<PressureSample> =
      synthesizer.samplesBetween(issueMillis - HISTORY_MILLIS, issueMillis).filter { it.timestampMillis <= issueMillis }
  }
}
