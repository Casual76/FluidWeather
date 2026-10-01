package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.replay.TierBench
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TruthKind
import dev.pampa.fluidweather.testbench.replay.TierReplayer
import dev.pampa.fluidweather.testbench.tiers.ScenarioMode
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import java.io.File
import java.util.Locale

/**
 * `v3-rows <tune|refit> [--step-ore N]`: costruisce le righe di addestramento del v3 (TRAIN e VALIDATION
 * a un'ora di passo) e ne scrive un rapporto — quante, quante bagnate, quanto hanno estratto a caso gli
 * scenari, quali colonne sono costanti, quanto ci e' voluto. E' il banco di prova del costruttore (non
 * addestra niente): l'addestratore (`train-v3`) usera' le stesse righe.
 *
 * - **tune**: TRAIN in modalita' addestramento (le estrazioni casuali degli scenari), VALIDATION in
 *   modalita' valutazione (la semantica del gate: il telefono come e' quando tutto e' arrivato).
 * - **refit**: TRAIN e VALIDATION entrambe in modalita' addestramento, con le tabelle della fase di
 *   riaddestramento; TEST non si tocca mai.
 */
object V3RowsCommand {

  fun run(
    stage: V3Stage,
    stepHours: Int = 1,
    dataRoot: File = File("data"),
    log: (String) -> Unit = ::println,
  ): String {
    val started = System.nanoTime()
    val loaded = TierBench.loadInputs(dataRoot, log)
    if (loaded.inputs.isEmpty()) return "v3-rows: nessuna localita' con i dati completi in ${dataRoot.path}"
    val slices = JackknifeSlices(JackknifePlan.real(), loaded.inputs)
    val builder = V3RowBuilder(loaded.inputs, slices, log = log)
    val step = stepHours * 3_600_000L

    log("costruisco le righe di TRAIN (${stage.name}, passo $stepHours h, ${TierReplayer.defaultThreads()} thread)...")
    val train = builder.build(V3RowSpec(TierPeriods.TRAIN, step, ScenarioMode.TRAINING, stage))
    val trainStats = builder.lastStats
    val validationMode = if (stage == V3Stage.TUNE) ScenarioMode.EVALUATION else ScenarioMode.TRAINING
    log("costruisco le righe di VALIDATION (${validationMode.name})...")
    val validation = builder.build(V3RowSpec(TierPeriods.VALIDATION, step, validationMode, stage))
    val validationStats = builder.lastStats
    val seconds = (System.nanoTime() - started) / 1e9
    val heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)

