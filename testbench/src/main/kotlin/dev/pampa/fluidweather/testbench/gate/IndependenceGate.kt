package dev.pampa.fluidweather.testbench.gate

import dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary
import dev.pampa.fluidweather.nowcast.scoring.DayBlockBootstrap
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.replay.ContextHourFloors
import dev.pampa.fluidweather.testbench.replay.EmptyLearning
import dev.pampa.fluidweather.testbench.replay.PhonePipeline
import dev.pampa.fluidweather.testbench.replay.PipelineModel
import dev.pampa.fluidweather.testbench.replay.PipelineModels
import dev.pampa.fluidweather.testbench.replay.TierBench
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TierReplayResult
import dev.pampa.fluidweather.testbench.replay.TierReplayer
import dev.pampa.fluidweather.testbench.replay.TierStats
import dev.pampa.fluidweather.testbench.replay.TruthKind
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import java.io.File
import java.time.Instant
import java.util.Locale

/** Quanto pesa una cella: [HARD] boccia il gate, [WARN] si riporta e basta. */
enum class GateScope(val label: String) {
  HARD("duro"),
  WARN("avviso"),
}

/**
 * Una cella del gate: un gruppo (una localita' o un insieme), un livello, una finestra.
 *
 * [margin] = Brier(pipeline) - Brier(migliore baseline applicabile) sugli stessi casi; la cella passa
 * se e' **strettamente** negativo — sul valore puntuale, come chiesto (critica A4). L'intervallo del
 * bootstrap a blocchi di giorni ([delta]) si stampa accanto: se attraversa lo zero la cella e'
 * "margine incerto", un'informazione e non un verdetto.
 */
data class GateCell(
  val group: String,
  val scope: GateScope,
  val kind: TierKind,
  val window: String,
  val n: Int,
  val pipelineBrier: Double,
  /** La migliore fra le baseline oneste applicabili; null se la cella non ha casi. */
  val bestBaseline: String?,
  val bestBrier: Double,
  /** La differenza appaiata pipeline - migliore baseline, con il suo IC95%. */
  val delta: BootstrapSummary?,
) {
  /** La cella non ha casi o non ha baseline: non si puo' dire che passi. */
  val missing: Boolean get() = n == 0 || bestBaseline == null || pipelineBrier.isNaN()

  val margin: Double get() = if (missing) Double.NaN else pipelineBrier - bestBrier

  val passed: Boolean get() = !missing && margin < 0

  /** L'IC95% attraversa lo zero (o lo tocca): il segno del margine non e' sicuro. */
  val uncertain: Boolean get() = delta != null && delta.low <= 0 && delta.high >= 0

  /** L'esito in parole, come nella tabella. */
  val verdict: String
    get() {
      val base = when {
        missing -> "MANCA"
        passed -> "passa"
        scope == GateScope.HARD -> "FALLISCE"
        else -> "avviso"
      }
      return if (!missing && uncertain) "$base (margine incerto)" else base
    }
}

/** Il gate di un periodo per una pipeline: tutte le celle, e cosa ne segue. */
class GateResult(
  val period: String,
  /** L'etichetta di versione della pipeline giudicata ([dev.pampa.fluidweather.nowcast.verdict.ModelVersions]). */
  val tag: String,
  /** La colonna del replay che e' stata giudicata. */
  val column: String,
  val cells: List<GateCell>,
) {
  /** Le celle dure che non passano (anche quelle mancanti: non si certifica cio' che non si e' giocato). */
  val failures: List<GateCell> get() = cells.filter { it.scope == GateScope.HARD && !it.passed }

  /** Le celle solo-avviso che non passano. */
  val warnings: List<GateCell> get() = cells.filter { it.scope == GateScope.WARN && !it.passed }

  val passed: Boolean get() = failures.isEmpty()

  /** L'ultima riga del rapporto, quella che si legge da uno script. */
  fun finalLine(): String = if (passed) "GATE: PASS $tag" else "GATE: FAIL $tag (${failures.size} celle)"
}

