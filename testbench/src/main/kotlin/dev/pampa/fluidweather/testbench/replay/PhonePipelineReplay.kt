package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattParamsRecord
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.learning.LearningState
import dev.pampa.fluidweather.nowcast.learning.LearningStateBuilder
import dev.pampa.fluidweather.nowcast.learning.NowcastEngine
import dev.pampa.fluidweather.nowcast.learning.PlattCalibration
import dev.pampa.fluidweather.nowcast.learning.PlattParams
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.RecalibrationV2r
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.TierKind
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------------
// L'apprendimento: la parte che P2 cambiera'.
// ---------------------------------------------------------------------------------------------

/**
 * Cio' che un telefono ha imparato fin li', e come continua a imparare: l'archivio delle emissioni
 * e degli esiti, le mappe di Platt e quando ristimarle. Una sessione per telefono; le chiamate
 * arrivano in ordine di tempo.
 */
interface LearningSession {

  /** Lo stato con cui il motore giudica l'emissione a [nowMillis]. */
  fun stateAt(nowMillis: Long): LearningState

  /** L'emissione e' stata iscritta (dopo il verdetto): qui il telefono di oggi, se e' ora, ristima Platt. */
  fun recordIssue(issue: NowcastIssueRecord, nowMillis: Long)

  /** Un esito e' diventato noto. */
  fun recordOutcome(outcome: NowcastOutcomeRecord)

  /** Le mappe personali in vigore adesso: per la diagnosi. */
  fun currentPlatt(): Map<String, PlattParams> = emptyMap()

  /** Quante ristime hanno prodotto almeno una mappa: per la diagnosi. */
  fun refits(): Int = 0
}

/** Un modo di imparare: fabbrica di sessioni. P2 portera' il suo (Platt con guardia, per livello). */
interface LearningPolicy {
  val name: String
  fun newSession(): LearningSession
}

/** Nessun apprendimento: lo stato vuoto sempre. E' il telefono appena installato, ed e' cio' che giudica il gate. */
object EmptyLearning : LearningPolicy {
  override val name: String = "vuoto"

  override fun newSession(): LearningSession = object : LearningSession {
    override fun stateAt(nowMillis: Long): LearningState = LearningState.EMPTY
    override fun recordIssue(issue: NowcastIssueRecord, nowMillis: Long) = Unit
    override fun recordOutcome(outcome: NowcastOutcomeRecord) = Unit
  }
}

/**
 * L'apprendimento del telefono di oggi, riga per riga:
 *
 * - lo stato si costruisce con [LearningStateBuilder.build] dalle mappe salvate e dall'archivio degli
 *   ultimi due anni (`NowcastUseCase.learningState`); la cache di un'ora del telefono qui non conta,
 *   le emissioni distano tre ore;
 * - l'emissione si iscrive con le probabilita' **prima** della ricalibrazione personale (sul telefono
 *   di oggi sono quelle grezze del modello) e subito dopo, se sono passate sei ore dall'ultima,
 *   si ristima (`BackgroundCycle.maybeRefitPlatt`): corpus = le emissioni con contesto se sono almeno
 *   2 x [PlattCalibration.MIN_SAMPLES], altrimenti tutte; [LearningStateBuilder.samples] e
 *   [PlattCalibration.fit] per finestra; le mappe nuove sostituiscono le vecchie finestra per
 *   finestra, quelle non ristimate restano (`LearningStore.save`);
 * - gli analoghi sono quelli di [LearningStateBuilder.build]: tutte le emissioni iscritte, con gli esiti noti.
 */
object TodayLearning : LearningPolicy {
  override val name: String = "telefono-oggi"

  /** Come `LearningRepository.KEEP_MILLIS` (core-data, modulo Android: qui non si vede). */
  const val KEEP_MILLIS: Long = 2L * 365 * 24 * 3_600_000L

  /** Come `BackgroundCycle.REFIT_INTERVAL_MILLIS`. */
  const val REFIT_INTERVAL_MILLIS: Long = 6 * 3_600_000L

  override fun newSession(): LearningSession = Session()

  private class Session : LearningSession {
    private val issues = ArrayList<NowcastIssueRecord>()
    private val outcomes = ArrayList<NowcastOutcomeRecord>()
    private val platt = LinkedHashMap<String, PlattParamsRecord>()
    private var lastFitMillis = 0L
    private var refits = 0

