package dev.pampa.fluidweather.testbench.gate

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TierReplayResult
import dev.pampa.fluidweather.testbench.replay.TierStats
import dev.pampa.fluidweather.testbench.replay.TruthKind
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import java.util.Locale

/**
 * Le sezioni in piu' del rapporto del gate per il v3: i TARGET dichiarati per livello x finestra (Sesto,
 * EUROPA e, per informazione, TUTTE), il paracadute logistico quando parlano gli alberi, e il confronto con
 * il v2 sugli stessi casi.
 */
object V3GateSections {

  const val TARGET_BRIER: Double = 0.10

  fun render(result: TierReplayResult, subject: V3Subject, period: TierPeriod): String = buildString {
    val stats = TierStats(result)
    val played = result.locations
    val europe = TierGroups.EUROPA.filter { it in played }
    val groups = listOf(TierGroups.SESTO to listOf(TierGroups.SESTO).filter { it in played }, "EUROPA (${europe.size})" to europe, "TUTTE (${played.size})" to played)
    val column = subject.column
    val v2 = result.indexOf(V3Subject.V2_COLUMN)
    val rawIndex = result.indexOf(subject.rawColumn)
    val fallbackIndex = result.indexOf(subject.fallbackColumn)

    appendLine("--- TARGET per livello x finestra — PANNELLO (primaria) ed ERA5 (secondaria); TUTTE solo per informazione")
    appendLine("  Brier '< 0.10 da solo' e' il traguardo dell'utente; BSS contro la climatologia locale (in NONE_NOCLIMA il tasso costante di tutti i posti);")
    appendLine("  delta = Brier(v3 +pavimenti) - Brier(migliore baseline), IC95% appaiato a blocchi di giorni. Le baseline sono tarate sul pannello:")
    appendLine("  sotto ERA5 il confronto e' un controllo, non un verdetto.")
    appendLine(
      String.format(
        Locale.ROOT, "  %-18s %-12s %-5s %6s %7s %-4s %7s  %-18s %7s %8s %-19s | %7s %7s%s | %7s %-18s %8s",
        "gruppo", "livello", "fin.", "n", "v3", "<.10", "BSS", "migliore", "Brier", "delta", "IC95%", "v2", "grezzo",
        if (fallbackIndex >= 0) String.format(Locale.ROOT, " %7s", "parac.") else "",
        "ERA5 v3", "migliore ERA5", "delta",
      ),
    )
    for ((label, locations) in groups) {
      if (locations.isEmpty()) continue
      for (kind in TierKind.PRIMARY) {
        for ((w, window) in RainWindows.ALL.withIndex()) {
          val cases = stats.cases(locations, kind, w, TruthKind.PANEL).filter { !it.probabilities[result.indexOf(column)].isNaN() }
          val rows = stats.rows(cases, TruthKind.PANEL)
          val own = stats.byName(rows, column)
          val best = stats.bestBaseline(rows)
          val delta = best?.let { stats.paired(cases, TruthKind.PANEL, result.indexOf(column), result.indexOf(it.predictor)) }
          val v2Brier = if (v2 >= 0) stats.row(cases, v2, TruthKind.PANEL)?.brier ?: Double.NaN else Double.NaN
          val rawBrier = if (rawIndex >= 0) stats.row(cases, rawIndex, TruthKind.PANEL)?.brier ?: Double.NaN else Double.NaN
          val fallback = if (fallbackIndex >= 0) String.format(Locale.ROOT, " %7s", Fmt.f4(stats.row(cases, fallbackIndex, TruthKind.PANEL)?.brier ?: Double.NaN)) else ""
          val era5Cases = stats.cases(locations, kind, w, TruthKind.ERA5).filter { !it.probabilities[result.indexOf(column)].isNaN() }
          val era5Rows = stats.rows(era5Cases, TruthKind.ERA5)
          val era5Own = stats.byName(era5Rows, column)
          val era5Best = stats.bestBaseline(era5Rows)
          appendLine(
            String.format(
              Locale.ROOT, "  %-18s %-12s %-5s %6d %7s %-4s %7s  %-18s %7s %8s %-19s | %7s %7s%s | %7s %-18s %8s",
              label.take(18), kind.label, window.label, own?.n ?: 0, Fmt.f4(own?.brier ?: Double.NaN),
              if ((own?.brier ?: 1.0) < TARGET_BRIER) "si" else "no", Fmt.sgn3(own?.bss ?: Double.NaN),
              best?.predictor ?: "-", Fmt.f4(best?.brier ?: Double.NaN), Fmt.sgn4((own?.brier ?: Double.NaN) - (best?.brier ?: Double.NaN)),
              Fmt.interval(delta), Fmt.f4(v2Brier), Fmt.f4(rawBrier), fallback,
              Fmt.f4(era5Own?.brier ?: Double.NaN), era5Best?.predictor ?: "-",
              Fmt.sgn4((era5Own?.brier ?: Double.NaN) - (era5Best?.brier ?: Double.NaN)),
            ),
          )
        }
      }
      appendLine()
    }
    appendLine("  v2 = la pipeline del gate di oggi (v2 grezzo -> motore, pavimenti di oggi) sugli stessi casi; grezzo = il v3 senza pavimenti.")
    appendLine()

    if (fallbackIndex >= 0) {
      val fallbackGate = IndependenceGate.evaluate(result, subject.fallbackColumn, subject.tag)
      appendLine("--- PARACADUTE LOGISTICO (informativo, stessa esecuzione): '${subject.fallbackColumn}'")
      appendLine("  Se gli alberi non si caricano sul telefono parla questa colonna. Celle dure che non passano: ${fallbackGate.failures.size}.")
      for (cell in fallbackGate.failures) {
        appendLine(String.format(Locale.ROOT, "    %-22s %-12s %-5s %s contro %s %s: margine %s", cell.group, cell.kind.label, cell.window,
          Fmt.f4(cell.pipelineBrier), cell.bestBaseline ?: "-", Fmt.f4(cell.bestBrier), Fmt.sgn4(cell.margin)))
      }
      appendLine()
    } else if (subject.model.activeFamily == ModelFamily.LOGISTICA) {
      appendLine("--- Famiglia LOGISTICA: il modello giudicato e' gia' il paracadute.")
      appendLine()
    }
    if (period.firstMillis >= dev.pampa.fluidweather.testbench.tiers.TierPeriods.TEST.firstMillis) {
      appendLine("Nota: la sensibilita' al barometro causale si misura solo su VALIDATION (replay-tiers --barometro-causale).")
      appendLine()
    }
    // Il nome della colonna di climatologia serve solo a ricordare da dove viene il BSS.
    check(PredictorNames.CLIMATOLOGIA in result.predictorNames)
  }
}