/**
 * Il gate d'indipendenza (D1): la pipeline del telefono **appena installato** — apprendimento vuoto,
 * cioe' modello grezzo (o ricalibrato) piu' pavimenti — deve battere la migliore baseline onesta
 * applicabile in ogni localita' x livello x finestra, sulla verita' del pannello.
 *
 * Perche' apprendimento vuoto: e' cio' che ogni telefono vede il primo giorno, e la ricalibrazione
 * personale non puo' essere la stampella che tiene in piedi un modello che da solo non batte la
 * climatologia del posto. Le baseline applicabili sono climatologia, persistenza (solo dove il contesto
 * dice se piove adesso) e regola barometrica; nel livello senza climatologia, le loro versioni di
 * tutte le localita'. Le PoP dei provider non ci sono mai (hanno fuga), ne' "sempre-0".
 *
 * Duro: le sei localita' europee una per una e il loro insieme (dove vive l'app). Solo avviso: le
 * altre quattro, dove il barometro puo' non aggiungere niente e un pareggio con la climatologia e' il
 * meglio possibile — e quindi anche l'insieme di TUTTE, che le contiene: se fosse duro, una localita'
 * "solo avviso" potrebbe bocciare il gate passando dall'insieme (a TEST la cella TUTTE FRESH 0-1h
 * falliva per singapore da sola, con l'Europa sotto la persistenza di mezzo centesimo). Una localita'
 * dura che non si e' potuta giocare boccia: non si certifica cio' che non si e' guardato.
 *
 * La logica e' pura ([evaluate] su un [TierReplayResult]); [run] gioca il periodo e scrive, e solo la
 * riga di comando esce con codice 1.
 */
object IndependenceGate {

  /** Il nome della colonna della pipeline di un modello nel gate: apprendimento vuoto, pavimenti di oggi. */
  fun columnFor(model: PipelineModel): String = "${model.name} +pavimenti"