    override fun stateAt(nowMillis: Long): LearningState {
      val since = nowMillis - KEEP_MILLIS
      return LearningStateBuilder.build(
        platt = LinkedHashMap(platt),
        issues = issues.filter { it.issuedAtMillis >= since },
        outcomes = outcomes.filter { it.issuedAtMillis >= since },
      )
    }

    override fun recordIssue(issue: NowcastIssueRecord, nowMillis: Long) {
      issues += issue
      maybeRefit(nowMillis)
    }

    override fun recordOutcome(outcome: NowcastOutcomeRecord) {
      outcomes += outcome
    }

    override fun currentPlatt(): Map<String, PlattParams> = platt.mapValues { (_, record) -> PlattParams(record.a, record.b) }

    override fun refits(): Int = refits

    private fun maybeRefit(nowMillis: Long) {
      if (nowMillis - lastFitMillis < REFIT_INTERVAL_MILLIS) return
      val since = nowMillis - KEEP_MILLIS
      val kept = issues.filter { it.issuedAtMillis >= since }
      val known = outcomes.filter { it.issuedAtMillis >= since }
      val withContext = kept.filter { FeatureExtractor.hasContext(it.features.toDoubleArray()) }
      val corpus = if (withContext.size >= PlattCalibration.MIN_SAMPLES * 2) withContext else kept
      val fitted = WINDOW_LABELS.mapNotNull { window ->
        val samples = LearningStateBuilder.samples(window, corpus, known)
        PlattCalibration.fit(samples)?.let { PlattParamsRecord(window, it.a, it.b, samples.size, nowMillis) }
      }
      for (record in fitted) platt[record.window] = record
      if (fitted.isNotEmpty()) refits++
      lastFitMillis = nowMillis
    }
  }

  private val WINDOW_LABELS = RainWindows.ALL.map { it.label }
}

// ---------------------------------------------------------------------------------------------
// I pavimenti dell'osservazione.
// ---------------------------------------------------------------------------------------------

/** Cosa il telefono vede fuori a un'emissione: la [RainObservation] che alza il verdetto, o null. */
fun interface FloorPolicy {
  fun observation(view: IssueView): RainObservation?
}

/** Nessuna osservazione: il verdetto resta quello del modello (e dell'apprendimento). */
object NoFloors : FloorPolicy {
  override fun observation(view: IssueView): RainObservation? = null
}

/**
 * Il pavimento di oggi con un'osservazione oraria al posto del quarto d'ora: "piove adesso" se
 * l'ultimo slot chiuso del contesto del livello ha almeno [RainObservation.RAINING_FROM_MM_PER_HOUR]
 * mm, e allora [RainObservation.raining] con i pavimenti di sempre; altrimenti [RainObservation.dry],
 * che non alza niente. Senza contesto (NONE, NONE_NOCLIMA) nessuna osservazione: offline non c'e' ne'
 * quarto d'ora ne' radar.
 *
 * E' un'approssimazione dichiarata: sul telefono il quarto d'ora e' quello appena concluso, qui e'
 * l'ora chiusa che il contesto conosce — in FRESH vecchia fino a due ore e mezza, in STALE fino a
 * tredici. E in STALE il telefono vero leggerebbe dal bundle vecchio un quarto d'ora *previsto*;
 * qui legge l'ora osservata di allora, cioe' una persistenza ritardata.
 */
object ContextHourFloors : FloorPolicy {
  const val SOURCE: String = "contesto orario"

