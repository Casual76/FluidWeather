package dev.pampa.fluidweather.testbench.recalibration

import dev.pampa.fluidweather.nowcast.scoring.ProperScores
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV1
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.metrics.ReliabilityPrinter
import dev.pampa.fluidweather.testbench.replay.CaseRecord
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
import java.util.Locale

/** La chiave di una mappa v2r: la finestra e la famiglia di livello (con o senza contesto dei provider). */
data class V2rKey(val window: String, val hasContext: Boolean)

/** Le sei mappe v2r e la versione che le identifica. */
class V2rTable(
  val version: String,
  /** La versione del modello grezzo su cui sono stimate: se il modello cambia, le mappe non valgono piu'. */
  val baseModelVersion: String,
  val fits: Map<V2rKey, RecalibrationFit>,
) {
  fun fit(window: String, hasContext: Boolean): RecalibrationFit =
    fits[V2rKey(window, hasContext)] ?: error("nessuna mappa v2r per $window / contesto=$hasContext")

  fun apply(window: String, hasContext: Boolean, probability: Double): Double = fit(window, hasContext).apply(probability)
}

/**
 * La ricalibrazione v2r: il modello v2 spedito oggi, riportato sull'etichetta del pannello.
 *
 * Il v2 e' stato addestrato su un'etichetta ERA5 (piu' bagnata del pannello di circa il 40%) e con
 * contesto sempre fresco: sul pannello dice troppa pioggia, e senza contesto dice il contesto medio.
 * Prima che arrivi il v3 (P4) la correzione minima e' una mappa di Platt per finestra e per famiglia di
 * livello — con contesto (FRESH e STALE insieme) e senza (NONE e NONE_NOCLIMA) — sulla probabilita'
 * grezza: logit(p') = a * logit(p) + b ([RidgePlattFit]).
 *
 * - **Stima**: emissioni di TRAIN e VALIDATION (dal 2022-11-24 al 2025-08-31), tutte e dieci le
 *   localita' (la popolazione su cui il v2 e' stato addestrato), verita' PANNELLO, stessi scenari del
 *   banco onesto (minuto di emissione casuale, contesto com'era al fetch). NONE e NONE_NOCLIMA danno
 *   la stessa probabilita' grezza (il modello non vede la climatologia) sugli stessi casi: si contano
 *   una volta sola.
 * - **Valutazione**: TEST, mai visto ne' dal v2 ne' dalla stima.
 * - **Artefatto**: `nowcast/.../verdict/RecalibrationV2r.kt`, generato qui come `TrainCommand` genera
 *   `TrainedNowcastV1.kt`; nessuno lo collega al motore (lo fara' P2).
 */
object V2rRecalibration {

  /** Il nome della colonna ricalibrata nei replay. */
  const val COLUMN: String = "v2r"

  /** Dove finisce l'artefatto, dalla cartella del banco (come `TrainCommand`). */
  const val TARGET_PATH: String = "../nowcast/src/main/kotlin/dev/pampa/fluidweather/nowcast/verdict/RecalibrationV2r.kt"

  /** Le famiglie in parole, per rapporto e KDoc. */
  fun familyLabel(hasContext: Boolean): String =
    if (hasContext) "contesto (FRESH+STALE)" else "senza contesto (NONE=NONE_NOCLIMA)"

  /**
   * La versione v2r di un modello v2: stessa data, cosi' si legge a quale v2 appartiene.
   * `v2-2026-09-10` -> `v2r-2026-09-10`.
   */
  fun versionFor(baseVersion: String): String {
    require(baseVersion.startsWith("v2-")) { "v2r si stima solo sopra un modello v2, non $baseVersion" }
    return "v2r-" + baseVersion.removePrefix("v2-")
  }

  /**
   * La famiglia di un livello per la stima: true = con contesto, false = senza, null = non entra
   * (NONE_NOCLIMA duplica NONE; le eta' fisse di STALE sono gia' dentro STALE).
   */
  fun familyOf(kind: TierKind): Boolean? = when (kind) {
    TierKind.FRESH, TierKind.STALE -> true
    TierKind.NONE -> false
    else -> null
  }

