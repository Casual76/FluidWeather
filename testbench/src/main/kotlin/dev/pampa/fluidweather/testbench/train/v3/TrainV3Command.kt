package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.ObservationFloors
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.gate.IndependenceGate
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.replay.TierBench
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TierReplayer
import dev.pampa.fluidweather.testbench.tiers.ScenarioMode
import dev.pampa.fluidweather.testbench.tiers.TierHalf
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * `train-v3 <tune|decide|refit|emit>`: l'addestramento del v3 dall'inizio all'artefatto.
 *
 * - **tune**: righe orarie di TRAIN (scenari estratti) e di VALIDATION (semantica del gate), griglia della
 *   logistica e degli alberi per ogni (livello, finestra), scelta su VALIDATION A, misure su VALIDATION B e
 *   l'anticipo del gate su tutto VALIDATION. Scrive il candidato in `build/v3/candidate/` e
 *   `reports/training-v3-tune.txt`.
 * - **decide**: la regola D4 sui numeri del candidato e sugli esiti del gate di sviluppo gia' nel registro
 *   (`gate validation --model v3-candidate --family ...`); scrive `reports/training-v3.txt` con la riga
 *   `SCELTA:` e fissa la famiglia del candidato.
 * - **refit**: TRAIN + VALIDATION con gli iperparametri congelati del candidato, in `build/v3/release/`.
 * - **emit [--from candidate|release]**: i file Kotlin e le risorse del v3 dentro `:nowcast`.
 */
object TrainV3Command {

  val CANDIDATE_DIR: File = File("build/v3/candidate")
  val RELEASE_DIR: File = File("build/v3/release")

  fun candidateVersion(): String = "v3-candidato-${today()}"

  fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

  // ------------------------------------------------------------------ tune

  /** Dove scrive un giro di sola logistica: non tocca il candidato (che ha anche gli alberi). */
  val LOGISTIC_ONLY_DIR: File = File("build/v3/candidate-logistica")