  override fun observation(view: IssueView): RainObservation? {
    val lastHour = view.contextLastHourMm ?: return null
    if (!view.kind.hasContext || lastHour.isNaN()) return null
    return if (lastHour >= RainObservation.RAINING_FROM_MM_PER_HOUR) {
      RainObservation.raining(lastHour, SOURCE)
    } else {
      RainObservation.dry(SOURCE)
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Il modello della pipeline.
// ---------------------------------------------------------------------------------------------

/**
 * Il modello che parla nella pipeline: il [NowcastModel] spedito, piu' (facoltativa) una
 * ricalibrazione fissa per finestra e famiglia di livello (v2r) fra il modello e l'apprendimento.
 *
 * La ricalibrazione entra nel vero [NowcastEngine] senza toccarlo: e' una mappa di Platt, e due
 * mappe di Platt in fila sono ancora una mappa di Platt — sigma(a2 logit(sigma(a1 x + b1)) + b2) =
 * sigma(a2 a1 x + a2 b1 + b2) ([compose]) — quindi lo stato di apprendimento che il motore riceve
 * porta, per ogni finestra, la ricalibrazione composta con la mappa personale (se c'e'). La mappa
 * personale si stima sulle probabilita' gia' ricalibrate ([recalibrate]), che e' cio' che
 * l'emissione iscrive. Senza ricalibrazione tutto e' identico al telefono di oggi.
 */
class PipelineModel(
  /** Il nome breve per le colonne e la riga di comando ("v2", "v2r"). */
  val name: String,
  /** La versione del modello per l'etichetta di [ModelVersions]. */
  val version: String,
  val nowcast: NowcastModel = NowcastModel.trained(),
  featureMeans: DoubleArray = TrainedNowcastV1.means,
  featureSds: DoubleArray = TrainedNowcastV1.sds,
  private val recalibration: ((windowLabel: String, hasContext: Boolean) -> PlattParams)? = null,
) {

  /** Il motore vero, lo stesso del telefono. */
  val engine: NowcastEngine = NowcastEngine(nowcast, featureMeans, featureSds)

  /** L'etichetta di versione con cui si registra (e si giudica) cio' che dice. */
  val tag: String get() = ModelVersions.tag(model = version)

  val isRecalibrated: Boolean get() = recalibration != null

  /**
   * L'impronta della ricalibrazione fissa: le sei mappe (famiglia x finestra) come testo, SHA-256,
   * prime dieci cifre esadecimali; "-" senza ricalibrazione. La versione v2r porta la data del v2 su
   * cui poggia, non le mappe: una ristima su altri casi avrebbe la stessa versione e mappe diverse.
   * Il gate la scrive accanto alla versione, nel rapporto e nel registro, cosi' si sa *quale*
   * artefatto e' stato giudicato.
   */
  val fingerprint: String by lazy {
    val fixed = recalibration ?: return@lazy "-"
    val text = buildString {
      for (hasContext in listOf(true, false)) {
        for (window in RainWindows.ALL) {
          val map = fixed(window.label, hasContext)
          append(window.label).append('|').append(hasContext).append('|').append(map.a).append('|').append(map.b).append(';')
        }
      }
    }
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
      .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
      .take(10)
  }

  /** La probabilita' grezza del modello dopo la ricalibrazione fissa (identita' se non c'e'). */
  fun recalibrate(windowLabel: String, hasContext: Boolean, probability: Double): Double =
    recalibration?.invoke(windowLabel, hasContext)?.apply(probability) ?: probability

  /** Lo stato che il motore riceve: la ricalibrazione fissa composta con quella personale. */
  fun effectiveLearning(state: LearningState, hasContext: Boolean): LearningState {
    val fixed = recalibration ?: return state
    val platt = RainWindows.ALL.associate { window ->
      val first = fixed(window.label, hasContext)
      window.label to (state.platt[window.label]?.let { compose(it, first) } ?: first)
    }
    return state.copy(platt = platt)
  }

  companion object {
    /** La mappa [outer] dopo la mappa [inner]: ancora una mappa di Platt (a meno dei ritagli a 1e-4). */
    fun compose(outer: PlattParams, inner: PlattParams): PlattParams =
      PlattParams(outer.a * inner.a, outer.a * inner.b + outer.b)
  }
}

// ---------------------------------------------------------------------------------------------
// La pipeline come colonna del replay.
// ---------------------------------------------------------------------------------------------

/** Cosa ha fatto la pipeline di un telefono (una localita', un livello): per capire, non per giudicare. */
class PipelineSessionStats(val location: String, val kind: TierKind) {
  var issues: Int = 0
  var outcomes: Int = 0
  var rainingObservations: Int = 0

  /** Per finestra: emissioni in cui il pavimento ha alzato la probabilita'. */
  val floorsRaised: IntArray = IntArray(RainWindows.ALL.size)

  /** Per finestra: emissioni in cui una mappa personale era attiva. */
  val plattActive: IntArray = IntArray(RainWindows.ALL.size)

  /** Per finestra: emissioni in cui gli analoghi hanno spostato la probabilita'. */
  val analogsUsed: IntArray = IntArray(RainWindows.ALL.size)
  var refits: Int = 0
  var finalPlatt: Map<String, PlattParams> = emptyMap()
}

/** La diagnosi di tutte le sessioni di una pipeline; scritta dai thread delle localita', letta alla fine. */
class PipelineDiagnostics {
  private val sessions = ConcurrentHashMap<Pair<String, TierKind>, PipelineSessionStats>()

  internal fun open(location: String, kind: TierKind): PipelineSessionStats =
    PipelineSessionStats(location, kind).also { sessions[location to kind] = it }

  fun of(location: String, kind: TierKind): PipelineSessionStats? = sessions[location to kind]
}

/**
 * La pipeline del telefono come colonna del [TierReplayer]: verdetto grezzo del modello -> vero
 * [NowcastEngine] con lo stato di [learning] (ricalibrazione personale e analoghi) -> pavimenti di
 * [floors]. La colonna e' la probabilita' finale, quella che il telefono mostra.
 *
 * Un telefono per (localita', livello), nuovo all'inizio del periodo: impara solo da cio' che vede
 * durante il periodo, e gli esiti gli arrivano dalla [FinalityQueue] del replay, mai prima che il
 * pannello li consideri definitivi.
 */
class PhonePipeline(
  val column: String,
  val model: PipelineModel,
  val learning: LearningPolicy,
  val floors: FloorPolicy,
  val diagnostics: PipelineDiagnostics? = null,
) : CasePredictor {

  override val columns: List<String> = listOf(column)

  override fun session(input: LocationInputs, kind: TierKind): CaseSession =
    Session(learning.newSession(), diagnostics?.open(input.location.name, kind))

  private inner class Session(
    private val learner: LearningSession,
    private val stats: PipelineSessionStats?,
  ) : CaseSession {

    override fun onFinalOutcome(outcome: FinalOutcome) {
      learner.recordOutcome(NowcastOutcomeRecord(outcome.issueMillis, outcome.window.label, outcome.rained))
      stats?.let { it.outcomes++ }
    }

    override fun predict(view: IssueView): Array<DoubleArray> {
      val hasContext = view.kind.hasContext
      val state = learner.stateAt(view.issueMillis)
      val personal = state.platt.keys
      val observation = floors.observation(view)
      val explanation = model.engine.evaluate(view.features, model.effectiveLearning(state, hasContext), observation)

      // Si iscrive cio' che entra nella ricalibrazione personale: il grezzo del modello (oggi), o il
      // grezzo gia' ricalibrato (v2r). Come sul telefono, 0 se la finestra mancasse.
      val raw = explanation.rawVerdict
      fun entering(label: String): Double =
        model.recalibrate(label, hasContext, raw.forWindow(label)?.probability ?: 0.0)
      learner.recordIssue(
        NowcastIssueRecord(
          issuedAtMillis = view.issueMillis,
          features = view.features.toList(),
          rawProbability01 = entering(RainWindows.ZERO_ONE.label),
          rawProbability13 = entering(RainWindows.ONE_THREE.label),
          rawProbability36 = entering(RainWindows.THREE_SIX.label),
        ),
        view.issueMillis,
      )

      stats?.let { s ->
        s.issues++
        if (observation?.rainingNow == true) s.rainingObservations++
        for ((w, window) in RainWindows.ALL.withIndex()) {
          if (window.label in explanation.observed) s.floorsRaised[w]++
          if (window.label in personal) s.plattActive[w]++
          if (window.label in explanation.analogs) s.analogsUsed[w]++
        }
        s.refits = learner.refits()
        s.finalPlatt = learner.currentPlatt()
      }

      return arrayOf(
        DoubleArray(RainWindows.ALL.size) { w ->
          explanation.verdict.forWindow(RainWindows.ALL[w].label)?.probability ?: Double.NaN
        },
      )
    }
  }
}

/**
 * I modelli che la pipeline sa far parlare, per nome (`--model`): il v2 spedito oggi e la sua
 * ricalibrazione v2r sull'etichetta del pannello ([RecalibrationV2r], generata da `recalibrate-v2r`).
 */
object PipelineModels {

  /** Il modello spedito oggi, com'e'. */
  val V2: PipelineModel = PipelineModel(name = "v2", version = TrainedNowcastV1.VERSION)

  /** Il v2 con la ricalibrazione v2r fra il modello e l'apprendimento personale. */
  val V2R: PipelineModel = PipelineModel(
    name = "v2r",
    version = RecalibrationV2r.VERSION,
    recalibration = { window, hasContext ->
      RecalibrationV2r.coefficients(window, hasContext).let { PlattParams(it.a, it.b) }
    },
  )

  val ALL: List<PipelineModel> = listOf(V2, V2R)

  /** Il modello di nome [name] ("v2" se null); null se il nome non esiste. */
  fun byName(name: String?): PipelineModel? = if (name == null) V2 else ALL.firstOrNull { it.name == name }
}