  /**
   * I casi di stima dai replay: probabilita' grezza del modello ed esito del pannello, per chiave.
   *
   * [fitUntilMillis] e' la fine (esclusa) dei periodi di stima: una finestra il cui ultimo slot si
   * chiude dopo non entra. Le emissioni delle ultime ore di VALIDATION hanno finestre che finiscono
   * nelle prime ore di TEST (l'ancora delle 21 del 31 agosto giudica la 3-6h fino alle 03 del primo
   * settembre): quegli esiti sono verita' di TEST, e una mappa stimata "solo su TRAIN e VALIDATION"
   * non li puo' vedere, nemmeno pochi.
   */
  fun collect(
    results: List<TierReplayResult>,
    fitUntilMillis: Long = Long.MAX_VALUE,
  ): Map<V2rKey, Pair<DoubleArray, BooleanArray>> {
    val probabilities = LinkedHashMap<V2rKey, ArrayList<Double>>()
    val outcomes = LinkedHashMap<V2rKey, ArrayList<Boolean>>()
    for (result in results) {
      val model = result.indexOf(PredictorNames.MODELLO)
      require(model >= 0) { "il replay non ha il modello" }
      for (record in result.records) {
        val hasContext = familyOf(record.kind) ?: continue
        if (RainWindows.lastSlotEnd(record.issueMillis, record.window) > fitUntilMillis) continue
        val outcome = record.outcome(TruthKind.PANEL)
        val p = record.probabilities[model]
        if (outcome < 0 || p.isNaN()) continue
        val key = V2rKey(record.window.label, hasContext)
        probabilities.getOrPut(key) { ArrayList() } += p
        outcomes.getOrPut(key) { ArrayList() } += outcome == 1
      }
    }
    return probabilities.keys.associateWith { key ->
      probabilities.getValue(key).toDoubleArray() to outcomes.getValue(key).toBooleanArray()
    }
  }

  /** Stima le sei mappe (arrotondate come nel file generato: il banco usa gli stessi numeri del telefono). */
  fun fitAll(samples: Map<V2rKey, Pair<DoubleArray, BooleanArray>>, baseModelVersion: String = TrainedNowcastV1.VERSION): V2rTable {
    val fits = LinkedHashMap<V2rKey, RecalibrationFit>()
    for (hasContext in listOf(true, false)) {
      for (window in RainWindows.ALL) {
        val key = V2rKey(window.label, hasContext)
        val (p, y) = samples[key] ?: error("nessun caso di stima per ${window.label} / ${familyLabel(hasContext)}")
        fits[key] = RidgePlattFit.fit(p, y).rounded()
      }
    }
    return V2rTable(versionFor(baseModelVersion), baseModelVersion, fits)
  }

  /** Aggiunge a un replay la colonna [column] calcolata da [probability] caso per caso (NaN = non risponde). */
  fun withColumn(result: TierReplayResult, column: String, probability: (CaseRecord) -> Double): TierReplayResult {
    val records = result.records.map { record ->
      CaseRecord(
        location = record.location,
        issueMillis = record.issueMillis,
        anchorMillis = record.anchorMillis,
        kind = record.kind,
        windowIndex = record.windowIndex,
        panelOutcome = record.outcome(TruthKind.PANEL),
        era5Outcome = record.outcome(TruthKind.ERA5),
        probabilities = record.probabilities + probability(record),
      )
    }
    return TierReplayResult(
      period = result.period,
      predictorNames = result.predictorNames + column,
      records = records,
      coverage = result.coverage,
      baselines = result.baselines,
      skipped = result.skipped,
    )
  }

  /** La colonna v2r di un replay che ha il modello grezzo. */
  fun withV2r(result: TierReplayResult, table: V2rTable): TierReplayResult {
    val model = result.indexOf(PredictorNames.MODELLO)
    require(model >= 0) { "il replay non ha il modello" }
    return withColumn(result, COLUMN) { record ->
      val p = record.probabilities[model]
      if (p.isNaN()) Double.NaN else table.apply(record.window.label, record.kind.hasContext, p)
    }
  }

  // ------------------------------------------------------------------ il comando

