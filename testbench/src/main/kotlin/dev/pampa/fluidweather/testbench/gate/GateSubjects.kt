package dev.pampa.fluidweather.testbench.gate

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.FloorPoliciesV3
import dev.pampa.fluidweather.nowcast.verdict.ObservationFloors
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3Fresh
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3None
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3NoneNoClima
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3Stale
import dev.pampa.fluidweather.nowcast.verdict.WindowCoefficients
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.replay.CasePredictor
import dev.pampa.fluidweather.testbench.replay.ContextHourFloors
import dev.pampa.fluidweather.testbench.replay.EmptyLearning
import dev.pampa.fluidweather.testbench.replay.PhonePipeline
import dev.pampa.fluidweather.testbench.replay.PipelineModel
import dev.pampa.fluidweather.testbench.replay.PipelineModels
import dev.pampa.fluidweather.testbench.replay.TierBench
import dev.pampa.fluidweather.testbench.replay.V3Pipeline
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import dev.pampa.fluidweather.testbench.train.v3.CandidateArtefact
import dev.pampa.fluidweather.testbench.train.v3.LogisticTableV3
import dev.pampa.fluidweather.testbench.train.v3.TrainV3Command

/**
 * Cio' che il gate giudica: un nome, una versione con la sua etichetta, un'impronta (quale artefatto,
 * esattamente), la colonna della pipeline ad apprendimento vuoto e — per il v3 — le colonne informative
 * che la accompagnano (il grezzo, il paracadute logistico se parlano gli alberi, il v2 per confronto).
 */
interface GateSubject {
  val name: String
  val version: String
  val tag: String get() = ModelVersions.tag(model = version)

  /** L'impronta dell'artefatto giudicato; "-" dove non ce n'e' (il v2 com'e'). */
  val fingerprint: String

  /** La famiglia che parla ("-" per il v2). */
  val family: String

  /** La colonna giudicata. */
  val column: String

  /** Le colonne che si aggiungono per informazione, gia' con i loro predittori. */
  val informational: List<CasePredictor> get() = emptyList()

  /** Il predittore della colonna giudicata. */
  fun predictor(): CasePredictor

  /** La frase "Pipeline = ...". */
  fun description(): String

  /** Le avvertenze di un periodo (in campione, ecc.), una riga ciascuna. */
  fun warnings(period: TierPeriod): List<String>

  /** Il rapporto scrive il numero d'esecuzione per impronta? (Solo dove l'impronta cambia a parita' di versione.) */
  val countsByFingerprint: Boolean get() = fingerprint != "-"

  /** E' un v3 (il rapporto aggiunge i TARGET e le colonne informative)? */
  val isV3: Boolean get() = false
}

/** Il v2 e il v2r come soggetti del gate: la pipeline di sempre. */
class PipelineSubject(val model: PipelineModel) : GateSubject {
  override val name: String get() = model.name
  override val version: String get() = model.version
  override val tag: String get() = model.tag
  override val fingerprint: String get() = model.fingerprint
  override val family: String get() = "-"
  override val column: String get() = IndependenceGate.columnFor(model)

  override fun predictor(): CasePredictor = PhonePipeline(column, model, EmptyLearning, ContextHourFloors)

  override fun description(): String =
    if (model.isRecalibrated) "NowcastModel.trained() grezzo -> ricalibrazione ${model.version} -> NowcastEngine" else "NowcastModel.trained() grezzo -> NowcastEngine"

  override fun warnings(period: TierPeriod): List<String> = buildList {
    TierBench.v2InSampleNote(period)?.let { add(it) }
    if (model.isRecalibrated && period.firstMillis < TierPeriods.VALIDATION.endExclusiveMillis) {
      add("ATTENZIONE: le mappe ${model.version} sono stimate su TRAIN+VALIDATION: su ${period.name.uppercase()} sono in campione.")
    }
  }
}

/**
 * Il v3 come soggetto: un [TieredNowcastModel] (il candidato da disco o l'artefatto compilato) con la
 * famiglia che parla, i suoi pavimenti, e accanto il grezzo, il paracadute logistico (se parlano gli
 * alberi) e il v2 per confronto.
 */