  fun tune(
    dataRoot: File = File("data"),
    withGbm: Boolean = true,
    levers: V3Levers = V3Levers(),
    log: (String) -> Unit = ::println,
  ): String {
    val started = System.nanoTime()
    val loaded = TierBench.loadInputs(dataRoot, log)
    require(loaded.inputs.isNotEmpty()) { "nessuna localita' con i dati completi" }
    val slices = JackknifeSlices(JackknifePlan.real(), loaded.inputs)
    val builder = V3RowBuilder(loaded.inputs, slices, log = log)
    val hour = 3_600_000L
    log("righe di TRAIN (TUNE, addestramento, orarie)...")
    val train = builder.build(V3RowSpec(TierPeriods.TRAIN, hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    val trainStats = builder.lastStats
    log("righe di VALIDATION (TUNE, valutazione, orarie)...")
    val validation = builder.build(V3RowSpec(TierPeriods.VALIDATION, hour, ScenarioMode.EVALUATION, V3Stage.TUNE))
    val rowsSeconds = (System.nanoTime() - started) / 1e9

    log("addestro (${TierReplayer.defaultThreads()} thread)...")
    val tables = V3Trainer(log = log).tune(train, validation, levers = levers, withGbm = withGbm)
    val trees = if (withGbm) V3Trainer.selectTrees(tables.cells, D4Decision.MAX_TREE_BYTES) else emptyMap()

    val pooled = HonestBaselines.buildAll(TierPeriods.VALIDATION, loaded.inputs).values.first().pooledOnly()!!.pooled.encode()
    val info = LinkedHashMap<String, String>()
    info["stage"] = "TUNE"
    info["levers"] = levers.label
    info["trained"] = "TRAIN ${TierPeriods.TRAIN} (scenari estratti), scelta su VALIDATION A"
    for (cell in tables.cells) {
      info["cell.${cell.key}.logistic"] = cell.logisticChoice.encode()
      trees[cell.key]?.let { info["cell.${cell.key}.gbm"] = "${encodeConfig(it.config)};rounds=${it.rounds}" }
    }
    val provisional = CandidateArtefact(
      version = candidateVersion(),
      requestedFamily = ModelFamily.LOGISTICA,
      logistic = logisticTables(tables),
      trees = if (withGbm) treeFiles(trees) else null,
      pooled = pooled,
      floors = ObservationFloors.none(),
      info = info,
    )
    log("valuto su VALIDATION...")
    val models = linkedMapOf("logistica" to provisional.toModel(ModelFamily.LOGISTICA))
    if (withGbm) models["alberi"] = provisional.toModel(ModelFamily.GBM)
    val evaluation = V3Evaluation(validation, models)
    val gains = gainTable(evaluation, withGbm)
    val g = if (withGbm) D4Decision.meanGain(gains.map { it.logisticBrier }, gains.map { it.gbmBrier }) else Double.NaN
    val candidate = provisional.copy(info = info + mapOf("d4.G" to g.toString()))
    candidate.write(if (withGbm) CANDIDATE_DIR else LOGISTIC_ONLY_DIR)
    val seconds = (System.nanoTime() - started) / 1e9

    return buildString {
      appendLine("=== train-v3 tune — il v3 per livello di contesto, scelta su VALIDATION ===")
      appendLine()
      appendLine("Generato da `train-v3 tune`. ${TruthPanel.ATTRIBUTION}.")
      appendLine("Leve del diario attive: ${levers.label}." + if (withGbm) "" else " Giro di sola logistica: scritto in ${LOGISTIC_ONLY_DIR.path}, il candidato non si tocca.")
      appendLine(String.format(Locale.ROOT, "Tempo: %.0f s (righe %.0f s). Candidato: %s, impronta logistica %s%s.", seconds, rowsSeconds, candidate.version,
        candidate.fingerprint(ModelFamily.LOGISTICA), if (withGbm) ", alberi ${candidate.fingerprint(ModelFamily.GBM)}" else ""))
      appendLine()
      appendLine("Righe: TRAIN ${train.size} (orarie, scenari estratti; emissioni ${trainStats.anchors}), VALIDATION ${validation.size} (orarie, semantica del gate).")
      appendLine("  Etichette: PANNELLO (${TruthPanel.VERSION}); ERA5 solo per riportare. Tabelle di baseline delle righe di TRAIN: gli altri anni (jackknife).")
      for (tier in ContextTier.entries) {
        appendLine(String.format(Locale.ROOT, "  %-13s TRAIN %7d  VALIDATION %7d  colonne degeneri: %s", tier.name, train.count(tier), validation.count(tier),
          tables.standardization.getValue(tier).degenerate.sorted().joinToString(", ") { FeatureExtractorV3.names[it] }.ifEmpty { "nessuna" }))
      }
      appendLine()
      logisticSection(this, tables)
      if (withGbm) gbmSection(this, tables, trees)
      d4Section(this, gains, g, candidate, withGbm)
      appendLine("--- ANTICIPO DEL GATE su VALIDATION (righe al passo di 3 h = i casi del gate; modelli grezzi, niente pavimenti)")
      for ((m, name) in models.keys.withIndex()) {
        val (text, failures) = evaluation.preGate(3 + m)
        appendLine("  [$name] celle dure che non passano: $failures")
        append(text)
        appendLine()
      }
      coefficientSection(this, tables)
      if (withGbm) importanceSection(this, trees)
    }
  }

  class GainCell(val tier: ContextTier, val window: Int, val n: Int, val base: Double, val bestName: String, val bestBrier: Double, val logisticBrier: Double, val gbmBrier: Double) {
    val gain: Double get() = (logisticBrier - gbmBrier) / logisticBrier
  }

  private fun gainTable(evaluation: V3Evaluation, withGbm: Boolean): List<GainCell> {
    val out = ArrayList<GainCell>()
    for (tier in ContextTier.entries) for (w in 0 until 3) {
      val cases = evaluation.cases(tier, w) { evaluation.isEurope(it) && evaluation.isHalf(it, TierHalf.B) }
      val (best, bestBrier) = evaluation.bestBaseline(cases, w)
      out += GainCell(
        tier, w, cases.size, cases.count { evaluation.label(it, w) == 1 }.toDouble() / cases.size,
        evaluation.names[best], bestBrier, evaluation.brier(cases, 3, w), if (withGbm) evaluation.brier(cases, 4, w) else Double.NaN,
      )
    }
    return out
  }

  private fun logisticSection(out: StringBuilder, tables: V3Trainer.TierTables) {
    out.appendLine("--- LOGISTICA: la scelta per cella (log-loss di VALIDATION A, Europa; a parita' la penalita' piu' piccola)")
    for (cell in tables.cells) {
      val best = cell.logisticGrid.minWithOrNull(compareBy<LogisticGridPoint>({ it.validationLogLoss }, { it.choice.lambda }))
      out.appendLine(String.format(Locale.ROOT, "  %-13s %-5s righe %7d (bagnate %.3f), %2d colonne: %s — VAL-A %.5f; bag non convergenti: %d",
        cell.tier.name, RainWindows.ALL[cell.window].label, cell.trainRows, cell.trainPositives.toDouble() / cell.trainRows, cell.columns.size,
        cell.logisticChoice.label, best?.validationLogLoss ?: Double.NaN, cell.nonConverged))
      val byAnchor = cell.logisticGrid.groupBy { it.choice.anchor }
      for ((anchor, points) in byAnchor) {
        val top = points.minBy { it.validationLogLoss }
        out.appendLine(String.format(Locale.ROOT, "      ancora %-26s migliore %.5f (lambda %s, fuori Europa %.2f), margine peggiore VAL-A %+.4f (%s)%s", LogisticChoice.anchorName(anchor), top.validationLogLoss,
          top.choice.lambda, top.choice.nonEuropeWeight, top.worstMargin, top.worstLocation, if (points.any { !it.converged }) " — punti non convergenti: ${points.count { !it.converged }}" else ""))
      }
    }
    out.appendLine()
  }

  private fun gbmSection(out: StringBuilder, tables: V3Trainer.TierTables, trees: Map<String, GbmGridPoint>) {
    out.appendLine("--- ALBERI: la scelta per cella (arresto anticipato su VALIDATION A, Europa; limite complessivo ${D4Decision.MAX_TREE_BYTES / 1_000_000.0} MB)")
    var total = 0
    for (cell in tables.cells) {
      val chosen = trees.getValue(cell.key)
      val best = cell.gbmGrid.minBy { it.validationLogLoss }
      total += chosen.bytes.size
      out.appendLine(String.format(Locale.ROOT, "  %-13s %-5s %-18s giri %4d (giocati %4d) VAL-A %.5f, %7d byte%s", cell.tier.name, RainWindows.ALL[cell.window].label,
        chosen.config.label, chosen.rounds, chosen.roundsPlayed, chosen.validationLogLoss, chosen.bytes.size,
        if (chosen !== best) " (la migliore senza limite: ${best.config.label}, ${best.bytes.size} byte)" else ""))
    }
    out.appendLine(String.format(Locale.ROOT, "  Totale alberi: %d byte (%.2f MB).", total, total / 1_000_000.0))
    out.appendLine()
  }

  private fun d4Section(out: StringBuilder, gains: List<GainCell>, g: Double, candidate: CandidateArtefact, withGbm: Boolean) {
    out.appendLine("--- VALIDATION B, EUROPA (righe orarie, semantica del gate, modelli grezzi): Brier per cella")
    out.appendLine(String.format(Locale.ROOT, "  %-13s %-5s %7s %6s  %-19s %8s %9s %9s %8s", "livello", "fin.", "n", "base", "migliore baseline", "Brier", "logistica", "alberi", "g_c"))
    for (c in gains) {
      out.appendLine(String.format(Locale.ROOT, "  %-13s %-5s %7d %6.3f  %-19s %8s %9s %9s %8s", c.tier.name, RainWindows.ALL[c.window].label, c.n, c.base, c.bestName,
        Fmt.f4(c.bestBrier), Fmt.f4(c.logisticBrier), Fmt.f4(c.gbmBrier), if (withGbm) String.format(Locale.ROOT, "%+.2f%%", 100 * c.gain) else "-"))
    }
    if (withGbm) {
      out.appendLine(String.format(Locale.ROOT, "  G (media dei g_c, EUROPA VAL-B) = %+.2f%%; peso degli alberi %d byte.", 100 * g, candidate.treeBytes))
      out.appendLine("  La decisione D4 completa (con il gate di sviluppo delle due famiglie) la scrive `train-v3 decide` in reports/training-v3.txt.")
    }
    out.appendLine()
  }

  private fun coefficientSection(out: StringBuilder, tables: V3Trainer.TierTables) {
    out.appendLine("--- COEFFICIENTI della logistica (primo bag, scala standardizzata, ancora assorbita), colonne usate")
    for (cell in tables.cells) {
      val bag = cell.bags.first()
      out.appendLine(String.format(Locale.ROOT, "  %s %s  intercetta %.4f", cell.tier.name, RainWindows.ALL[cell.window].label, bag[0]))
      val line = cell.columns.sortedByDescending { kotlin.math.abs(bag[it + 1]) }.joinToString(", ") {
        String.format(Locale.ROOT, "%s %+.3f", FeatureExtractorV3.names[it], bag[it + 1])
      }
      out.appendLine("    $line")
    }
    out.appendLine()
  }

  private fun importanceSection(out: StringBuilder, trees: Map<String, GbmGridPoint>) {
    out.appendLine("--- ALBERI: importanza (quota del guadagno degli split), le otto colonne principali per cella")
    for ((key, point) in trees) {
      val total = point.gain.sum()
      val top = point.gain.indices.filter { point.gain[it] > 0 }.sortedByDescending { point.gain[it] }.take(8)
      out.appendLine("  $key: " + top.joinToString(", ") { String.format(Locale.ROOT, "%s %.0f%%", FeatureExtractorV3.names[it], 100 * point.gain[it] / total) })
    }
    out.appendLine()
  }

  // ------------------------------------------------------------------ decide

  /**
   * D4 sui numeri del candidato (G in `manifest.txt`) e sugli esiti del gate di sviluppo per le due
   * famiglie dal registro (l'ultima riga di VALIDATION con l'impronta del candidato per quella famiglia).
   */
  fun decide(reports: File = File("reports")): Pair<String, D4Result> {
    val candidate = CandidateArtefact.read(CANDIDATE_DIR)
    val g = candidate.info["d4.G"]?.toDoubleOrNull() ?: Double.NaN
    val ledger = File(reports, IndependenceGate.LEDGER_NAME).takeIf { it.exists() }?.readLines().orEmpty().map { it.split(' ') }
    val tag = ModelVersions.tag(model = candidate.version)
    fun passed(family: ModelFamily): Boolean? {
      val fingerprint = candidate.fingerprint(family)
      return ledger.lastOrNull { it.size >= 6 && it[1] == "validation" && it[2] == tag && it[5] == fingerprint }?.let { it[3] == "PASS" }
    }
    val gbm = if (candidate.trees != null) passed(ModelFamily.GBM) else null
    val logistic = passed(ModelFamily.LOGISTICA)
    val result = D4Decision.decide(if (g.isNaN()) Double.NEGATIVE_INFINITY else g, gbm, logistic, candidate.treeBytes)
    val family = result.family ?: ModelFamily.LOGISTICA
    val decided = candidate.copy(requestedFamily = family, info = candidate.info + mapOf("d4" to result.outcome.name))
    decided.write(CANDIDATE_DIR)
    val text = buildString {
      appendLine("=== train-v3 — la decisione D4 ===")
      appendLine()
      appendLine("Generato da `train-v3 decide` sul candidato ${candidate.version} (impronte: logistica ${candidate.fingerprint(ModelFamily.LOGISTICA)}" +
        (if (candidate.trees != null) ", alberi ${candidate.fingerprint(ModelFamily.GBM)}" else "") + ").")
      appendLine("Regola dichiarata: alberi solo se G >= 3% (media sulle 12 celle, EUROPA, VALIDATION B, modelli grezzi) E gli alberi passano il gate")
      appendLine("di sviluppo (VALIDATION, pavimenti del candidato) E pesano al piu' 1,5 MB; altrimenti la logistica. Le tabelle logistiche si emettono")
      appendLine("sempre; due casi tornano all'utente (solo gli alberi passano con G < 3%; alberi scelti ma paracadute che non passa).")
      appendLine()
      appendLine(result.line)
      for (reason in result.reasons) appendLine("  - $reason")
      if (result.family == null) appendLine("  La famiglia del candidato resta LOGISTICA finche' l'utente non decide.")
      appendLine()
      appendLine("I numeri per cella (Brier VALIDATION B, EUROPA) e l'anticipo del gate sono in reports/training-v3-tune.txt;")
      appendLine("i gate di sviluppo in reports/gate-validation-v3-candidate*.txt.")
    }
    return text to result
  }

  // ------------------------------------------------------------------ refit

  fun refit(dataRoot: File = File("data"), log: (String) -> Unit = ::println): String {
    val started = System.nanoTime()
    val candidate = CandidateArtefact.read(CANDIDATE_DIR)
    val family = candidate.requestedFamily
    val frozen = ContextTier.entries.flatMap { tier -> RainWindows.ALL.map { "${tier.name}.${it.label}" } }.associateWith { key ->
      val logistic = LogisticChoice.decode(candidate.info.getValue("cell.$key.logistic"))
      val gbmText = candidate.info["cell.$key.gbm"]
      val (config, rounds) = if (gbmText == null) null to 0 else decodeConfig(gbmText)
      val tier = ContextTier.valueOf(key.substringBefore('.'))
      val window = RainWindows.ALL.indexOfFirst { it.label == key.substringAfter('.') }
      V3Trainer.FrozenCell(logistic, config, rounds, candidate.logistic.getValue(tier).used[window])
    }
    val loaded = TierBench.loadInputs(dataRoot, log)
    val slices = JackknifeSlices(JackknifePlan.real(), loaded.inputs)
    val builder = V3RowBuilder(loaded.inputs, slices, log = log)
    val hour = 3_600_000L
    log("righe di TRAIN e VALIDATION (REFIT, addestramento, orarie)...")
    val train = builder.build(V3RowSpec(TierPeriods.TRAIN, hour, ScenarioMode.TRAINING, V3Stage.REFIT))
    val validation = builder.build(V3RowSpec(TierPeriods.VALIDATION, hour, ScenarioMode.TRAINING, V3Stage.REFIT))
    val all = V3Rows.concat(train.locations, listOf(train, validation))
    val withGbm = family == ModelFamily.GBM
    val tables = V3Trainer(log = log).refit(all, frozen, withGbm)
    val trees = if (withGbm) tables.cells.associate { it.key to it.gbmGrid.single() } else emptyMap()
    val pooled = HonestBaselines.buildAll(TierPeriods.TEST, loaded.inputs).values.first().pooledOnly()!!.pooled.encode()
    val release = CandidateArtefact(
      version = "v3-${today()}",
      requestedFamily = family,
      logistic = logisticTables(tables),
      trees = if (withGbm) treeFiles(trees) else null,
      pooled = pooled,
      floors = candidate.floors,
      info = candidate.info.filterKeys { it.startsWith("cell.") || it.startsWith("d4") } + mapOf(
        "stage" to "REFIT",
        "trained" to "TRAIN+VALIDATION ${Fmt.date(TierPeriods.TRAIN.firstMillis)}..${Fmt.date(TierPeriods.VALIDATION.endExclusiveMillis - 1)} (scenari estratti), iperparametri congelati da ${candidate.version}",
        "candidate" to candidate.version,
      ),
    )
    release.write(RELEASE_DIR)
    val seconds = (System.nanoTime() - started) / 1e9
    return buildString {
      appendLine("=== train-v3 refit — riaddestramento finale su TRAIN + VALIDATION ===")
      appendLine()
      appendLine("Generato da `train-v3 refit`. ${TruthPanel.ATTRIBUTION}.")
      appendLine(String.format(Locale.ROOT, "Tempo %.0f s. Famiglia %s (da D4). Versione %s, impronta %s. Righe %d.", seconds, family, release.version, release.fingerprint(), all.size))
      appendLine("Iperparametri congelati da ${candidate.version}; giri degli alberi = i giri migliori della scelta (non riscalati).")
      appendLine("Tabelle delle righe: jackknife di REFIT (ogni anno dagli altri, TEST mai); riferimento di tutti i posti spedito = la storia del gate di TEST.")
      appendLine()
      for (cell in tables.cells) {
        appendLine(String.format(Locale.ROOT, "  %-13s %-5s righe %7d (bagnate %.3f) %s%s; bag non convergenti %d", cell.tier.name, RainWindows.ALL[cell.window].label, cell.trainRows,
          cell.trainPositives.toDouble() / cell.trainRows, cell.logisticChoice.label, trees[cell.key]?.let { ", alberi ${it.config.label} x ${it.rounds}, ${it.bytes.size} byte" } ?: "", cell.nonConverged))
      }
      appendLine()
      appendLine("Controllo preregistrato prima di TEST: `gate validation --model v3` (in campione) deve passare; se non passa si emette il candidato")
      appendLine("(`train-v3 emit --from candidate`) e lo si annota.")
    }
  }

  // ------------------------------------------------------------------ helpers

  fun logisticTables(tables: V3Trainer.TierTables): Map<ContextTier, LogisticTableV3> = ContextTier.entries.associateWith { tier ->
    val std = tables.standardization.getValue(tier)
    val cells = tables.cells.filter { it.tier == tier }.sortedBy { it.window }
    LogisticTableV3(tier, std.means, std.sds, cells.map { it.columns }, cells.map { it.bags })
  }

  fun treeFiles(trees: Map<String, GbmGridPoint>): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    for (tier in ContextTier.entries) for (w in RainWindows.ALL.indices) {
      out[TieredNowcastModel.resourceName(tier, w)] = trees.getValue("${tier.name}.${RainWindows.ALL[w].label}").bytes
    }
    return out
  }

  fun encodeConfig(config: GbmConfig): String =
    "depth=${config.depth};lr=${config.learningRate};lambda=${config.lambda};minh=${config.minHessian};sub=${config.subsample};seed=${config.seed}"

  fun decodeConfig(text: String): Pair<GbmConfig, Int> {
    val map = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
    val config = GbmConfig(
      depth = map.getValue("depth").toInt(),
      learningRate = map.getValue("lr").toDouble(),
      lambda = map.getValue("lambda").toDouble(),
      minHessian = map.getValue("minh").toDouble(),
      subsample = map.getValue("sub").toDouble(),
      seed = map.getValue("seed").toLong(),
    )
    return config to map.getValue("rounds").toInt()
  }

  /** Il gruppo europeo, per chi legge i numeri. */
  val EUROPE: List<String> get() = TierGroups.EUROPA
}
