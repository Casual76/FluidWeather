package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.tiers.TierKind
import java.util.Locale

/**
 * La sezione "v3 accanto agli altri" che `replay-tiers --model v3` e `phone-pipeline --model v3` aggiungono
 * ai loro rapporti: Brier sul PANNELLO per gruppo (EUROPA, Sesto, TUTTE e le sei localita' europee) x livello
 * x finestra delle colonne date, con la migliore baseline, la differenza appaiata della prima colonna contro di
 * lei, e — per capire gli sbagli di taratura — il tasso bagnato dei casi accanto alla probabilita' media che la
 * prima colonna ha dato.
 */
object V3Report {

  fun section(result: TierReplayResult, title: String, columns: List<Pair<String, String>>, truth: TruthKind = TruthKind.PANEL): String = buildString {
    val stats = TierStats(result)
    val played = result.locations
    val europe = TierGroups.EUROPA.filter { it in played }
    val groups = listOf("EUROPA (${europe.size})" to europe, "TUTTE (${played.size})" to played) + europe.map { it to listOf(it) }
    val present = columns.filter { result.indexOf(it.first) >= 0 }
    appendLine("--- $title — Brier, verita' ${truth.name}")
    append(String.format(Locale.ROOT, "  %-18s %-12s %-5s %6s %6s %7s  %-19s %7s", "gruppo", "livello", "fin.", "n", "base", "media p", "migliore baseline", "Brier"))
    for ((_, short) in present) append(String.format(Locale.ROOT, " %9s", short.take(9)))
    appendLine(String.format(Locale.ROOT, "  %9s %-19s", "delta(1a)", "IC95%"))
    for ((label, locations) in groups) {
      if (locations.isEmpty()) continue
      for (kind in TierKind.PRIMARY) {
        for ((w, window) in RainWindows.ALL.withIndex()) {
          val first = result.indexOf(present.first().first)
          val cases = stats.cases(locations, kind, w, truth).filter { !it.probabilities[first].isNaN() }
          val rows = stats.rows(cases, truth)
          val best = stats.bestBaseline(rows)
          val meanP = if (cases.isEmpty()) Double.NaN else cases.sumOf { it.probabilities[first] } / cases.size
          val base = stats.byName(rows, present.first().first)?.base ?: Double.NaN
          append(
            String.format(
              Locale.ROOT, "  %-18s %-12s %-5s %6d %6s %7s  %-19s %7s",
              label.take(18), kind.label, window.label, cases.size, Fmt.f3(base), Fmt.f3(meanP), best?.predictor ?: "-", Fmt.f4(best?.brier ?: Double.NaN),
            ),
          )
          for ((column, _) in present) append(String.format(Locale.ROOT, " %9s", Fmt.f4(stats.byName(rows, column)?.brier ?: Double.NaN)))
          val delta = best?.let { stats.paired(cases, truth, first, result.indexOf(it.predictor)) }
          appendLine(String.format(Locale.ROOT, "  %9s %-19s", Fmt.sgn4(delta?.mean ?: Double.NaN), Fmt.interval(delta)))
        }
      }
      appendLine()
    }
  }
}