class V3Subject(
  override val name: String,
  val model: TieredNowcastModel,
  override val fingerprint: String,
  /** Fin dove l'artefatto ha visto le etichette (esclusa): prima e' in campione. */
  private val trainedUntilMillis: Long,
  private val origin: String,
  /** La famiglia e' stata forzata da `--family` (e non e' quella che l'artefatto chiede). */
  val forcedFamily: Boolean = false,
) : GateSubject {
  override val version: String get() = model.version
  override val family: String get() = model.activeFamily.name
  override val column: String get() = "$name +pavimenti"
  override val isV3: Boolean get() = true

  val rawColumn: String get() = "$name grezzo"
  val fallbackColumn: String get() = "$name paracadute logistico +pavimenti"

  override fun predictor(): CasePredictor = V3Pipeline(column, model)

  override val informational: List<CasePredictor>
    get() = buildList {
      add(V3Pipeline(rawColumn, model, raw = true))
      if (model.activeFamily == ModelFamily.GBM) add(V3Pipeline(fallbackColumn, model.withFamily(ModelFamily.LOGISTICA)))
      add(PhonePipeline(V2_COLUMN, PipelineModels.V2, EmptyLearning, ContextHourFloors))
    }

  override fun description(): String =
    "TieredNowcastModel ($origin, famiglia ${model.activeFamily}${model.loadProblem?.let { ", $it" } ?: ""}) sulle 42 feature del v3 -> pavimenti ${floorsLabel()}"

  private fun floorsLabel(): String {
    val active = ContextTier.entries.filter { model.floorsFor(it).whenRainingNow.isNotEmpty() || model.floorsFor(it).radar != null }
    return if (active.isEmpty()) "nessuno" else active.joinToString(", ") { model.floorsFor(it).encode() }
  }

  override fun warnings(period: TierPeriod): List<String> = buildList {
    if (period.firstMillis < trainedUntilMillis) {
      add("ATTENZIONE: ${model.version} ha visto le etichette fino al ${dev.pampa.fluidweather.testbench.metrics.Fmt.date(trainedUntilMillis - 1)}: su ${period.name.uppercase()} e' IN CAMPIONE (gate di controllo, non una prova).")
    }
    model.loadProblem?.let { add("ATTENZIONE: $it") }
  }

  companion object {
    const val V2_COLUMN: String = "v2 +pavimenti (confronto)"
  }
}

/** I soggetti per nome (`--model`), con la famiglia facoltativa (`--family`). */
object GateSubjects {

  const val V3_CANDIDATE: String = "v3-candidate"
  const val V3: String = "v3"

  val NAMES: List<String> = PipelineModels.ALL.map { it.name } + listOf(V3_CANDIDATE, V3)

  fun isKnown(name: String): Boolean = name in NAMES

  /** Il soggetto di nome [name]; [family] forza la famiglia del v3. */
  fun create(name: String, family: ModelFamily?): GateSubject = when (name) {
    V3_CANDIDATE -> candidate(family)
    V3 -> compiled(family)
    else -> PipelineSubject(PipelineModels.ALL.first { it.name == name })
  }

  /** Il candidato su disco (`build/v3/candidate`), addestrato su TRAIN. */
  fun candidate(family: ModelFamily?, dir: java.io.File = TrainV3Command.CANDIDATE_DIR): V3Subject {
    val artefact = CandidateArtefact.read(dir)
    val chosen = family ?: artefact.requestedFamily
    val model = artefact.toModel(chosen)
    require(model.activeFamily == chosen) { "il candidato non ha la famiglia $chosen: ${model.loadProblem}" }
    return V3Subject(V3_CANDIDATE, model, artefact.fingerprint(chosen), TierPeriods.VALIDATION.firstMillis, "candidato ${dir.path}", family != null)
  }

  /** L'artefatto compilato ([TieredNowcastModel.trained]), addestrato su TRAIN + VALIDATION. */
  fun compiled(family: ModelFamily?): V3Subject {
    val shipped = TieredNowcastModel.trained()
    val model = if (family == null) shipped else shipped.withFamily(family)
    return V3Subject(V3, model, compiledFingerprint(model.activeFamily), TierPeriods.TEST.firstMillis, "artefatto compilato ${TrainedNowcastV3.VERSION}", family != null)
  }

  /** Le quattro tabelle compilate come testo del candidato: stessa tabella, stesso testo, stessa impronta. */
  fun compiledTables(): Map<ContextTier, LogisticTableV3> = ContextTier.entries.associateWith { tier ->
    val (means, sds, used, windows) = when (tier) {
      ContextTier.FRESH -> Quad(TrainedNowcastV3Fresh.means, TrainedNowcastV3Fresh.sds, TrainedNowcastV3Fresh.used, TrainedNowcastV3Fresh.windows)
      ContextTier.STALE -> Quad(TrainedNowcastV3Stale.means, TrainedNowcastV3Stale.sds, TrainedNowcastV3Stale.used, TrainedNowcastV3Stale.windows)
      ContextTier.NONE -> Quad(TrainedNowcastV3None.means, TrainedNowcastV3None.sds, TrainedNowcastV3None.used, TrainedNowcastV3None.windows)
      ContextTier.NONE_NOCLIMA -> Quad(TrainedNowcastV3NoneNoClima.means, TrainedNowcastV3NoneNoClima.sds, TrainedNowcastV3NoneNoClima.used, TrainedNowcastV3NoneNoClima.windows)
    }
    LogisticTableV3(tier, means, sds, RainWindows.ALL.map { used.getValue(it.label) }, windows.map { it.bags })
  }

  /** L'impronta dell'artefatto compilato per [family]: la stessa formula del candidato. */
  fun compiledFingerprint(family: ModelFamily): String = CandidateArtefact.fingerprintOf(
    TrainedNowcastV3.VERSION, family, ContextTier.entries.map { compiledTables().getValue(it).encode() },
    if (family == ModelFamily.GBM) TrainedNowcastV3.GBM_SHA256 else emptyMap(),
    TrainedNowcastV3.POOLED, ObservationFloors.encodeAll(FloorPoliciesV3.BY_TIER),
  )

  private data class Quad(val a: DoubleArray, val b: DoubleArray, val c: Map<String, IntArray>, val d: List<WindowCoefficients>)
}