    return buildString {
      appendLine("=== v3-rows ${stage.name.lowercase()} — righe di addestramento del v3 ===")
      appendLine()
      appendLine("Generato da `v3-rows ${stage.name.lowercase()} --step-ore $stepHours`. Dati meteo di Open-Meteo.com (CC BY 4.0).")
      appendLine(String.format(Locale.ROOT, "Tempo: %.0f s, heap in uso a fine giro: %d MB, %d colonne di feature.", seconds, heapMb, FeatureExtractorV3.COUNT))
      appendLine()
      appendLine("Tabelle per anno (fase ${stage.name}), slot da cui nascono (fine dello slot, UTC):")
      val plan = slices.plan
      for (region in TableRegion.entries) {
        val ranges = plan.ranges(stage, region)
        appendLine("  ${region.name.padEnd(5)} ${if (ranges.isEmpty()) "(assente in questa fase)" else ranges.joinToString(" + ") { "${Fmt.date(it.first)}..${Fmt.date(it.last)}" }}")
      }
      appendLine("  Le righe di un anno non leggono mai tabelle che contengono l'anno (margine ${plan.bufferMillis / 3_600_000} h); TEST non entra.")
      appendLine()
      section(this, "TRAIN ${TierPeriods.TRAIN}", ScenarioMode.TRAINING, train, trainStats)
      section(this, "VALIDATION ${TierPeriods.VALIDATION}", validationMode, validation, validationStats)
      featureSummary(this, "TRAIN", train)
      if (validationMode == ScenarioMode.EVALUATION) gateParity(this, validation)
    }
  }

  /**
   * Parita' con il gate: le tre colonne di baseline delle righe di VALIDATION (modalita' valutazione) a
   * passo di tre ore sono le **stesse** avversarie del gate. Il Brier di sigma(colonna) su EUROPA e su Sesto
   * deve coincidere con quello delle baseline in `baselines-validation.txt` (a meno del ritaglio del logit a
   * 1e-4, che qui non morde): e' la prova che il modello puo' riprodurre ogni baseline.
   */
  private fun gateParity(out: StringBuilder, rows: V3Rows) {
    out.appendLine("--- PARITA' CON IL GATE: Brier delle colonne di baseline su VALIDATION, passo di 3 h (da confrontare con baselines-validation.txt)")
    val step = 3 * 3_600_000L
    val first = TierPeriods.VALIDATION.firstMillis
    val groups = listOf("EUROPA (6)" to TierGroups.EUROPA.toSet(), "sesto-fiorentino" to setOf(TierGroups.SESTO))
    out.appendLine(String.format(Locale.ROOT, "  %-18s %-13s %-5s %7s %11s %11s %13s", "gruppo", "livello", "fin.", "n", "clima", "persistenza", "regola-barom."))
    for ((label, members) in groups) {
      for (tier in ContextTier.entries) {
        for (w in 0 until 3) {
          var n = 0
          val sums = DoubleArray(3)
          val persistenceAvailable = tier.hasContext
          for (row in 0 until rows.size) {
            if (rows.tierOf(row) != tier || rows.locationOf(row) !in members) continue
            if ((rows.t0[row] - first) % step != 0L) continue
            val y = rows.label(row, w, TruthKind.PANEL)
            if (y < 0) continue
            n++
            val columns = intArrayOf(FeatureExtractorV3.CLIMATOLOGY + w, FeatureExtractorV3.PERSISTENCE + w, FeatureExtractorV3.BAROMETRIC_RULE + w)
            for ((k, column) in columns.withIndex()) {
              val value = rows.feature(row, column).toDouble()
              if (value.isNaN()) continue
              val p = 1.0 / (1.0 + Math.exp(-value))
              sums[k] += (p - y) * (p - y)
            }
          }
          fun cell(k: Int) = if (n == 0 || (k == 1 && !persistenceAvailable)) "-" else Fmt.f4(sums[k] / n)
          out.appendLine(
            String.format(
              Locale.ROOT, "  %-18s %-13s %-5s %7d %11s %11s %13s",
              label, tier.name, RainWindows.ALL[w].label, n, cell(0), cell(1), cell(2),
            ),
          )
        }
      }
    }
    out.appendLine("  (In NONE_NOCLIMA la climatologia e' il tasso costante di tutte le localita'; la regola e' la stessa sui conteggi sommati.)")
    out.appendLine()
  }

  /** Per livello e colonna: quota di NaN, media, deviazione standard, minimo e massimo sulle righe di TRAIN. */
  private fun featureSummary(out: StringBuilder, title: String, rows: V3Rows) {
    out.appendLine("--- FEATURE DI $title per livello: NaN, media, sd, min, max (le colonne costanti e' il rapporto sopra a dirle)")
    for (tier in ContextTier.entries) {
      out.appendLine("  ${tier.name}")
      out.appendLine(String.format(Locale.ROOT, "    %-3s %-26s %7s %10s %10s %10s %10s", "idx", "feature", "NaN", "media", "sd", "min", "max"))
      for (column in 0 until FeatureExtractorV3.COUNT) {
        var count = 0
        var nan = 0
        var sum = 0.0
        var sumSq = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (row in 0 until rows.size) {
          if (rows.tierOf(row) != tier) continue
          count++
          val value = rows.feature(row, column).toDouble()
          if (value.isNaN()) {
            nan++
            continue
          }
          sum += value
          sumSq += value * value
          if (value < min) min = value
          if (value > max) max = value
        }
        val n = count - nan
        val mean = if (n == 0) Double.NaN else sum / n
        val sd = if (n == 0) Double.NaN else Math.sqrt(maxOf(0.0, sumSq / n - mean * mean))
        out.appendLine(
          String.format(
            Locale.ROOT, "    %-3d %-26s %6.1f%% %10s %10s %10s %10s",
            column, FeatureExtractorV3.names[column], if (count == 0) 0.0 else 100.0 * nan / count,
            num(mean), num(sd), num(if (n == 0) Double.NaN else min), num(if (n == 0) Double.NaN else max),
          ),
        )
      }
    }
    out.appendLine()
  }

  private fun num(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%.4f", value)

  private fun section(out: StringBuilder, title: String, mode: ScenarioMode, rows: V3Rows, stats: V3BuildStats) {
    out.appendLine("--- $title (scenario: ${mode.name})")
    out.appendLine("  emissioni: ${stats.anchors}; senza etichetta del pannello: ${stats.noTruth}; senza barometro: ${stats.noBarometer}; finestre tagliate dal limite di fase: ${stats.labelsCutByPhase}")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-13s %9s %11s %8s   %-24s %-24s %-24s %s",
        "livello", "righe", "senza ctx", "senza tab.", "0-1h (giudicate, bagn.)", "1-3h", "3-6h", "estratto: storia / tabelle locali",
      ),
    )
    for (tier in ContextTier.entries) {
      val n = rows.count(tier)
      val cells = (0 until 3).map { w ->
        val (judged, wet) = rows.labelCounts(tier, w)
        String.format(Locale.ROOT, "%d, %d (%.3f)", judged, wet, if (judged == 0) Double.NaN else wet.toDouble() / judged)
      }
      val history = stats.historyKnownByTier.getValue(tier)
      val local = stats.localPriorsByTier.getValue(tier)
      out.appendLine(
        String.format(
          Locale.ROOT, "  %-13s %9d %11d %8d   %-24s %-24s %-24s %.3f / %.3f",
          tier.name, n, stats.droppedNoContext.getValue(tier), stats.droppedNoTables.getValue(tier),
          cells[0], cells[1], cells[2],
          if (n == 0) Double.NaN else history.toDouble() / n,
          if (n == 0) Double.NaN else local.toDouble() / n,
        ),
      )
    }
    out.appendLine()
    out.appendLine("  Colonne costanti per livello (l'addestratore le toglie):")
    for (tier in ContextTier.entries) {
      if (rows.count(tier) == 0) continue
      val constant = rows.constantColumns(tier)
      out.appendLine("    ${tier.name.padEnd(13)} ${if (constant.isEmpty()) "nessuna" else constant.joinToString(", ") { FeatureExtractorV3.names[it] }}")
    }
    out.appendLine()
    val locations = rows.locations
    out.appendLine("  Righe per localita' (tutti i livelli): " + locations.withIndex().joinToString(", ") { (i, name) ->
      "$name ${(0 until rows.size).count { rows.locationIndex[it].toInt() == i }}"
    })
    out.appendLine("  Finestre: ${RainWindows.ALL.joinToString(", ") { it.label }}. Etichette del pannello (${dev.pampa.fluidweather.nowcast.truth.TruthPanel.VERSION}); ERA5 viaggia nelle righe per il rapporto.")
    out.appendLine()
  }
}