  /**
   * `recalibrate-v2r`: gioca TRAIN e VALIDATION (solo modello), stima le mappe, gioca TEST (baseline e
   * modello), scrive l'artefatto in [target] e ritorna il rapporto.
   */
  fun run(dataRoot: File = File("data"), target: File = File(TARGET_PATH), log: (String) -> Unit = ::println): String {
    val loaded = TierBench.loadInputs(dataRoot, log)
    if (loaded.inputs.isEmpty()) return "recalibrate-v2r: nessuna localita' con i dati completi in ${dataRoot.path}"
    val model = NowcastModel.trained()
    val fitPeriods = listOf(TierPeriods.TRAIN, TierPeriods.VALIDATION)

    val fitResults = fitPeriods.map { period ->
      log("gioco $period (solo modello, per la stima)...")
      TierReplayer(period, model = model, kinds = TierKind.PRIMARY, log = log, withBaselines = false).replay(loaded.inputs)
    }
    val samples = collect(fitResults, fitUntilMillis = fitPeriods.maxOf { it.endExclusiveMillis })
    val table = fitAll(samples)
    log("mappe stimate: ${table.version}")

    log("gioco ${TierPeriods.TEST} (baseline e modello, per la valutazione)...")
    val test = TierReplayer(TierPeriods.TEST, model = model, kinds = TierKind.PRIMARY, log = log).replay(loaded.inputs)
    val evaluated = withV2r(TierBench.withMissing(test, loaded.missing), table)

    target.parentFile?.mkdirs()
    target.writeText(emitKotlin(table, samples, fitPeriods))
    log(">> scritto ${target.path}")
    return report(table, samples, fitPeriods, evaluated)
  }

  // ------------------------------------------------------------------ l'artefatto

  /** Il file Kotlin generato: dati puri, una funzione pura, nessun collegamento al motore. */
  fun emitKotlin(
    table: V2rTable,
    samples: Map<V2rKey, Pair<DoubleArray, BooleanArray>>,
    fitPeriods: List<TierPeriod>,
  ): String = buildString {
    val from = Fmt.date(fitPeriods.minOf { it.firstMillis })
    val to = Fmt.date(fitPeriods.maxOf { it.endExclusiveMillis } - 1)
    appendLine("package dev.pampa.fluidweather.nowcast.verdict")
    appendLine()
    appendLine("import kotlin.math.exp")
    appendLine("import kotlin.math.ln")
    appendLine()
    appendLine("/**")
    appendLine(" * GENERATO da `gradlew :testbench:run --args=recalibrate-v2r` — non modificare a mano.")
    appendLine(" *")
    appendLine(" * La ricalibrazione v2r: il modello v2 spedito ([TrainedNowcastV1], versione [BASE_MODEL_VERSION])")
    appendLine(" * riportato sull'etichetta del pannello dei giudici (${TruthPanel.VERSION}), che e' piu' asciutta di ERA5")
    appendLine(" * su cui il v2 e' stato addestrato. Una mappa di Platt per finestra e per famiglia di livello:")
    appendLine(" *")
    appendLine(" *     logit(p') = a * logit(p) + b")
    appendLine(" *")
    appendLine(" * - con contesto dei provider ([WITH_CONTEXT]): i livelli FRESH e STALE insieme;")
    appendLine(" * - senza contesto ([WITHOUT_CONTEXT]): NONE e NONE_NOCLIMA (il modello non vede la climatologia,")
    appendLine(" *   quindi per lui sono lo stesso livello).")
    appendLine(" *")
    appendLine(" * Stimata al banco (reports/recalibration-v2r.txt) sulle emissioni dal $from al $to")
    appendLine(" * di dieci localita', ogni tre ore a minuto casuale, con il contesto com'era al fetch e la")
    appendLine(" * verita' del pannello; massima verosimiglianza con una cresta verso l'identita' e la pendenza")
    appendLine(" * tenuta in [${RidgePlattFit.A_MIN}, ${RidgePlattFit.A_MAX}]. Valutata sull'anno di TEST, che non ha visto.")
    appendLine(" *")
    appendLine(" * Dati puri: nessuno la collega a [NowcastModel] o al motore (lo fara' P2). Stessa forma e stessi")
    appendLine(" * ritagli di `PlattParams`, cosi' la si puo' comporre con la ricalibrazione personale.")
    appendLine(" * Dati: Open-Meteo.com (CC BY 4.0), uso non commerciale.")
    appendLine(" */")
    appendLine("object RecalibrationV2r {")
    appendLine()
    appendLine("  /** La versione di cio' che dice il modello ricalibrato: la data e' quella del v2 su cui poggia. */")
    appendLine("  const val VERSION: String = \"${table.version}\"")
    appendLine()
    appendLine("  /** Il modello grezzo su cui le mappe sono stimate: con un altro modello non valgono. */")
    appendLine("  const val BASE_MODEL_VERSION: String = \"${table.baseModelVersion}\"")
    appendLine()
    appendLine("  /** Una mappa: [a] pendenza sul logit, [b] spostamento; [samples] casi su cui e' stata stimata. */")
    appendLine("  data class Coefficients(val a: Double, val b: Double, val samples: Int)")
    appendLine()
    for (hasContext in listOf(true, false)) {
      val name = if (hasContext) "WITH_CONTEXT" else "WITHOUT_CONTEXT"
      val doc = if (hasContext) "Con il contesto dei provider (FRESH, STALE): finestra -> mappa." else "Senza contesto (NONE, NONE_NOCLIMA): finestra -> mappa."
      appendLine("  /** $doc */")
      appendLine("  val $name: Map<String, Coefficients> = mapOf(")
      for (window in RainWindows.ALL) {
        val fit = table.fit(window.label, hasContext)
        appendLine("    \"${window.label}\" to Coefficients(a = ${number(fit.a)}, b = ${number(fit.b)}, samples = ${fit.samples}),")
      }
      appendLine("  )")
      appendLine()
    }
    appendLine("  /** La mappa di una finestra (\"0-1h\", \"1-3h\", \"3-6h\") per la famiglia di livello. */")
    appendLine("  fun coefficients(windowLabel: String, hasContext: Boolean): Coefficients =")
    appendLine("    requireNotNull((if (hasContext) WITH_CONTEXT else WITHOUT_CONTEXT)[windowLabel]) { \"finestra sconosciuta: \$windowLabel\" }")
    appendLine()
    appendLine("  /**")
    appendLine("   * La probabilita' grezza del v2 ricalibrata: sigma(a * logit(p) + b), con p e il risultato tenuti")
    appendLine("   * in [1e-4, 1 - 1e-4] come fa `PlattParams`. Pura: stesso ingresso, stesso numero.")
    appendLine("   */")
    appendLine("  fun apply(windowLabel: String, hasContext: Boolean, probability: Double): Double {")
    appendLine("    val map = coefficients(windowLabel, hasContext)")
    appendLine("    val p = probability.coerceIn(EPS, 1 - EPS)")
    appendLine("    val z = map.a * ln(p / (1 - p)) + map.b")
    appendLine("    return (1.0 / (1.0 + exp(-z))).coerceIn(EPS, 1 - EPS)")
    appendLine("  }")
    appendLine()
    appendLine("  private const val EPS = 1e-4")
    appendLine("}")
    check(samples.isNotEmpty())
  }

