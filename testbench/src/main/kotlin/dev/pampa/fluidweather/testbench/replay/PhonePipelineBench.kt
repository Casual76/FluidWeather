package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.learning.PlattCalibration
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.metrics.ReliabilityPrinter
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import java.io.File
import java.util.Locale

/**
 * `phone-pipeline <validation|test>`: cio' che il telefono mostra davvero, non il verdetto grezzo.
 *
 * Sulle stesse emissioni del banco onesto, accanto alle baseline:
 *
 * - `nowcast-spedito` — il v2 grezzo;
 * - `v2 +pavimenti` — il telefono appena installato: [NowcastEngine][dev.pampa.fluidweather.nowcast.learning.NowcastEngine]
 *   con apprendimento vuoto e i pavimenti dal contesto orario (e' la pipeline del gate);
 * - `v2 telefono-oggi` — il telefono che vive tutto il periodo: Platt personale ristimato ogni sei ore,
 *   analoghi, pavimenti, con gli esiti che arrivano solo dopo la finalita' del pannello ([TodayLearning]);
 * - `v2r` e `v2r +pavimenti` — la ricalibrazione v2r da sola e con i pavimenti (la pipeline del gate v2r);
 * - le PoP dei provider con fuga, solo FRESH, come riferimento.
 *
 * Un telefono per localita' e per livello, nuovo all'inizio del periodo.
 */
object PhonePipelineBench {

  val V2_FLOORS: String = "v2 +pavimenti"
  val V2_PHONE: String = "v2 telefono-oggi"
  val V2R: String = "v2r"
  val V2R_FLOORS: String = "v2r +pavimenti"

  fun reportName(period: TierPeriod): String = "phone-pipeline-${period.name}.txt"

  fun run(
    period: TierPeriod,
    dataRoot: File = File("data"),
    log: (String) -> Unit = ::println,
    /** Il v3 da mettere accanto (`--model v3|v3-candidate`): nome e modello. */
    v3: Pair<String, dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel>? = null,
  ): String {
    val loaded = TierBench.loadInputs(dataRoot, log)
    if (loaded.inputs.isEmpty()) return "phone-pipeline: nessuna localita' con i dati completi in ${dataRoot.path}"
    val diagnostics = PipelineDiagnostics()
    val extras = listOf(
      PhonePipeline(V2_FLOORS, PipelineModels.V2, EmptyLearning, ContextHourFloors),
      PhonePipeline(V2_PHONE, PipelineModels.V2, TodayLearning, ContextHourFloors, diagnostics),
      PhonePipeline(V2R, PipelineModels.V2R, EmptyLearning, NoFloors),
      PhonePipeline(V2R_FLOORS, PipelineModels.V2R, EmptyLearning, ContextHourFloors),
      LeakyProviderPop(),
    ) + (v3?.let { (name, model) -> listOf(V3Pipeline("$name +pavimenti", model), V3Pipeline("$name grezzo", model, raw = true)) } ?: emptyList())
    log("gioco $period con la pipeline del telefono (${TierReplayer.defaultThreads()} thread)...")
    val result = TierReplayer(
      period,
      model = PipelineModels.V2.nowcast,
      kinds = TierKind.PRIMARY,
      log = log,
      extras = extras,
    ).replay(loaded.inputs)
    val report = PhonePipelineReport(TierBench.withMissing(result, loaded.missing), diagnostics, "phone-pipeline ${period.name}").render()
    if (v3 == null) return report
    val name = v3.first
    return report + "\n" + V3Report.section(
      result,
      "IL V3 ACCANTO ALLE PIPELINE DEL TELEFONO ($name, ${v3.second.version}, famiglia ${v3.second.activeFamily}; apprendimento vuoto)",
      listOf("$name +pavimenti" to "v3+pav", "$name grezzo" to "v3 grezzo", V2_FLOORS to "v2+pav", V2_PHONE to "v2 oggi", PredictorNames.MODELLO to "v2 grezzo"),
    )
  }
}

