package dev.pampa.fluidweather.testbench

import dev.pampa.fluidweather.testbench.audit.LabelAudit
import dev.pampa.fluidweather.testbench.data.BenchLocations
import dev.pampa.fluidweather.testbench.data.CallBudget
import dev.pampa.fluidweather.testbench.data.HfProducts
import dev.pampa.fluidweather.testbench.data.HistoricalForecastFetcher
import dev.pampa.fluidweather.testbench.data.OpenMeteoFetcher
import dev.pampa.fluidweather.testbench.data.OpenMeteoHttp
import dev.pampa.fluidweather.testbench.data.RunArchive
import dev.pampa.fluidweather.testbench.data.RunPresets
import dev.pampa.fluidweather.testbench.data.SingleRunFetcher
import dev.pampa.fluidweather.testbench.data.StabilityProbe
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.gate.GateSubjects
import dev.pampa.fluidweather.testbench.gate.IndependenceGate
import dev.pampa.fluidweather.testbench.metrics.Contingency
import dev.pampa.fluidweather.testbench.metrics.Probabilistic
import dev.pampa.fluidweather.testbench.metrics.Verification
import dev.pampa.fluidweather.testbench.recalibration.V2rRecalibration
import dev.pampa.fluidweather.testbench.replay.PhonePipelineBench
import dev.pampa.fluidweather.testbench.replay.PipelineModels
import dev.pampa.fluidweather.testbench.replay.Replayer
import dev.pampa.fluidweather.testbench.replay.SamplingProfile
import dev.pampa.fluidweather.testbench.replay.TaggedVerification
import dev.pampa.fluidweather.testbench.replay.TierBench
import dev.pampa.fluidweather.testbench.stages.StageBenches
import dev.pampa.fluidweather.testbench.train.TrainCommand
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3
import dev.pampa.fluidweather.testbench.gate.V3Subject
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import dev.pampa.fluidweather.testbench.train.v3.ArtefactWriterV3
import dev.pampa.fluidweather.testbench.train.v3.CandidateArtefact
import dev.pampa.fluidweather.testbench.train.v3.FloorsV3Command
import dev.pampa.fluidweather.testbench.train.v3.TrainV3Command
import dev.pampa.fluidweather.testbench.train.v3.V3Levers
import dev.pampa.fluidweather.testbench.train.v3.V3RowsCommand
import dev.pampa.fluidweather.testbench.train.v3.V3Stage
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Il banco di prova da riga di comando:
 *
 *   gradlew :testbench:run --args="fetch"     scarica gli archivi storici (10 localita', 2 anni)
 *   gradlew :testbench:run --args="replay"    tabellone POD/FAR/CSI/Brier per predittore/finestra
 *   gradlew :testbench:run --args="stages"    metriche per stadio: riduzione, marea, screening, tendenza
 *   gradlew :testbench:run --args="all"       tutte e tre, in fila
 *
 * I dati per la revisione del nowcast (P3a), tutti riprendibili e governati dal budget di chiamate
 * (`data/.callbudget.log`, al massimo 9.000 al giorno):
 *
 *   --args="fetch-hf panel|context|providers|all"   serie stitched dell'historical-forecast-api -> data/hf/
 *   --args="fetch-hf15"                              il quarto d'ora di best_match (4 localita') -> data/hf15/
 *   --args="fetch-runs [--probe] [--model m]"        corse passate della Single Runs API -> data/runs/
 *                                                     (anche: --from yyyyMMddHH --to yyyyMMddHH --limit n)
 *   --args="probe-stability"                         istantanea del pannello a Sesto -> data/stability/
 *
 * L'audit dell'etichetta "bagnato" su quei dati (pannello contro ERA5 e vecchio consenso):
 *
 *   --args="label-audit"                             tassi base, accordo, stagioni, copertura, climatologia
 *                                                     e baseline fuori campione -> reports/label-audit.txt
 *
 * Il banco onesto (P3b): baseline fuori campione e replay per livello di contesto, su VALIDATION o TEST
 * (il periodo e' obbligatorio), verita' primaria il pannello, secondaria ERA5:
 *
 *   --args="baselines <validation|test>"             solo le baseline -> reports/baselines-<periodo>.txt
 *   --args="replay-tiers <validation|test>"          baseline + nowcast spedito + PoP dei provider con fuga (solo FRESH)
 *                                                     -> reports/replay-tiers-<periodo>.txt
 *   --args="replay-tiers <periodo> --barometro-causale"  sensibilita': pressione sintetica senza anticipo sull'ora dopo
 *                                                     -> reports/replay-tiers-<periodo>-causale.txt
 *
 * La ricalibrazione v2r del modello spedito sull'etichetta del pannello (stima su TRAIN+VALIDATION, valutazione su TEST):
 *
 *   --args="recalibrate-v2r"                         -> nowcast/.../verdict/RecalibrationV2r.kt e reports/recalibration-v2r.txt
 *
 * Cio' che il telefono mostra (motore vero, Platt personale, analoghi, pavimenti) e il gate d'indipendenza D1:
 *
 *   --args="phone-pipeline <validation|test>"        grezzo contro finale per livello/finestra/localita' -> reports/phone-pipeline-<periodo>.txt
 *   --args="gate <validation|test> [--model v2|v2r]" la pipeline ad apprendimento vuoto contro le baseline oneste
 *                                                     -> reports/gate-<periodo>[-v2r].txt; codice d'uscita 1 se FAIL
 *
 * Le righe di addestramento del v3 (TRAIN e VALIDATION a un'ora di passo, tabelle degli altri anni):
 *
 *   --args="v3-rows <tune|refit> [--step-ore N]"    costruisce le righe e ne scrive il rapporto -> reports/v3-rows-<fase>.txt
 *
 * L'addestramento del v3 e i suoi gate (vedi reports/training-v3*.txt):
 *
 *   --args="train-v3 tune"                           griglie su TRAIN, scelta su VALIDATION A -> build/v3/candidate
 *   --args="gate validation --model v3-candidate [--family logistica|gbm]"   gate di sviluppo del candidato
 *   --args="train-v3 decide"                         D4 -> reports/training-v3.txt
 *   --args="floors-v3 validation [--emit]"           i pavimenti del v3 -> FloorPoliciesV3.kt
 *   --args="train-v3 refit" / "train-v3 emit"        TRAIN+VALIDATION congelato -> TrainedNowcastV3*.kt (+ .fwgb)
 *   --args="gate test --model v3"                    una volta sola, sull'artefatto compilato
 *
 * Dati meteo di Open-Meteo.com (CC BY 4.0), uso non commerciale.
 */
fun main(args: Array<String>) {
  when (args.firstOrNull() ?: "all") {
    "fetch-hf" -> fetchHistoricalForecast(args.getOrNull(1))
    "fetch-hf15" -> fetchHistoricalForecast("minutely")
    "fetch-runs" -> fetchRuns(args.drop(1))
    "probe-stability" -> StabilityProbe(newHttp()).run()
    "label-audit" -> emit(LabelAudit.run(), "label-audit.txt")
    "baselines" -> tierBench(args.drop(1), withModel = false)
    "replay-tiers" -> tierBench(args.drop(1), withModel = true)
    "recalibrate-v2r" -> emit(V2rRecalibration.run(), "recalibration-v2r.txt")
    "gate" -> gate(args.drop(1))
    "v3-rows" -> v3Rows(args.drop(1))
    "train-v3" -> trainV3(args.drop(1))
    "v3-lolo" -> emit(dev.pampa.fluidweather.testbench.train.v3.V3LoloCommand.run(), "v3-lolo-validation.txt")
    "phone-pipeline" -> phonePipeline(args.drop(1))
    "floors-v3" -> floorsV3(args.drop(1))
    "fetch" -> OpenMeteoFetcher().fetchAll()
    "replay" -> replay()
    // Lo stesso replay sull'archivio liscio: serve solo a misurare quanto costa essere un
    // telefono invece di una stazione. Non e' il voto, e' il termine di paragone.
    "replay-ideale" -> replay(profile = SamplingProfile.IDEALE)
    "stages" -> stages()
    "train" -> TrainCommand.run(fetchedDatasets())
    // La resa dei conti: il modello addestrato contro le baseline, solo sul periodo che
    // l'addestramento non ha mai visto (dal 2025-09-01 in poi).
    "replay-oos" -> replay(evaluateFromMillis = TrainCommand.CUTOFF_MILLIS, withNowcast = true)
    "all" -> {
      OpenMeteoFetcher().fetchAll()
      replay()
      stages()
    }
    else -> println(
      "comandi: fetch | replay | replay-ideale | stages | train | replay-oos | all | " +
        "fetch-hf <panel|context|providers|all> | fetch-hf15 | fetch-runs [--probe] [--model <m>] | probe-stability | label-audit | " +
        "baselines <validation|test> | replay-tiers <validation|test> | recalibrate-v2r | " +
        "phone-pipeline <validation|test> [--model v3] | gate <validation|test> [--model v2|v2r|v3-candidate|v3] [--family logistica|gbm] | " +
        "v3-rows <tune|refit> [--step-ore N] | train-v3 <tune|decide|refit|emit> | v3-lolo | floors-v3 validation [--emit]",
    )
  }
}

private fun newHttp() = OpenMeteoHttp(CallBudget())

/**
 * `baselines`/`replay-tiers <validation|test> [--barometro-causale]`: il periodo non ha default (TEST si
 * tocca a ragion veduta); il flag e' la misura di sensibilita' del barometro sintetico senza anticipo.
 */
private fun tierBench(args: List<String>, withModel: Boolean) {
  val period = TierBench.parsePeriod(args.firstOrNull())
  val command = if (withModel) "replay-tiers" else "baselines"
  val (v3Name, options) = v3Option(args.drop(1))
  if (period == null || options.any { it != TierBench.CAUSAL_FLAG } || (v3Name != null && !withModel)) {
    println("uso: $command <validation|test> [${TierBench.CAUSAL_FLAG}]${if (withModel) " [--model v3|v3-candidate]" else ""}  (il periodo e' obbligatorio)")
    return
  }
  val v3 = v3Name?.let { v3Model(it, period) }
  val causal = TierBench.CAUSAL_FLAG in options
  val name = TierBench.reportName(command, period, causal).let { if (v3Name != null) it.removeSuffix(".txt") + "-$v3Name.txt" else it }
  emit(TierBench.run(period, withModel, causalPressure = causal, v3 = v3), name)
}

/** `--model v3|v3-candidate` fra gli argomenti: il nome e il resto. */
private fun v3Option(args: List<String>): Pair<String?, List<String>> {
  val at = args.indexOf("--model")
  if (at < 0) return null to args
  val name = args.getOrNull(at + 1)
  if (name != GateSubjects.V3 && name != GateSubjects.V3_CANDIDATE) {
    println("--model vale v3 o v3-candidate")
    exitProcess(2)
  }
  return name to (args.take(at) + args.drop(at + 2))
}

/**
 * Il v3 per un rapporto (non per il gate). Su TEST il candidato non entra mai, e l'artefatto compilato solo
 * dopo che il suo gate di TEST e' stato giocato (e' lo stesso sguardo, riportato in un'altra forma).
 */
private fun v3Model(name: String, period: TierPeriod): Pair<String, TieredNowcastModel> {
  if (period.name == TierPeriods.TEST.name) {
    if (name == GateSubjects.V3_CANDIDATE) {
      println("il candidato non si gioca su TEST")
      exitProcess(2)
    }
    val tag = ModelVersions.tag(model = TrainedNowcastV3.VERSION)
    val ledger = File("reports", IndependenceGate.LEDGER_NAME)
    val played = ledger.exists() && ledger.readLines().any { it.split(' ').let { p -> p.size >= 3 && p[1] == "test" && p[2] == tag } }
    if (!played) {
      println("il v3 su TEST solo dopo il suo gate di TEST (`gate test --model v3`), che si gioca una volta sola")
      exitProcess(2)
    }
  }
  val subject = GateSubjects.create(name, null) as V3Subject
  return name to subject.model
}

/**
 * `v3-rows <tune|refit> [--step-ore N]`: le righe di addestramento del v3 e il loro rapporto. Non addestra:
 * serve a vedere cosa entra (e cosa no) prima di addestrare.
 */
private fun v3Rows(args: List<String>) {
  val stage = when (args.firstOrNull()) {
    "tune" -> V3Stage.TUNE
    "refit" -> V3Stage.REFIT
    else -> null
  }
  val options = args.drop(1)
  val step = if (options.isEmpty()) 1 else options.takeIf { it.size == 2 && it[0] == "--step-ore" }?.get(1)?.toIntOrNull()?.takeIf { it in 1..24 }
  if (stage == null || step == null) {
    println("uso: v3-rows <tune|refit> [--step-ore N]  (la fase e' obbligatoria; N da 1 a 24, default 1)")
    exitProcess(2)
  }
  emit(V3RowsCommand.run(stage, step), "v3-rows-${stage.name.lowercase()}.txt")
}

/**
 * `train-v3 <tune [--senza-alberi] [--griglia-larga] [--regole-tutte] [--criterio-gate]|decide|refit|emit [--from candidate|release]>`: l'addestramento del v3
 * (vedi [TrainV3Command]).
 */
private fun trainV3(args: List<String>) {
  when (args.firstOrNull()) {
    "tune" -> {
      val withGbm = "--senza-alberi" !in args
      val levers = V3Levers(wideGrid = "--griglia-larga" in args, allRules = "--regole-tutte" in args, gateCriterion = "--criterio-gate" in args)
      emit(TrainV3Command.tune(withGbm = withGbm, levers = levers), if (withGbm) "training-v3-tune.txt" else "training-v3-tune-logistica.txt")
    }
    "decide" -> emit(TrainV3Command.decide().first, "training-v3.txt")
    "refit" -> emit(TrainV3Command.refit(), "training-v3-refit.txt")
    "emit" -> {
      val from = if (args.getOrNull(1) == "--from") args.getOrNull(2) else "release"
      val artefact = when (from) {
        "release" -> CandidateArtefact.read(TrainV3Command.RELEASE_DIR)
        "candidate" -> CandidateArtefact.read(TrainV3Command.CANDIDATE_DIR).let {
          it.copy(version = "v3-${TrainV3Command.today()}", info = it.info + mapOf("emitted-from" to "candidate"))
        }
        else -> {
          println("uso: train-v3 emit [--from candidate|release]")
          exitProcess(2)
        }
      }
      emit(ArtefactWriterV3.emit(artefact, from), "model-v3.txt")
    }
    else -> {
      println("uso: train-v3 <tune [--senza-alberi] [--griglia-larga] [--regole-tutte] [--criterio-gate]|decide|refit|emit [--from candidate|release]>")
      exitProcess(2)
    }
  }
}

/**
 * `gate <validation|test> [--model <nome>]`: il gate D1 sulla pipeline ad apprendimento vuoto. L'unico
 * posto che esce con codice 1: la logica ([IndependenceGate]) resta pura e testabile.
 */
private fun gate(args: List<String>) {
  val parsed = IndependenceGate.parseArguments(args)
  if (parsed == null) {
    println(
      "uso: gate <validation|test> [--model ${GateSubjects.NAMES.joinToString("|")}] [--family logistica|gbm]" +
        "  (il periodo e' obbligatorio; --family solo per il v3)",
    )
    exitProcess(2)
  }
  IndependenceGate.refusal(parsed)?.let { why ->
    println("gate rifiutato: $why")
    exitProcess(2)
  }
  val outcome = IndependenceGate.run(parsed.period, parsed.subject())
  emit(outcome.report, IndependenceGate.reportName(parsed))
  if (!outcome.gate.passed) exitProcess(1)
}

/** `phone-pipeline <validation|test> [--model v3|v3-candidate]`. */
private fun phonePipeline(args: List<String>) {
  val period = TierBench.parsePeriod(args.firstOrNull())
  val (v3Name, rest) = v3Option(args.drop(1))
  if (period == null || rest.isNotEmpty()) {
    println("uso: phone-pipeline <validation|test> [--model v3|v3-candidate]  (il periodo e' obbligatorio)")
    return
  }
  val v3 = v3Name?.let { v3Model(it, period) }
  val name = PhonePipelineBench.reportName(period).let { if (v3Name != null) it.removeSuffix(".txt") + "-$v3Name.txt" else it }
  emit(PhonePipelineBench.run(period, v3 = v3), name)
}

/** `floors-v3 validation [--emit]`: la politica dei pavimenti del v3 (solo VALIDATION: TEST non decide niente). */
private fun floorsV3(args: List<String>) {
  if (args.firstOrNull() != "validation" || args.drop(1).any { it != "--emit" }) {
    println("uso: floors-v3 validation [--emit]")
    exitProcess(2)
  }
  emit(FloorsV3Command.run(TierPeriods.VALIDATION, emit = "--emit" in args), "floors-v3-validation.txt")
}

private fun fetchHistoricalForecast(productName: String?) {
  val products = productName?.let { HfProducts.byName(it) }
  if (products == null) {
    println("uso: fetch-hf <panel|context|providers|all>  (oppure fetch-hf15 per il quarto d'ora)")
    return
  }
  HistoricalForecastFetcher(newHttp()).fetch(products)
}

/**
 * `fetch-runs [--probe] [--model <m>] [--from yyyyMMddHH] [--to yyyyMMddHH] [--limit n]`.
 * Senza `--model` scarica tutti i preset, nell'ordine di [RunPresets.ALL].
 */
private fun fetchRuns(args: List<String>) {
  val probe = "--probe" in args
  fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
  fun stamp(name: String): Instant? = option(name)?.let {
    Instant.ofEpochMilli(RunArchive.parseStamp(it) ?: error("$name vuole yyyyMMddHH, non '$it'"))
  }

  val model = option("--model")
  val presets = RunPresets.ALL.filter { model == null || it.model == model }
  if (presets.isEmpty()) {
    println("modello sconosciuto '$model'; preset: ${RunPresets.ALL.joinToString { it.model }}")
    return
  }
  val fetcher = SingleRunFetcher(newHttp())
  if (probe) {
    fetcher.probe(presets, stamp("--from") ?: RunPresets.PROBE_INIT)
    return
  }
  val from = stamp("--from")
  val to = stamp("--to")
  val limit = option("--limit")?.toInt()
  for (preset in presets) fetcher.fetch(preset, from, to, limit)
  println(HistoricalForecastFetcher.ATTRIBUTION)
}

private fun fetchedDatasets(): List<StationDataset> {
  val available = BenchLocations.filter { StationDataset.isFetched(it) }
  if (available.isEmpty()) {
    println("Nessun dato: prima `gradlew :testbench:run --args=fetch`")
    return emptyList()
  }
  return available.map { StationDataset.load(it) }
}

private fun replay(
  evaluateFromMillis: Long? = null,
  withNowcast: Boolean = false,
  profile: SamplingProfile = SamplingProfile.TELEFONO,
) {
  val datasets = fetchedDatasets()
  if (datasets.isEmpty()) return
  val replayer = Replayer(profile = profile)
  val report = StringBuilder()

  val title = if (withNowcast) {
    "REPLAY OUT-OF-SAMPLE (dal 2025-09-01) — nowcast-v1 in classifica, profilo $profile"
  } else {
    "REPLAY — profilo $profile"
  }
  report.appendLine("=== $title — POD/FAR/CSI a soglia 0,5 · Brier/BSS · per predittore e finestra ===")
  report.appendLine()

  val global = mutableMapOf<String, MutableMap<String, MutableList<TaggedVerification>>>()
  for (dataset in datasets) {
    val outcome = replayer.replay(dataset, evaluateFromMillis, withNowcast)
    report.appendLine("--- ${dataset.location.name} (${dataset.spanDays.toInt()} giorni, ${outcome.evaluations} valutazioni) — ${dataset.location.why}")
    report.append(table(outcome.cells))
    report.appendLine()
    for ((predictor, byWindow) in outcome.cells) {
      for ((window, verifications) in byWindow) {
        global.getOrPut(predictor) { mutableMapOf() }
          .getOrPut(window) { mutableListOf() } += verifications
      }
    }
  }

  report.appendLine("--- TUTTE LE LOCALITA' INSIEME")
  report.append(table(global))
  report.appendLine()
  report.appendLine("--- Per stagione (tutte le localita', regola-barometrica, Brier)")
  val rule = global["regola-barometrica"].orEmpty()
  for ((window, verifications) in rule) {
    val bySeason = verifications.groupBy { it.season }.toSortedMap()
    val line = bySeason.entries.joinToString("  ") { (season, list) ->
      "$season=${format(Probabilistic.brier(list.map { Verification(it.probability, it.occurred) }))}"
    }
    report.appendLine("  $window: $line")
  }

  val name = when {
    withNowcast -> "replay-oos.txt"
    profile == SamplingProfile.IDEALE -> "replay-ideale.txt"
    else -> "replay.txt"
  }
  emit(report.toString(), name)
}

private fun table(cells: Map<String, out Map<String, out List<TaggedVerification>>>): String {
  val builder = StringBuilder()
  builder.appendLine(
    String.format(
      Locale.ROOT,
      "  %-20s %-6s %6s %6s %6s %6s %8s %8s %7s",
      "predittore", "fin.", "n", "POD", "FAR", "CSI", "Brier", "BSS", "base",
    ),
  )
  for ((predictor, byWindow) in cells) {
    for ((window, tagged) in byWindow) {
      val verifications = tagged.map { Verification(it.probability, it.occurred) }
      val contingency = Contingency.at(0.5, verifications)
      builder.appendLine(
        String.format(
          Locale.ROOT,
          "  %-20s %-6s %6d %6s %6s %6s %8s %8s %7s",
          predictor,
          window,
          verifications.size,
          format(contingency.pod),
          format(contingency.far),
          format(contingency.csi),
          format(Probabilistic.brier(verifications)),
          format(Probabilistic.brierSkillScore(verifications)),
          format(Probabilistic.baseRate(verifications)),
        ),
      )
    }
  }
  return builder.toString()
}

private fun stages() {
  val datasets = fetchedDatasets()
  if (datasets.isEmpty()) return
  val report = StringBuilder()

  report.appendLine("=== STADI — ogni pezzo della pipeline ha la sua metrica ===")
  report.appendLine()
  report.appendLine("--- Riduzione al mare: MAE contro la MSL del provider (hPa)")
  report.appendLine(String.format(Locale.ROOT, "  %-18s %10s %14s", "localita'", "T reale", "T standard"))
  for (dataset in datasets) {
    val r = StageBenches.reduction(dataset)
    report.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %10s %14s",
        dataset.location.name, format(r.maeRealTemperatureHpa), format(r.maeStandardTemperatureHpa),
      ),
    )
  }

  report.appendLine()
  report.appendLine("--- Persistenza: dato che piove ADESSO, quanto piove poi (il pavimento dell'osservazione)")
  report.appendLine(String.format(Locale.ROOT, "  %-18s %8s %8s %8s %8s", "localita'", "casi", "0-1h", "1-3h", "3-6h"))
  val persistenceTotals = mutableMapOf<String, MutableList<Double>>()
  for (dataset in datasets) {
    val p = StageBenches.persistence(dataset)
    p.byWindow.forEach { (window, rate) -> persistenceTotals.getOrPut(window) { mutableListOf() } += rate }
    report.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %8d %8s %8s %8s",
        dataset.location.name, p.cases,
        format(p.byWindow["0-1h"] ?: Double.NaN),
        format(p.byWindow["1-3h"] ?: Double.NaN),
        format(p.byWindow["3-6h"] ?: Double.NaN),
      ),
    )
  }
  report.appendLine(
    String.format(
      Locale.ROOT, "  %-18s %8s %8s %8s %8s", "MEDIA", "",
      format(persistenceTotals["0-1h"]?.average() ?: Double.NaN),
      format(persistenceTotals["1-3h"]?.average() ?: Double.NaN),
      format(persistenceTotals["3-6h"]?.average() ?: Double.NaN),
    ),
  )

  report.appendLine()
  report.appendLine("--- Quota: quanto della tendenza e' meteo e quanto e' il GPS")
  report.appendLine("    (riferimento: lo stesso telefono con un GPS che non sbaglia mai)")
  report.appendLine(
    String.format(
      Locale.ROOT, "  %-18s %9s %9s | %9s %9s | %9s %9s",
      "localita'", "MAE ora", "MAE prima", "peggiore", "-", "ruvid.ora", "ruvid.prima",
    ),
  )
  for (dataset in datasets) {
    val a = StageBenches.altitudeNoise(dataset)
    report.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %9s %9s | %9s %9s | %9s %9s",
        dataset.location.name,
        format(a.trendMaeHpaPerHour), format(a.legacyTrendMaeHpaPerHour),
        format(a.trendMaxHpaPerHour), "",
        format(a.roughnessJitterHpa), format(a.legacyRoughnessHpa),
      ),
    )
  }

  report.appendLine()
  report.appendLine("--- Marea: ampiezze S1/S2 nei dati, dopo il prior, dopo il fit locale (hPa)")
  report.appendLine(
    String.format(
      Locale.ROOT, "  %-18s %6s %6s | %6s %6s | %6s %6s",
      "localita'", "S1", "S2", "S1'", "S2'", "S1fit", "S2fit",
    ),
  )
  for (dataset in datasets) {
    val t = StageBenches.tide(dataset)
    report.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %6s %6s | %6s %6s | %6s %6s",
        dataset.location.name,
        format(t.s1InDataHpa), format(t.s2InDataHpa),
        format(t.s1AfterPriorHpa), format(t.s2AfterPriorHpa),
        format(t.s1AfterLocalFitHpa), format(t.s2AfterLocalFitHpa),
      ),
    )
  }

  report.appendLine()
  report.appendLine("--- Screening: artefatti iniettati in dati veri")
  for (dataset in datasets) {
    val s = StageBenches.screening(dataset)
    report.appendLine(
      "  ${dataset.location.name}: ascensori ${s.jumpCaught}/${s.jumpInjected} presi, " +
        "${s.jumpFalsePositives} falsi positivi; veicolo ${s.vehicleCaught}/${s.vehicleInjected}",
    )
  }

  report.appendLine()
  report.appendLine("--- Tendenza: presa in parola sulle 3 ore successive (MSL de-tidalizzata)")
  for (dataset in datasets) {
    val t = StageBenches.trend(dataset)
    report.appendLine(
      "  ${dataset.location.name}: corr=${format(t.correlation)} MAE=${format(t.maeHpa)} hPa " +
        "(${t.evaluations} valutazioni)",
    )
  }

  emit(report.toString(), "stages.txt")
}

private fun format(value: Double): String =
  if (value.isNaN()) "—" else String.format(Locale.ROOT, "%.3f", value)

private fun emit(text: String, fileName: String) {
  println(text)
  val reports = File("reports")
  reports.mkdirs()
  File(reports, fileName).writeText(text)
  println(">> scritto reports/$fileName")
}