  private fun number(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

  // ------------------------------------------------------------------ il rapporto

  private fun report(
    table: V2rTable,
    samples: Map<V2rKey, Pair<DoubleArray, BooleanArray>>,
    fitPeriods: List<TierPeriod>,
    test: TierReplayResult,
  ): String = buildString {
    val stats = TierStats(test)
    val raw = test.indexOf(PredictorNames.MODELLO)
    val v2r = test.indexOf(COLUMN)
    val played = test.locations
    val europa = TierGroups.EUROPA.filter { it in played }
    val groups = listOf(
      "EUROPA (${europa.size})" to europa,
      TierGroups.SESTO to listOf(TierGroups.SESTO).filter { it in played },
      "TUTTE (${played.size})" to played,
    )
    val from = Fmt.date(fitPeriods.minOf { it.firstMillis })
    val to = Fmt.date(fitPeriods.maxOf { it.endExclusiveMillis } - 1)

    appendLine("=== RICALIBRAZIONE v2r — il v2 spedito (${table.baseModelVersion}) sull'etichetta del pannello — ${table.version} ===")
    appendLine()
    appendLine("Generato da `recalibrate-v2r`. ${TruthPanel.ATTRIBUTION}.")
    appendLine()
    appendLine("Come si legge")
    appendLine("  Mappa       logit(p') = a * logit(p_grezza) + b, per finestra e per famiglia di livello: contesto = FRESH+STALE,")
    appendLine("              senza contesto = NONE (NONE_NOCLIMA ha la stessa probabilita' grezza sugli stessi casi: contato una volta).")
    appendLine("  Stima       emissioni $from..$to (TRAIN + VALIDATION), 10 localita', ogni 3 h a minuto casuale, contesto com'era al fetch,")
    appendLine("              verita' PANNELLO (${TruthPanel.VERSION}); massima verosimiglianza, cresta verso l'identita' (λ = ${RidgePlattFit.RIDGE} sulla somma),")
    appendLine("              pendenza in [${RidgePlattFit.A_MIN}, ${RidgePlattFit.A_MAX}]; coefficienti arrotondati a 6 decimali, gli stessi del file generato.")
    appendLine("              Solo finestre il cui ultimo slot si chiude entro la fine del $to: gli esiti che cadono in TEST restano fuori.")
    appendLine("              Attenzione: il v2 e' stato addestrato fino al 2025-08-31 (etichetta ERA5, ogni ora): la stima cade dentro il suo")
    appendLine("              periodo d'addestramento. TEST (${Fmt.date(TierPeriods.TEST.firstMillis)}..${Fmt.date(TierPeriods.TEST.endExclusiveMillis - 1)}) e' fuori per entrambi.")
    appendLine("  Valutazione TEST, verita' PANNELLO, stessi casi del banco onesto; grezzo = ${PredictorNames.MODELLO}, v2r = la mappa sul grezzo.")
    appendLine("              Niente pavimenti e niente apprendimento qui: la pipeline intera e' in gate-*.txt e phone-pipeline-*.txt.")
    appendLine("  Δ           differenze di Brier appaiate caso per caso, IC95% dal bootstrap a blocchi di giorni (1000 ricampionamenti).")
    appendLine("              migliore = la migliore baseline onesta (climatologia, persistenza, regola-barometrica) sugli stessi casi.")
    appendLine()

    appendLine("--- COEFFICIENTI (stima su $from..$to)")
    appendLine(
      String.format(
        Locale.ROOT, "  %-36s %-5s %8s %6s %8s %8s %9s %9s %-8s %9s %9s",
        "famiglia", "fin.", "n", "base", "p grezza", "p v2r", "a", "b", "recinto", "Brier gr.", "Brier v2r",
      ),
    )
    for (hasContext in listOf(true, false)) {
      for (window in RainWindows.ALL) {
        val fit = table.fit(window.label, hasContext)
        val (p, y) = samples.getValue(V2rKey(window.label, hasContext))
        var brierRaw = 0.0
        var brierV2r = 0.0
        var meanV2r = 0.0
        for (i in p.indices) {
          val q = fit.apply(p[i])
          meanV2r += q
          brierRaw += ProperScores.brier(p[i], y[i])
          brierV2r += ProperScores.brier(q, y[i])
        }
        appendLine(
          String.format(
            Locale.ROOT, "  %-36s %-5s %8d %6s %8s %8s %9s %9s %-8s %9s %9s",
            familyLabel(hasContext), window.label, fit.samples, Fmt.f3(fit.positives.toDouble() / fit.samples),
            Fmt.f3(p.average()), Fmt.f3(meanV2r / p.size), String.format(Locale.ROOT, "%.4f", fit.a),
            String.format(Locale.ROOT, "%+.4f", fit.b), if (fit.clamped) "FERMATA" else "libera",
            Fmt.f4(brierRaw / p.size), Fmt.f4(brierV2r / p.size),
          ),
        )
      }
    }
    appendLine("  (Brier in campione, sui casi della stima: dice quanto la mappa sposta, non quanto vale.)")
    appendLine()

    appendLine("--- TEST — prima (grezzo) e dopo (v2r), verita' PANNELLO")
    appendLine(
      String.format(
        Locale.ROOT, "  %-16s %-12s %-5s %6s %6s | %7s %7s %9s %-19s | %6s %6s | %7s %7s | %-26s %9s %-19s %s",
        "gruppo", "livello", "fin.", "n", "base", "grezzo", "v2r", "Δ v2r-gr", "IC95%", "p gr.", "p v2r",
        "LL gr.", "LL v2r", "migliore baseline", "Δ v2r-mig", "IC95%", "esito",
      ),
    )
    for ((label, locations) in groups) {
      if (locations.isEmpty()) continue
      for (kind in TierKind.PRIMARY) {
        for ((w, window) in RainWindows.ALL.withIndex()) {
          val cases = stats.cases(locations, kind, w, TruthKind.PANEL).filter { !it.probabilities[v2r].isNaN() }
          if (cases.isEmpty()) continue
          val rows = stats.rows(cases, TruthKind.PANEL)
          val rawRow = stats.byName(rows, PredictorNames.MODELLO) ?: continue
          val v2rRow = stats.byName(rows, COLUMN) ?: continue
          val best = stats.bestBaseline(rows)
          val deltaRaw = stats.paired(cases, TruthKind.PANEL, v2r, raw)
          val deltaBest = best?.let { stats.paired(cases, TruthKind.PANEL, v2r, test.indexOf(it.predictor)) }
          val meanRaw = cases.sumOf { it.probabilities[raw] } / cases.size
          val meanV2r = cases.sumOf { it.probabilities[v2r] } / cases.size
          appendLine(
            String.format(
              Locale.ROOT, "  %-16s %-12s %-5s %6d %6s | %7s %7s %9s %-19s | %6s %6s | %7s %7s | %-26s %9s %-19s %s",
              label, kind.label, window.label, v2rRow.n, Fmt.f3(v2rRow.base), Fmt.f4(rawRow.brier), Fmt.f4(v2rRow.brier),
              Fmt.sgn4(deltaRaw?.mean ?: Double.NaN), Fmt.interval(deltaRaw), Fmt.f3(meanRaw), Fmt.f3(meanV2r),
              Fmt.f3(rawRow.logLoss), Fmt.f3(v2rRow.logLoss),
              if (best == null) "-" else "${best.predictor} ${Fmt.f4(best.brier)}",
              Fmt.sgn4(deltaBest?.mean ?: Double.NaN), Fmt.interval(deltaBest), Fmt.verdict(deltaBest),
            ),
          )
        }
      }
    }
    appendLine("  (Δ v2r-gr negativo = la ricalibrazione migliora; esito = v2r contro la migliore baseline: VINCE / vince~ / perde~ / PERDE.)")
    appendLine()

    appendLine("--- AFFIDABILITA' su TEST (10 gradini) — grezzo | v2r: quando dice X%, succede Y% delle volte")
    for ((label, locations) in groups.take(2)) {
      if (locations.isEmpty()) continue
      for (kind in TierKind.PRIMARY) {
        for ((w, window) in RainWindows.ALL.withIndex()) {
          val cases = stats.cases(locations, kind, w, TruthKind.PANEL)
          val before = stats.forecastCases(cases, raw, TruthKind.PANEL)
          val after = stats.forecastCases(cases, v2r, TruthKind.PANEL)
          if (before.isEmpty()) continue
          appendLine("--- $label - ${kind.label} ${window.label} (n=${before.size})")
          appendLine(ReliabilityPrinter.sideBySide(listOf("grezzo", "v2r"), listOf(before, after), indent = "  "))
        }
      }
    }
    appendLine()
    appendLine("--- NOTE")
    for ((label, locations) in groups.take(2)) {
      if (locations.isEmpty()) continue
      for (hasContext in listOf(true, false)) {
        val kinds = TierKind.PRIMARY.filter { it.hasContext == hasContext }
        var better = 0
        var worse = 0
        var beatsBest = 0
        var cells = 0
        for (kind in kinds) {
          for (w in RainWindows.ALL.indices) {
            val cases = stats.cases(locations, kind, w, TruthKind.PANEL)
            val rows = stats.rows(cases, TruthKind.PANEL)
            val rawRow = stats.byName(rows, PredictorNames.MODELLO) ?: continue
            val v2rRow = stats.byName(rows, COLUMN) ?: continue
            val best = stats.bestBaseline(rows) ?: continue
            cells++
            if (v2rRow.brier < rawRow.brier) better++ else if (v2rRow.brier > rawRow.brier) worse++
            if (v2rRow.brier < best.brier) beatsBest++
          }
        }
        appendLine(
          "  - $label, ${kinds.joinToString("/") { it.label }}: v2r migliora il grezzo in $better celle su $cells, lo peggiora in $worse; " +
            "batte la migliore baseline in $beatsBest su $cells.",
        )
      }
    }
    appendLine("  - Una mappa sola per FRESH e STALE e' un compromesso: in STALE il grezzo tratta il contesto vecchio come fresco ed e' troppo sicuro,")
    appendLine("    in FRESH molto meno; la pendenza comune (a < 1) schiaccia entrambi. Le pendenze per famiglia sono nella tabella dei coefficienti.")
    appendLine("  - La mappa corregge la taratura, non l'informazione: senza contesto il grezzo ha poca abilita', e v2r lo avvicina al tasso medio")
    appendLine("    delle dieci localita', non a quello del posto — la regola barometrica locale conosce il posto, la mappa no.")
    appendLine("  - Una sola mappa per tutte le localita': dove una localita' e' molto piu' secca o piu' bagnata della media (singapore, denver)")
    appendLine("    la mappa la sposta nella direzione giusta per la media, non per lei.")
  }
}