/** Il rapporto di `phone-pipeline`: grezzo contro finale, per livello, finestra e localita'. */
class PhonePipelineReport(
  private val result: TierReplayResult,
  private val diagnostics: PipelineDiagnostics,
  private val command: String,
) {
  private val stats = TierStats(result)
  private val windows = RainWindows.ALL
  private val played = result.locations
  private val europa = TierGroups.EUROPA.filter { it in played }
  private val groups: List<Pair<String, List<String>>> = listOf(
    "EUROPA (${europa.size})" to europa,
    TierGroups.SESTO to listOf(TierGroups.SESTO).filter { it in played },
    "TUTTE (${played.size})" to played,
  )
  private val pipelineColumns = listOf(
    PredictorNames.MODELLO, PhonePipelineBench.V2_FLOORS, PhonePipelineBench.V2_PHONE,
    PhonePipelineBench.V2R, PhonePipelineBench.V2R_FLOORS,
  )
  private val shortNames = listOf("v2 grezzo", "v2+pav", "v2 oggi", "v2r", "v2r+pav")
  private val leaky = result.predictorNames.filter { LeakyProviderPop.isLeaky(it) }

  fun render(): String = buildString {
    header(this)
    appendLine("--- SINTESI — Brier per pipeline, verita' PANNELLO (le PoP dei provider solo in FRESH, ${LeakyProviderPop.LEAK_LABEL})")
    brierTable(this, groups)
    appendLine("--- DIFFERENZE APPAIATE — IC95% bootstrap a blocchi di giorni")
    deltaTable(this, groups)
    appendLine("--- PER LOCALITA' — Brier per pipeline (${TierGroups.WARN_ONLY.joinToString(", ")}: solo avviso)")
    brierTable(this, played.map { it to listOf(it) })
    learning(this)
    reliability(this)
    notes(this)
  }

  private fun header(out: StringBuilder) {
    val period = result.period
    out.appendLine("=== PIPELINE DEL TELEFONO — cio' che l'app mostra, non il verdetto grezzo — periodo ${period.name.uppercase()} (emissioni ${Fmt.date(period.firstMillis)}..${Fmt.date(period.endExclusiveMillis - 1)}) ===")
    out.appendLine()
    out.appendLine("Generato da `$command`. ${TruthPanel.ATTRIBUTION}.")
    out.appendLine()
    out.appendLine("Come si legge")
    out.appendLine("  Stessi casi del banco onesto (replay-tiers): emissioni ogni 3 h a minuto casuale, barometro TELEFONO sintetizzato da ERA5,")
    out.appendLine("  contesto best_match com'era al fetch, baseline oneste dai due anni prima del periodo, verita' PANNELLO (${TruthPanel.VERSION}).")
    out.appendLine("  v2 grezzo  ${PredictorNames.MODELLO}: NowcastModel.trained() (${PipelineModels.V2.version}), nient'altro.")
    out.appendLine("  v2+pav     il vero NowcastEngine con apprendimento VUOTO e i pavimenti di RainObservation: piove adesso = l'ultimo slot chiuso")
    out.appendLine("             del contesto >= ${RainObservation.RAINING_FROM_MM_PER_HOUR} mm (proxy orario del quarto d'ora); FRESH e STALE soltanto. E' la pipeline del gate v2.")
    out.appendLine("  v2 oggi    il telefono che vive il periodo da nuovo: ogni emissione si iscrive con le probabilita' grezze, gli esiti arrivano")
    out.appendLine("             ${TruthPanel.FINALITY_MILLIS / 3_600_000L} h dopo la fine dell'ultimo slot della finestra, Platt si ristima ogni 6 h (corpus con contesto se >= ${PlattCalibration.MIN_SAMPLES * 2},")
    out.appendLine("             fit da ${PlattCalibration.MIN_SAMPLES} coppie), analoghi da tutte le emissioni con esito, poi i pavimenti. Stesso codice del telefono.")
    out.appendLine("  v2r        la ricalibrazione v2r (${PipelineModels.V2R.version}) sul grezzo, senza pavimenti; v2r+pav: con i pavimenti (la pipeline del gate v2r).")
    if (leaky.isNotEmpty()) out.appendLine("  provider   ${leaky.joinToString(" · ")}: riferimento, mai giudice.")
    out.appendLine("  migliore   la migliore baseline onesta (climatologia, persistenza, regola-barometrica) sugli stessi casi.")
    out.appendLine("  Un telefono ogni tre ore iscrive un terzo delle emissioni del telefono vero (uno l'ora): Platt vi arriva piu' tardi.")
    TierBench.v2InSampleNote(period)?.let { out.appendLine("  $it") }
    if (period.firstMillis < TierPeriods.VALIDATION.endExclusiveMillis) {
      out.appendLine("  ATTENZIONE: le mappe v2r sono stimate su TRAIN+VALIDATION: su ${period.name.uppercase()} le colonne v2r sono in campione.")
    }
    out.appendLine()
  }

  private fun brierTable(out: StringBuilder, groupList: List<Pair<String, List<String>>>) {
    out.append(
      String.format(
        Locale.ROOT, "  %-18s %-12s %-5s %6s %6s  %-26s",
        "gruppo", "livello", "fin.", "n", "base", "migliore baseline",
      ),
    )
    for (name in shortNames) out.append(String.format(Locale.ROOT, " %8s", name))
    for (column in leaky) out.append(String.format(Locale.ROOT, " %9s", column.substringBefore(' ').substringBefore('_') + "*"))
    out.appendLine()
    for ((label, locations) in groupList) {
      if (locations.isEmpty()) continue
      for (kind in TierKind.PRIMARY) {
        for ((w, window) in windows.withIndex()) {
          val cases = stats.cases(locations, kind, w, TruthKind.PANEL)
          if (cases.isEmpty()) continue
          val rows = stats.rows(cases, TruthKind.PANEL)
          val best = stats.bestBaseline(rows)
          val clima = stats.byName(rows, PredictorNames.CLIMATOLOGIA)
          out.append(
            String.format(
              Locale.ROOT, "  %-18s %-12s %-5s %6d %6s  %-26s",
              label + if (label in TierGroups.WARN_ONLY) "~" else "", kind.label, window.label, clima?.n ?: cases.size,
              Fmt.f3(clima?.base ?: Double.NaN), if (best == null) "-" else "${best.predictor} ${Fmt.f4(best.brier)}",
            ),
          )
          for (column in pipelineColumns) out.append(String.format(Locale.ROOT, " %8s", Fmt.f4(stats.byName(rows, column)?.brier ?: Double.NaN)))
          for (column in leaky) out.append(String.format(Locale.ROOT, " %9s", Fmt.f4(stats.byName(rows, column)?.brier ?: Double.NaN)))
          out.appendLine()
        }
      }
    }
    out.appendLine("  (* = PoP del provider ${LeakyProviderPop.LEAK_LABEL}; ~ = localita' solo avviso)")
    out.appendLine()
  }

  private fun deltaTable(out: StringBuilder, groupList: List<Pair<String, List<String>>>) {
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %-12s %-5s | %-28s | %-28s | %-28s | %s",
        "gruppo", "livello", "fin.", "pavimenti: v2+pav - grezzo", "apprendimento: oggi - v2+pav", "v2r+pav - migliore", "esito v2r+pav",
      ),
    )
    val raw = result.indexOf(PredictorNames.MODELLO)
    val floors = result.indexOf(PhonePipelineBench.V2_FLOORS)
    val phone = result.indexOf(PhonePipelineBench.V2_PHONE)
    val v2rFloors = result.indexOf(PhonePipelineBench.V2R_FLOORS)
    for ((label, locations) in groupList) {
      if (locations.isEmpty()) continue
      for (kind in TierKind.PRIMARY) {
        for ((w, window) in windows.withIndex()) {
          val cases = stats.cases(locations, kind, w, TruthKind.PANEL)
          if (cases.isEmpty()) continue
          val best = stats.bestBaseline(stats.rows(cases, TruthKind.PANEL))
          val floorEffect = stats.paired(cases, TruthKind.PANEL, floors, raw)
          val learningEffect = stats.paired(cases, TruthKind.PANEL, phone, floors)
          val againstBest = best?.let { stats.paired(cases, TruthKind.PANEL, v2rFloors, result.indexOf(it.predictor)) }
          out.appendLine(
            String.format(
              Locale.ROOT, "  %-18s %-12s %-5s | %-28s | %-28s | %-28s | %s",
              label, kind.label, window.label, pair(floorEffect), pair(learningEffect), pair(againstBest), Fmt.verdict(againstBest),
            ),
          )
        }
      }
    }
    out.appendLine("  (negativo = meglio: i pavimenti aiutano se il primo e' negativo, l'apprendimento se il secondo e' negativo)")
    out.appendLine()
  }

  private fun pair(summary: dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary?): String =
    if (summary == null) "-" else "${Fmt.sgn4(summary.mean)} ${Fmt.interval(summary)}"

  private fun learning(out: StringBuilder) {
    out.appendLine("--- APPRENDIMENTO DEL TELEFONO DI OGGI ('v2 oggi') — cosa ha fatto in ogni scenario, a fine periodo")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %-12s %6s %6s %6s | %-17s | %-35s | %-17s | %-17s",
        "localita'", "livello", "emiss.", "esiti", "stime", "Platt attivo %", "Platt finale a/b (0-1h, 1-3h, 3-6h)", "pavimento %", "analoghi %",
      ),
    )
    for (location in played) {
      for (kind in TierKind.PRIMARY) {
        val s = diagnostics.of(location, kind) ?: continue
        val share = { counts: IntArray -> counts.joinToString("/") { if (s.issues == 0) "-" else String.format(Locale.ROOT, "%.0f", 100.0 * it / s.issues) } }
        val platt = windows.joinToString(" ") { w ->
          s.finalPlatt[w.label]?.let { String.format(Locale.ROOT, "%.2f/%+.2f", it.a, it.b) } ?: "-"
        }
        out.appendLine(
          String.format(
            Locale.ROOT, "  %-18s %-12s %6d %6d %6d | %-17s | %-35s | %-17s | %-17s",
            location, kind.label, s.issues, s.outcomes, s.refits, share(s.plattActive), platt, share(s.floorsRaised), share(s.analogsUsed),
          ),
        )
      }
    }
    out.appendLine("  (percentuali per finestra 0-1h/1-3h/3-6h sulle emissioni; 'stime' = ristime di Platt che hanno prodotto almeno una mappa;")
    out.appendLine("   pavimento % = emissioni in cui l'osservazione ha alzato la probabilita' finale)")
    out.appendLine()
  }

  private fun reliability(out: StringBuilder) {
    out.appendLine("--- AFFIDABILITA' (10 gradini) — v2 grezzo | v2 oggi | v2r+pav, verita' PANNELLO")
    val columns = listOf(PredictorNames.MODELLO, PhonePipelineBench.V2_PHONE, PhonePipelineBench.V2R_FLOORS)
    for ((label, locations) in groups.take(2)) {
      if (locations.isEmpty()) continue
      for (kind in TierKind.PRIMARY) {
        for ((w, window) in windows.withIndex()) {
          val cases = stats.cases(locations, kind, w, TruthKind.PANEL)
          if (cases.isEmpty()) continue
          val series = columns.map { stats.forecastCases(cases, result.indexOf(it), TruthKind.PANEL) }
          out.appendLine("--- $label - ${kind.label} ${window.label} (n=${cases.size})")
          out.appendLine(ReliabilityPrinter.sideBySide(listOf("v2 grezzo", "v2 oggi", "v2r+pav"), series, indent = "  "))
        }
      }
    }
    out.appendLine()
  }

  private fun notes(out: StringBuilder) {
    out.appendLine("--- NOTE")
    out.appendLine("  - I pavimenti usano l'ora chiusa del contesto, non il quarto d'ora: in FRESH l'osservazione e' vecchia fino a 2,5 h, in STALE fino a 13 h.")
    out.appendLine("    Il radar non si rigioca. Sul telefono vero, in STALE, il quarto d'ora del bundle vecchio sarebbe una previsione, non un'osservazione.")
    out.appendLine("  - Gli esiti con cui il telefono impara sono quelli del pannello; sul telefono di oggi arrivano dal verificatore con la vecchia verita'.")
    out.appendLine("  - Ogni scenario e' un telefono che vive sempre nello stesso livello: il telefono vero passa da un livello all'altro e ha un solo Platt.")
    out.appendLine("  - Le PoP dei provider hanno fuga (stitched): nessun telefono le ha avute all'ora dell'emissione.")
    out.appendLine()
  }
}