  /**
   * Le celle del gate su un replay che ha le baseline e la colonna [column].
   * [hardLocations] sono dure una per una e nel loro insieme; le altre giocate sono solo avviso, e
   * con loro l'insieme di tutte (duro solo se non contiene nessuna localita' solo-avviso).
   */
  fun evaluate(
    result: TierReplayResult,
    column: String,
    tag: String,
    hardLocations: List<String> = TierGroups.EUROPA,
    kinds: List<TierKind> = TierKind.PRIMARY,
    bootstrap: DayBlockBootstrap = DayBlockBootstrap(),
  ): GateResult {
    val stats = TierStats(result)
    val pipeline = result.indexOf(column)
    require(pipeline >= 0) { "il replay non ha la colonna '$column'" }
    val played = result.locations
    val hardPlayed = hardLocations.filter { it in played }

    val groups = buildList {
      add(Triple("${TierGroups.EUROPA_LABEL.substringBefore(" (")} (${hardPlayed.size}/${hardLocations.size})", hardPlayed, GateScope.HARD))
      val allScope = if (played.all { it in hardLocations }) GateScope.HARD else GateScope.WARN
      add(Triple("TUTTE (${played.size})", played, allScope))
      for (location in hardLocations) add(Triple(location, listOf(location).filter { it in played }, GateScope.HARD))
      for (location in played.filter { it !in hardLocations }) add(Triple(location, listOf(location), GateScope.WARN))
    }

    val cells = ArrayList<GateCell>()
    for ((label, locations, scope) in groups) {
      for (kind in kinds) {
        for ((w, window) in RainWindows.ALL.withIndex()) {
          val cases = stats.cases(locations, kind, w, TruthKind.PANEL).filter { !it.probabilities[pipeline].isNaN() }
          val rows = stats.rows(cases, TruthKind.PANEL)
          val best = stats.bestBaseline(rows)
          val own = stats.byName(rows, column)
          val delta = best?.let { stats.paired(cases, TruthKind.PANEL, pipeline, result.indexOf(it.predictor), bootstrap) }
          cells += GateCell(
            group = label,
            scope = scope,
            kind = kind,
            window = window.label,
            n = own?.n ?: 0,
            pipelineBrier = own?.brier ?: Double.NaN,
            bestBaseline = best?.predictor,
            bestBrier = best?.brier ?: Double.NaN,
            delta = delta,
          )
        }
      }
    }
    return GateResult(result.period.name, tag, column, cells)
  }

  /** Il rapporto del gate: regola, tabella, celle fallite, avvisi, e per ultima la riga del verdetto. */
  fun render(
    gate: GateResult,
    model: PipelineModel,
    period: TierPeriod,
    runNumber: Int,
    skipped: Map<String, String>,
    fingerprintRunNumber: Int = runNumber,
  ): String = render(gate, PipelineSubject(model), period, runNumber, skipped, fingerprintRunNumber)

  /** Lo stesso per un soggetto qualsiasi; [extra] sono le sezioni in piu' del v3 (TARGET, paracadute), prima della riga finale. */
  fun render(
    gate: GateResult,
    subject: GateSubject,
    period: TierPeriod,
    runNumber: Int,
    skipped: Map<String, String>,
    fingerprintRunNumber: Int = runNumber,
    extra: String = "",
    header: List<String> = emptyList(),
  ): String = buildString {
    appendLine("=== GATE D1 — indipendenza del barometro — periodo ${period.name.uppercase()} — ${gate.tag} ===")
    appendLine()
    val forced = (subject as? V3Subject)?.forcedFamily == true
    appendLine("Generato da `gate ${period.name} --model ${subject.name}${if (forced) " --family ${subject.family.lowercase()}" else ""}`. ${TruthPanel.ATTRIBUTION}.")
    appendLine(
      "Esecuzione n. $runNumber del gate su ${period.name.uppercase()} per questa etichetta" +
        (if (subject.countsByFingerprint) ", n. $fingerprintRunNumber per questa impronta ${if (subject.isV3) "dell'artefatto" else "delle mappe"} (${subject.fingerprint})" else "") +
        (if (subject.isV3) ", famiglia ${subject.family}" else "") +
        " (registro: reports/$LEDGER_NAME).",
    )
    for (line in header) appendLine(line)
    for (warning in subject.warnings(period)) appendLine(warning)
    appendLine()
    appendLine("Regola")
    appendLine("  Per ogni localita' x livello x finestra: Brier(pipeline) < min(baseline oneste applicabili), verita' PANNELLO (${TruthPanel.VERSION}),")
    appendLine("  sul valore puntuale. Pipeline = '${gate.column}': ${subject.description()},")
    if (subject.isV3) {
      appendLine("  apprendimento VUOTO (il telefono appena installato: niente Platt personale, niente analoghi); 'piove adesso' dei pavimenti = l'ultimo")
      appendLine("  slot chiuso del contesto (la stessa pioggia che il modello vede come feature), solo FRESH e STALE.")
    } else {
      appendLine("  apprendimento VUOTO (il telefono appena installato: niente Platt personale, niente analoghi), pavimenti di oggi dal contesto")
      appendLine("  orario (ultimo slot chiuso >= ${dev.pampa.fluidweather.nowcast.learning.RainObservation.RAINING_FROM_MM_PER_HOUR} mm = piove adesso; solo FRESH e STALE).")
    }
    appendLine("  Baseline applicabili: climatologia, persistenza (FRESH e STALE), regola-barometrica; in NONE_NOCLIMA quelle di tutte le localita'.")
    appendLine("  Le PoP dei provider (con fuga) e sempre-0 non entrano.")
    appendLine("  Duro: ${TierGroups.EUROPA.joinToString(", ")} e l'insieme EUROPA. Solo avviso: le altre e l'insieme TUTTE, che le contiene.")
    appendLine("  margine = Brier(pipeline) - Brier(migliore): negativo = passa. IC95% appaiato, bootstrap a blocchi di giorni (1000):")
    appendLine("  se attraversa lo zero la cella e' 'margine incerto' — si stampa, non cambia l'esito.")
    appendLine("  Emissioni ogni 3 h a minuto casuale, contesto com'era al fetch, baseline dai due anni prima del periodo (vedi replay-tiers).")
    appendLine()
    for ((location, why) in skipped) appendLine("  NON GIOCATA: $location - $why")
    if (skipped.isNotEmpty()) appendLine()

    appendLine("--- TABELLA")
    appendLine(
      String.format(
        Locale.ROOT, "  %-22s %-6s %-12s %-5s %6s %9s  %-19s %8s %9s  %-19s %s",
        "gruppo", "ambito", "livello", "fin.", "n", "pipeline", "migliore baseline", "Brier", "margine", "IC95%", "esito",
      ),
    )
    var previous: String? = null
    for (cell in gate.cells) {
      if (previous != null && previous != cell.group) appendLine()
      previous = cell.group
      appendLine(
        String.format(
          Locale.ROOT, "  %-22s %-6s %-12s %-5s %6d %9s  %-19s %8s %9s  %-19s %s",
          cell.group, cell.scope.label, cell.kind.label, cell.window, cell.n, Fmt.f4(cell.pipelineBrier),
          cell.bestBaseline ?: "-", Fmt.f4(cell.bestBrier), Fmt.sgn4(cell.margin), Fmt.interval(cell.delta), cell.verdict,
        ),
      )
    }
    appendLine()

    appendLine("--- CELLE DURE CHE NON PASSANO (${gate.failures.size})")
    if (gate.failures.isEmpty()) appendLine("  nessuna.")
    for (cell in gate.failures) appendLine("  ${describe(cell)}")
    appendLine()
    appendLine("--- AVVISI (solo avviso, ${gate.warnings.size})")
    if (gate.warnings.isEmpty()) appendLine("  nessuno.")
    for (cell in gate.warnings) appendLine("  ${describe(cell)}")
    appendLine()
    val uncertainPasses = gate.cells.count { it.passed && it.uncertain }
    appendLine("Celle che passano con margine incerto: $uncertainPasses su ${gate.cells.count { it.passed }}.")
    appendLine()
    if (extra.isNotEmpty()) {
      append(extra)
      appendLine()
    }
    appendLine(gate.finalLine())
  }

  private fun describe(cell: GateCell): String = String.format(
    Locale.ROOT, "%-22s %-12s %-5s pipeline %s contro %s %s: margine %s %s%s",
    cell.group, cell.kind.label, cell.window, Fmt.f4(cell.pipelineBrier), cell.bestBaseline ?: "-", Fmt.f4(cell.bestBrier),
    Fmt.sgn4(cell.margin), Fmt.interval(cell.delta), if (cell.uncertain) " (margine incerto)" else "",
  )

  // ------------------------------------------------------------------ il comando

  const val LEDGER_NAME: String = "gate-runs.log"

  /**
   * Gli argomenti di `gate`, gia' controllati. Il soggetto si crea solo quando serve ([subject]): il
   * candidato del v3 si legge da disco, e controllare una riga di comando non deve toccare il disco.
   */
  class Arguments(
    val period: TierPeriod,
    val subjectName: String,
    /** La famiglia forzata del v3 (`--family`), o null. */
    val family: ModelFamily? = null,
  ) {
    /** Il v2 o il v2r, se e' uno di loro. */
    val model: PipelineModel? get() = PipelineModels.ALL.firstOrNull { it.name == subjectName }

    fun subject(): GateSubject = GateSubjects.create(subjectName, family)
  }

  /**
   * `gate <validation|test> [--model <nome>] [--family logistica|gbm]`, e niente altro. Null su qualunque
   * altra forma: un refuso ("--modle v2r", "v2r" senza `--model`, `--model` senza nome) non deve giocare in
   * silenzio il modello di default, perche' su TEST ogni esecuzione si conta nel registro e non si restituisce.
   * `--family` vale solo per il v3.
   */
  fun parseArguments(args: List<String>): Arguments? {
    val period = TierPeriods.byName(args.firstOrNull()) ?: return null
    val options = args.drop(1)
    if (options.size % 2 != 0) return null
    val map = LinkedHashMap<String, String>()
    for (i in options.indices step 2) {
      val key = options[i]
      if (key !in setOf("--model", "--family") || key in map) return null
      map[key] = options[i + 1]
    }
    val name = map["--model"] ?: PipelineModels.V2.name
    if (!GateSubjects.isKnown(name)) return null
    val family = map["--family"]?.let { text -> ModelFamily.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: return null }
    if (family != null && name !in setOf(GateSubjects.V3, GateSubjects.V3_CANDIDATE)) return null
    return Arguments(period, name, family)
  }

  /**
   * Perche' un gate non si gioca (null = si gioca), prima di toccare i dati. TEST e' dell'artefatto
   * compilato, una volta sola per versione, con la famiglia che spedisce:
   * - il candidato su TEST non si gioca mai (si sceglie su VALIDATION, si certifica il compilato);
   * - `--family` su TEST no: si certifica cio' che il telefono carica;
   * - il v3 su TEST si rifiuta se il registro ha gia' una riga di TEST per la stessa versione.
   */
  fun refusal(arguments: Arguments, reports: File = File("reports")): String? {
    if (arguments.period.name != TierPeriods.TEST.name) return null
    return when (arguments.subjectName) {
      GateSubjects.V3_CANDIDATE -> "il candidato non si gioca su TEST: TEST e' solo per l'artefatto compilato (`gate test --model v3`)."
      GateSubjects.V3 -> {
        if (arguments.family != null) return "su TEST si certifica la famiglia che il telefono carica: niente --family."
        val tag = ModelVersions.tag(model = TrainedNowcastV3.VERSION)
        val previous = ledgerLines(reports).firstOrNull { it.size >= 3 && it[1] == TierPeriods.TEST.name && it[2] == tag }
        previous?.let { "TEST e' gia' stato giocato per ${TrainedNowcastV3.VERSION}: ${it.joinToString(" ")}" }
      }
      else -> null
    }
  }

  private fun ledgerLines(reports: File): List<List<String>> {
    val ledger = File(reports, LEDGER_NAME)
    return if (ledger.exists()) ledger.readLines().map { it.split(' ') } else emptyList()
  }

  /** Il gate giocato, il suo testo e il numero d'esecuzione: la riga di comando decide il codice d'uscita. */
  class Outcome(val gate: GateResult, val report: String)

  /** La vecchia forma, per il v2 e il v2r. */
  fun run(
    period: TierPeriod,
    model: PipelineModel,
    dataRoot: File = File("data"),
    reports: File = File("reports"),
    log: (String) -> Unit = ::println,
  ): Outcome = run(period, PipelineSubject(model), dataRoot, reports, log)

  /**
   * `gate <periodo> [--model <nome>]`: gioca il periodo con la pipeline ad apprendimento vuoto, giudica,
   * annota l'esecuzione nel registro [LEDGER_NAME] dentro [reports] e ritorna il rapporto.
   * La riga del registro: `<istante> <periodo> <etichetta> PASS|FAIL <celle> <impronta> <famiglia>`.
   */
  fun run(
    period: TierPeriod,
    subject: GateSubject,
    dataRoot: File = File("data"),
    reports: File = File("reports"),
    log: (String) -> Unit = ::println,
  ): Outcome {
    val loaded = TierBench.loadInputs(dataRoot, log)
    val header = ArrayList<String>()
    if (subject.isV3 && period.name == TierPeriods.TEST.name) {
      // Le baseline del gate devono essere annidate nel modello: il riferimento di tutti i posti che il
      // modello porta con se' e' quello delle baseline di TEST, o il confronto non e' piu' quello addestrato.
      val gatePooled = HonestBaselines.buildAll(period, loaded.inputs).values.first().pooledOnly()?.pooled?.encode()
      check(gatePooled == TrainedNowcastV3.POOLED) {
        "TrainedNowcastV3.POOLED non e' il riferimento di tutti i posti delle baseline di TEST: le baseline non sarebbero annidate nel modello"
      }
      header += "Il riferimento di tutti i posti del modello coincide con quello delle baseline di TEST (controllo passato)."
    }
    val column = subject.column
    log("gioco $period per il gate di ${subject.tag} (${TierReplayer.defaultThreads()} thread)...")
    val result = TierReplayer(period, kinds = TierKind.PRIMARY, log = log, extras = listOf(subject.predictor()) + subject.informational).replay(loaded.inputs)
    val gate = evaluate(result, column, subject.tag)

    val lines = ledgerLines(reports)
    val sameTag = lines.filter { p -> p.size >= 3 && p[1] == period.name && p[2] == subject.tag }
    val runNumber = sameTag.size + 1
    // Le righe scritte prima dell'impronta non la portano: per quelle non si sa, e non contano.
    val fingerprintRunNumber = sameTag.count { p -> p.size >= 6 && p[5] == subject.fingerprint } + 1
    if (subject.isV3) {
      val earlierTests = lines.count { p -> p.size >= 3 && p[1] == TierPeriods.TEST.name && p[2].startsWith("v3") }
      header += "Esecuzioni precedenti di un v3 su TEST nel registro: $earlierTests."
    }
    val extra = if (subject is V3Subject) V3GateSections.render(result, subject, period) else ""
    val report = render(gate, subject, period, runNumber, loaded.missing + result.skipped, fingerprintRunNumber, extra, header)
    reports.mkdirs()
    File(reports, LEDGER_NAME).appendText(
      "${Instant.now()} ${period.name} ${subject.tag} ${if (gate.passed) "PASS" else "FAIL"} ${gate.failures.size} ${subject.fingerprint} ${subject.family}\n",
    )
    return Outcome(gate, report)
  }

  /** Il nome del rapporto di un gate. */
  fun reportName(period: TierPeriod, model: PipelineModel): String =
    if (model.name == "v2") "gate-${period.name}.txt" else "gate-${period.name}-${model.name}.txt"

  /** Il nome del rapporto di un gate per nome e famiglia forzata. */
  fun reportName(arguments: Arguments): String {
    val model = arguments.model
    if (model != null) return reportName(arguments.period, model)
    val family = arguments.family?.let { "-${it.name.lowercase()}" } ?: ""
    return "gate-${arguments.period.name}-${arguments.subjectName}$family.txt"
  }
}
