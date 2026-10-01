package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.metrics.ReliabilityPrinter
import dev.pampa.fluidweather.testbench.tiers.TierKind
import java.time.Instant
import java.util.Locale

/**
 * Il rapporto del banco onesto: `reports/baselines-<periodo>.txt` (solo baseline) e
 * `reports/replay-tiers-<periodo>.txt` (baseline piu' il modello spedito, verdetto grezzo).
 *
 * Tabelle per livello x finestra x predittore (n, tasso base, Brier, BSS, log-loss, MAE, distanza
 * dalla migliore baseline), per localita' e per gruppo: EUROPA (gate duro), TUTTE, e le quattro
 * localita' solo-avviso. La verita' primaria e' il PANNELLO; ERA5 e' una sezione a parte, da
 * leggere come avviso.
 */
class TierReportWriter(
  private val result: TierReplayResult,
  private val command: String,
  /** Righe in testa al rapporto (per esempio: questa e' una misura di sensibilita'). */
  private val notes: List<String> = emptyList(),
) {

  private val stats = TierStats(result)
  private val withModel = result.indexOf(PredictorNames.MODELLO) >= 0
  private val windows = RainWindows.ALL

  private class Group(val label: String, val locations: List<String>, val warnOnly: Boolean = false)

  private val played = result.locations
  private val europaPlayed = TierGroups.EUROPA.filter { it in played }
  private val europa = Group("EUROPA (${europaPlayed.size})", europaPlayed)
  private val pooled = Group("TUTTE (${played.size})", played)
  private val singles = played.map { Group(it, listOf(it), warnOnly = it in TierGroups.WARN_ONLY) }
  private val sesto = singles.firstOrNull { it.label == TierGroups.SESTO }
  private val summaryGroups = listOf(europa, pooled) + singles
  private val kinds = TierKind.PRIMARY

  /** Le PoP dei provider con fuga, se il replay le ha: righe di riferimento nelle tabelle FRESH, mai altro. */
  private val leakyColumns = result.predictorNames.filter { LeakyProviderPop.isLeaky(it) }

  /** La colonna del predittore: si allarga solo se ci sono i nomi lunghi dei provider. */
  private val predictorWidth = if (leakyColumns.isEmpty()) 25 else 42

  fun render(): String = buildString {
    header(this)
    coverage(this)
    summary(this)
    section(this, "TABELLE — verita' PANNELLO (primaria)")
    table(this, europa, TruthKind.PANEL, kinds, includeInfo = true)
    table(this, pooled, TruthKind.PANEL, kinds, includeInfo = true)
    for (group in singles) table(this, group, TruthKind.PANEL, kinds, includeInfo = group === sesto)
    section(this, "STALE A ETA' FISSA (3 / 6 / 12 ore) — quanto costa ogni ora di contesto vecchio")
    for (group in listOfNotNull(europa, pooled, sesto)) table(this, group, TruthKind.PANEL, TierKind.STALE_BUCKETS, includeInfo = false)
    if (withModel) reliability(this)
    era5(this)
    notes(this)
  }

  // ------------------------------------------------------------------ intestazione e copertura

  private fun header(out: StringBuilder) {
    val period = result.period
    val title = if (withModel) "REPLAY PER LIVELLO (banco onesto)" else "BASELINE ONESTE (banco onesto)"
    out.appendLine("=== $title - periodo ${period.name.uppercase()} (emissioni ${date(period.firstMillis)}..${date(period.endExclusiveMillis - 1)}) - verita' primaria PANNELLO (${TruthPanel.VERSION}) ===")
    out.appendLine()
    out.appendLine("Generato da `$command`. ${TruthPanel.ATTRIBUTION}.")
    out.appendLine()
    if (notes.isNotEmpty()) {
      for (line in notes) out.appendLine(line)
      out.appendLine()
    }
    out.appendLine("Come si legge")
    out.appendLine("  Emissioni   una ogni 3 h per localita' (t0, dalle 00 UTC del primo giorno); l'emissione vera e' t0 - U(0, 60) min (seme = localita' + t0),")
    out.appendLine("              cosi' l'ancora delle finestre resta t0 (RainWindows, per eccesso) e il telefono vede le 24 h prima dell'emissione, non di t0.")
    out.appendLine("              Il barometro e' sintetizzato dalla pressione di stazione di ERA5 (SamplingProfile.TELEFONO) e pulito una volta per emissione.")
    out.appendLine("  Livelli     FRESH: contesto di eta' U(0, 90 min) · STALE: U(1,5 h, 12 h) (e a eta' fisse 3/6/12 h, a parte) · NONE: niente contesto, climatologia locale nota ·")
    out.appendLine("              NONE_NOCLIMA: niente contesto e niente climatologia locale (solo i tassi di tutte le localita').")
    out.appendLine("  Contesto    best_match stitched 'com'era al fetch' (ref = emissione - eta'): solo l'ultimo slot orario CHIUSO a ref, pioggia dell'ultima ora = quello slot,")
    out.appendLine("              ultime 3 h = 3 slot chiusi, punto di 3 h fa = lo slot 3 h prima; nessun valore di uno slot che si chiude dopo ref.")
    out.appendLine("  Verita'     PANNELLO = mediana di ${TruthPanel.MODELS.joinToString("/")}, quorum = tutti; finestra bagnata se la somma degli slot chiusi e' >= ${RainWindows.WET_THRESHOLD_MM} mm.")
    out.appendLine("              Secondaria ERA5 (sezione a parte): le baseline sono tarate sul pannello e li' sono fuori taratura per costruzione.")
    out.appendLine("  Baseline    tutte fuori campione: costruite sulla verita' del pannello nei due anni che precedono il periodo")
    out.appendLine("              (storia: slot da ${date(period.historyFromMillis)} a ${hour(period.historyUntilMillis)}, gli ultimi gia' definitivi alla prima emissione")
    out.appendLine("              del periodo: finalita' ${TruthPanel.FINALITY_MILLIS / 3_600_000L} h piu' l'ora di anticipo massimo dell'emissione), come le scaricherebbe il telefono.")
    out.appendLine("              climatologia      tasso locale stagione x 6 h solari, ristretto (WindowClimatology); in NONE_NOCLIMA il tasso costante di tutte le localita'.")
    out.appendLine("              persistenza       P(bagnata | classe della pioggia dell'ultimo slot chiuso del contesto e ritardo di quello slot) (LocalBaselines LB2: fasce 1-2 / 3-4 / 5-7 / 8-13 h): FRESH e STALE.")
    out.appendLine("              regola-barometrica P(bagnata | classe della tendenza a 3 h MISURATA dal telefono) (LocalBaselines); in NONE_NOCLIMA sui conteggi di tutte le localita'.")
    out.appendLine("              sempre-0          zero. Riferimento della classifica, non una baseline del gate.")
    out.appendLine("              info:*            i vecchi numeri fissi del banco (clima col futuro, persistenza 0,85/0,08, regola a soglie fisse): solo informativi.")
    if (withModel) {
      out.appendLine("  Modello     ${PredictorNames.MODELLO}: NowcastModel.trained(), verdetto grezzo (niente Platt, niente pavimenti), con il contesto del livello")
      out.appendLine("              (NONE/NONE_NOCLIMA: nessun contesto, le feature mancanti imputate alla media come oggi).")
      TierBench.v2InSampleNote(period)?.let { out.appendLine("              $it") }
    }
    if (leakyColumns.isNotEmpty()) {
      out.appendLine("  Provider    ${leakyColumns.joinToString(" · ")}: PoP oraria massima sugli slot esatti della finestra,")
      out.appendLine("              dalla serie stitched dell'historical-forecast-api. ${LeakyProviderPop.LEAK_LABEL.uppercase()}: ogni ora viene dalla corsa piu' recente,")
      out.appendLine("              emessa dopo il telefono (fino a 6 h dopo per la 3-6h). Solo nelle tabelle FRESH, come riferimento: mai migliore baseline, mai nel gate.")
      out.appendLine("              La colonna MAE e' il numero della vecchia classifica dell'app (errore assoluto medio), qui sulla verita' del pannello.")
    }
    out.appendLine("  Migliore baseline: la migliore per Brier fra climatologia, persistenza e regola-barometrica sugli stessi casi ('*' nelle tabelle).")
    out.appendLine("  BSS         contro la climatologia dello stesso livello e finestra, sugli stessi casi.  Δmigl = Brier - Brier(migliore baseline): negativo = meglio.")
    if (withModel) {
      out.appendLine("  Δ appaiato  Brier(modello) - Brier(migliore baseline) caso per caso, IC95% dal bootstrap a blocchi di giorni (1000 ricampionamenti).")
      out.appendLine("              VINCE = IC tutto sotto zero · vince~ = media sotto zero ma IC che include zero · perde~ / PERDE = speculari. Il gate si decide sulla media.")
    }
    out.appendLine("  Gruppi      ${TierGroups.EUROPA_LABEL} = ${TierGroups.EUROPA.joinToString(", ")} (gate duro); TUTTE = tutte quelle giocate;")
    out.appendLine("              ${TierGroups.WARN_ONLY.joinToString(", ")} = solo avviso.")
    out.appendLine()
  }

  private fun coverage(out: StringBuilder) {
    section(out, "COPERTURA — cosa e' entrato nel replay e cosa no")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %6s %9s %13s %12s %-22s %8s %12s",
        "localita'", "t0", "emissioni", "senza-verita'", "senza-barom.", "senza-ctx F/S/3/6/12", "casi", "ripiego-tend",
      ),
    )
    for (c in result.coverage) {
      val droppedText = listOf(TierKind.FRESH, TierKind.STALE, TierKind.STALE_3H, TierKind.STALE_6H, TierKind.STALE_12H)
        .joinToString("/") { (c.droppedNoContext[it] ?: 0).toString() }
      out.appendLine(
        String.format(
          Locale.ROOT, "  %-18s %6d %9d %13d %12d %-22s %8d %12d",
          c.location, c.anchors, c.issues, c.noTruth, c.noBarometer, droppedText, c.records, c.barometricFallbacks,
        ),
      )
    }
    for ((location, why) in result.skipped) out.appendLine("  NON GIOCATA: $location - $why")
    out.appendLine("  (senza-verita' = nessuna finestra giudicabile ne' dal pannello ne' da ERA5; senza-barom. = meno di 13 h di storia pulita;")
    out.appendLine("   ripiego-tend = casi in cui la tendenza del telefono mancava e la regola barometrica ha detto la climatologia)")
    out.appendLine()
  }

  // ------------------------------------------------------------------ sintesi

  private fun summary(out: StringBuilder) {
    section(out, if (withModel) "SINTESI D1 — il modello contro la migliore baseline, per gruppo e livello (verita' PANNELLO)" else "SINTESI — la migliore baseline per gruppo e livello (verita' PANNELLO)")
    out.append(
      String.format(
        Locale.ROOT, "  %-22s %-12s %-5s %7s  %7s  %-26s %7s  %8s",
        "gruppo", "livello", "fin.", "n", "clima", "migliore baseline", "BSS", "sempre-0",
      ),
    )
    if (withModel) out.append(String.format(Locale.ROOT, " | %7s %9s  %-19s %s", "modello", "Δ", "IC95%", "esito"))
    out.appendLine()
    for (group in summaryGroups) {
      if (group.locations.isEmpty()) continue
      for (kind in kinds) {
        for (w in windows.indices) {
          val cases = stats.cases(group.locations, kind, w, TruthKind.PANEL)
          if (cases.isEmpty()) continue
          val rows = stats.rows(cases, TruthKind.PANEL)
          val clima = stats.byName(rows, PredictorNames.CLIMATOLOGIA) ?: continue
          val best = stats.bestBaseline(rows) ?: continue
          val zero = stats.byName(rows, PredictorNames.SEMPRE_0)
          val label = group.label + if (group.warnOnly) " [avviso]" else ""
          out.append(
            String.format(
              Locale.ROOT, "  %-22s %-12s %-5s %7d  %7s  %-26s %7s  %8s",
              label, kind.label, windows[w].label, clima.n, f4(clima.brier),
              "${best.predictor} ${f4(best.brier)}", sgn3(best.bss), f4(zero?.brier ?: Double.NaN),
            ),
          )
          if (withModel) {
            val paired = stats.pairedAgainstBest(cases, TruthKind.PANEL)
            if (paired == null) {
              out.append(" | (modello assente)")
            } else {
              out.append(
                String.format(
                  Locale.ROOT, " | %7s %9s  %-19s %s",
                  f4(paired.modelBrier), sgn4(paired.delta.mean), interval(paired.delta), verdict(paired.delta),
                ),
              )
            }
          }
          out.appendLine()
        }
      }
    }
    out.appendLine()
  }

  // ------------------------------------------------------------------ tabelle

  private fun table(out: StringBuilder, group: Group, truth: TruthKind, kindList: List<TierKind>, includeInfo: Boolean) {
    if (group.locations.isEmpty()) return
    val suffix = if (group.warnOnly) " [solo avviso]" else ""
    out.appendLine("--- ${group.label}$suffix - verita' ${truth.label}")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-12s %-5s %-${predictorWidth}s %7s %6s %8s %7s %8s %6s %8s",
        "livello", "fin.", "predittore", "n", "base", "Brier", "BSS", "logloss", "MAE", "Δmigl",
      ),
    )
    for (kind in kindList) {
      for (w in windows.indices) {
        val cases = stats.cases(group.locations, kind, w, truth)
        if (cases.isEmpty()) continue
        val rows = stats.rows(cases, truth)
        val best = stats.bestBaseline(rows)
        for (row in rows) {
          if (!includeInfo && row.predictor in PredictorNames.INFO) continue
          val mark = if (best != null && row.predictor == best.predictor) "* " else "  "
          val delta = if (best == null) Double.NaN else row.brier - best.brier
          out.appendLine(
            String.format(
              Locale.ROOT, "  %-12s %-5s %-${predictorWidth}s %7d %6s %8s %7s %8s %6s %8s",
              kind.label, windows[w].label, mark + row.predictor, row.n, f3(row.base), f4(row.brier),
              sgn3(row.bss), f4(row.logLoss), f3(row.mae), sgn4(delta),
            ),
          )
        }
      }
    }
    out.appendLine()
  }

  // ------------------------------------------------------------------ affidabilita'

  private fun reliability(out: StringBuilder) {
    section(out, "AFFIDABILITA' del modello (10 gradini) - verita' PANNELLO: quando dice X%, succede Y% delle volte")
    val modelIndex = result.indexOf(PredictorNames.MODELLO)
    for (group in listOfNotNull(europa, sesto)) {
      for (kind in kinds) {
        for (w in windows.indices) {
          val cases = stats.cases(group.locations, kind, w, TruthKind.PANEL)
          val forecast = stats.forecastCases(cases, modelIndex, TruthKind.PANEL)
          if (forecast.isEmpty()) continue
          out.appendLine("--- ${group.label} - ${kind.label} ${windows[w].label} (n=${forecast.size})")
          out.appendLine(ReliabilityPrinter.format(forecast, indent = "  "))
        }
      }
    }
    out.appendLine()
  }

  // ------------------------------------------------------------------ ERA5

  private fun era5(out: StringBuilder) {
    section(out, "SECONDARIA - verita' ERA5 (solo avviso: le baseline sono tarate sul pannello, piu' asciutto di ERA5)")
    for (group in listOfNotNull(europa, pooled, sesto)) table(out, group, TruthKind.ERA5, kinds, includeInfo = false)
    if (!withModel) return
    out.appendLine("--- Avvisi ERA5: dove il modello perde contro la migliore baseline (Δ > 0) sotto ERA5, per gruppo e localita'")
    var warnings = 0
    for (group in summaryGroups) {
      if (group.locations.isEmpty()) continue
      for (kind in kinds) {
        for (w in windows.indices) {
          val cases = stats.cases(group.locations, kind, w, TruthKind.ERA5)
          if (cases.isEmpty()) continue
          val rows = stats.rows(cases, TruthKind.ERA5)
          val best = stats.bestBaseline(rows) ?: continue
          val model = stats.byName(rows, PredictorNames.MODELLO) ?: continue
          val delta = model.brier - best.brier
          if (delta > 0) {
            warnings++
            out.appendLine(
              String.format(
                Locale.ROOT, "  AVVISO %-22s %-12s %-5s modello %s contro %s %s (Δ %s)",
                group.label + if (group.warnOnly) " [avviso]" else "", kind.label, windows[w].label,
                f4(model.brier), best.predictor, f4(best.brier), sgn4(delta),
              ),
            )
          }
        }
      }
    }
    if (warnings == 0) out.appendLine("  nessuno: sotto ERA5 il modello non perde da nessuna parte.")
    out.appendLine()
  }

  // ------------------------------------------------------------------ note

  private fun notes(out: StringBuilder) {
    section(out, "NOTE")
    val fallbacks = result.coverage.sumOf { it.barometricFallbacks }
    out.appendLine("  - best_match coincide con ERA5 a singapore e buenos-aires (vedi label-audit): li' il contesto e' la pioggia vera, la persistenza e' piu' informata")
    out.appendLine("    di quanto un telefono potra' mai essere, e sotto ERA5 il suo confronto e' circolare. Il pannello resta la verita' primaria.")
    out.appendLine("  - Il pannello ha buchi fuori dall'Europa prima del 2023-12-27 (Meteo-France): a singapore, tokyo, denver e buenos-aires la storia delle baseline parte da li'")
    out.appendLine("    (VALIDATION: otto mesi invece di ventuno; TEST: venti mesi invece di ventiquattro).")
    out.appendLine("  - La persistenza impara 'piove adesso' con lo slot chiuso all'emissione oraria; applicata a FRESH (contesto vecchio fino a 90 min, slot chiuso fino a 2,5 h prima) e a STALE")
    out.appendLine("    e' una persistenza ritardata: il ritardo e' dichiarato e misurato qui, non nascosto.")
    out.appendLine("  - La barometrica e' tarata sulla tendenza del provider (Meteo-France, serie stitched) e applicata alla tendenza del telefono (filtro di Kalman su ERA5 + rumore):")
    out.appendLine("    la differenza fra le due e' parte del gioco. In $fallbacks casi la tendenza mancava e la regola ha detto la climatologia.")
    out.appendLine("  - ERA5 e' la rianalisi da cui si sintetizza il barometro: le sue finestre di 4D-Var di 12 ore lasciano che osservazioni successive plasmino la pressione a t,")
    out.appendLine("    quindi a banco l'abilita' del solo barometro e' sovrastimata (critica B1 del design). Il banco sul telefono vero (phone-archive-replay) verra' dopo.")
    out.appendLine("  - Nessun Platt, nessun pavimento, nessun analogo: il modello e' il verdetto grezzo, come dice il nome del predittore.")
    out.appendLine("  - I numeri non sono confrontabili con label-audit al centesimo: li' si emette ogni ora piena con persistenza a ritardo zero, qui ogni 3 h con emissione a minuto casuale.")
    out.appendLine()
  }

  // ------------------------------------------------------------------ formato

  private fun section(out: StringBuilder, title: String) {
    out.appendLine("--- $title")
    out.appendLine()
  }

  private fun verdict(delta: BootstrapSummary): String = when {
    delta.high < 0 -> "VINCE"
    delta.mean < 0 -> "vince~"
    delta.low > 0 -> "PERDE"
    else -> "perde~"
  }

  private fun interval(summary: BootstrapSummary): String = "[${sgn4(summary.low)},${sgn4(summary.high)}]"

  private fun date(millis: Long): String = Instant.ofEpochMilli(millis).toString().substring(0, 10)

  private fun hour(millis: Long): String = Instant.ofEpochMilli(millis).toString().substring(0, 16) + "Z"

  private fun f3(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%.3f", value)

  private fun f4(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%.4f", value)

  private fun sgn3(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%+.3f", value)

  private fun sgn4(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%+.4f", value)
}
