package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3
import dev.pampa.fluidweather.testbench.gate.GateSubjects
import dev.pampa.fluidweather.testbench.replay.TierBench
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.tiers.ScenarioMode
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * `v3-lolo`: il v3 su un posto che **non ha mai visto**. Diagnosi, non gate.
 *
 * Il gate giudica il v3 sulle stesse dieci localita' su cui e' addestrato (un anno dopo, ma gli stessi posti).
 * Un telefono vero sta quasi sempre in un posto nuovo. Il sospetto: nei livelli senza tabelle locali
 * (NONE_NOCLIMA) il livello del mare assoluto (feature 39) fa da *impronta del posto* — Reykjavik ha la sua
 * pressione media, Milano la sua — e il modello impara "il clima di quel posto" attraverso di lei. Sul gate
 * sembra informazione locale; su un posto nuovo non c'e'.
 *
 * Per ogni (livello, finestra) con gli iperparametri spediti (ancora, lambda, peso fuori Europa, colonne) e
 * un solo fit (senza bag): righe TUNE (TRAIN estratte, VALIDATION con la semantica del gate, orarie), poi per
 * ogni localita' europea L il margine su VALIDATION di L contro la migliore baseline di L:
 * - `tutti`: addestrato anche su L (quello che il gate vede);
 * - `senza L`: addestrato senza le righe di L (leave-one-location-out);
 * - `senza L, senza 39`: lo stesso togliendo il livello del mare.
 * Le medie e le deviazioni sono quelle di tutte le righe (nessuna etichetta di L vi entra).
 */
object V3LoloCommand {

  fun run(dataRoot: File = File("data"), log: (String) -> Unit = ::println): String {
    val started = System.nanoTime()
    val loaded = TierBench.loadInputs(dataRoot, log)
    val slices = JackknifeSlices(JackknifePlan.real(), loaded.inputs)
    val builder = V3RowBuilder(loaded.inputs, slices, log = log)
    val hour = 3_600_000L
    val train = builder.build(V3RowSpec(TierPeriods.TRAIN, hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    val validation = builder.build(V3RowSpec(TierPeriods.VALIDATION, hour, ScenarioMode.EVALUATION, V3Stage.TUNE))
    val compiled = GateSubjects.compiledTables()
    val europe = TierGroups.EUROPA.filter { it in train.locations }

    class Line(val tier: ContextTier, val w: Int, val location: String, val n: Int, val best: Double, val all: Double, val lolo: Double, val loloNo39: Double)

    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 10))
    val lines = ArrayList<Line>()
    try {
      for (tier in ContextTier.entries) {
        val std = TierStandardization.fit(train, tier)
        for (w in RainWindows.ALL.indices) {
          val key = "${tier.name}.${RainWindows.ALL[w].label}.logistic"
          val choice = LogisticChoice.decode(TrainedNowcastV3.HYPERPARAMETERS.getValue(key))
          val columns = compiled.getValue(tier).used[w].filter { it !in std.degenerate }.toIntArray()
          val noLevel = columns.filter { it != FeatureExtractorV3.SEA_LEVEL }.toIntArray()
          val trainFull = CellDesign.of(train, tier, w, columns, std, withHalves = false)
          val trainNoLevel = CellDesign.of(train, tier, w, noLevel, std, withHalves = false)
          val valFull = CellDesign.of(validation, tier, w, columns, std, withHalves = false)
          val valNoLevel = CellDesign.of(validation, tier, w, noLevel, std, withHalves = false)
          val anchor = choice.anchor?.takeIf { it !in std.degenerate }

          fun fit(design: CellDesign, exclude: Int?): DoubleArray {
            val weights = design.weights(choice.nonEuropeWeight)
            if (exclude != null) for (i in 0 until design.n) if (design.location[i].toInt() == exclude) weights[i] = 0.0
            return LogisticV3.fit(design, design.offsets(anchor), weights, choice.lambda).beta
          }

          fun brier(design: CellDesign, rows: IntArray, beta: DoubleArray): Double {
            val offsets = design.offsets(anchor)
            var sum = 0.0
            for (i in rows) {
              val p = LogisticV3.sigmoid(LogisticV3.score(design, i, beta, offsets[i]))
              sum += (p - design.y[i]) * (p - design.y[i])
            }
            return sum / rows.size
          }

          fun bestBaseline(rows: IntArray): Double = listOfNotNull(
            FeatureExtractorV3.CLIMATOLOGY + w,
            (FeatureExtractorV3.PERSISTENCE + w).takeIf { tier.hasContext },
            FeatureExtractorV3.BAROMETRIC_RULE + w,
          ).minOf { column ->
            var sum = 0.0
            for (i in rows) {
              val p = LogisticV3.sigmoid(valFull.feature(i, column).toDouble())
              sum += (p - valFull.y[i]) * (p - valFull.y[i])
            }
            sum / rows.size
          }

          val allBeta = pool.submit(Callable { fit(trainFull, null) })
          val perLocation = europe.map { name ->
            val index = train.locations.indexOf(name)
            Triple(name, pool.submit(Callable { fit(trainFull, index) }), pool.submit(Callable { fit(trainNoLevel, index) }))
          }
          for ((name, lolo, loloNo39) in perLocation) {
            val index = validation.locations.indexOf(name)
            val rows = (0 until valFull.n).filter { valFull.location[it].toInt() == index }.toIntArray()
            if (rows.isEmpty()) continue
            val best = bestBaseline(rows)
            lines += Line(
              tier, w, name, rows.size, best,
              brier(valFull, rows, allBeta.get()) - best,
              brier(valFull, rows, lolo.get()) - best,
              brier(valNoLevel, rows, loloNo39.get()) - best,
            )
          }
          log("  $key fatto")
        }
      }
    } finally {
      pool.shutdown()
    }

    // Il livello del mare senza quota di riferimento: sul telefono e' NaN ([FeatureExtractorV3.extract] con
    // referenceAltitudeKnown = false), nell'addestramento e nel gate non lo e' mai. Il candidato (mai addestrato su
    // VALIDATION) con la feature 39 com'e' e con la feature 39 NaN, contro la migliore baseline.
    val candidate = CandidateArtefact.read(TrainV3Command.CANDIDATE_DIR).toModel(ModelFamily.LOGISTICA)
    val levelLines = ArrayList<String>()
    for (tier in ContextTier.entries) {
      for (w in RainWindows.ALL.indices) {
        for (name in europe) {
          val index = validation.locations.indexOf(name)
          var n = 0
          var withLevel = 0.0
          var withoutLevel = 0.0
          val baselines = DoubleArray(3)
          for (row in 0 until validation.size) {
            if (validation.tier[row].toInt() != tier.ordinal || validation.locationIndex[row].toInt() != index) continue
            val label = validation.label(row, w)
            if (label < 0) continue
            val x = validation.featureRow(row)
            val p = candidate.verdict(tier, x).windows[w].probability
            x[FeatureExtractorV3.SEA_LEVEL] = Double.NaN
            val q = candidate.verdict(tier, x).windows[w].probability
            n++
            withLevel += (p - label) * (p - label)
            withoutLevel += (q - label) * (q - label)
            for ((k, column) in listOf(FeatureExtractorV3.CLIMATOLOGY, FeatureExtractorV3.PERSISTENCE, FeatureExtractorV3.BAROMETRIC_RULE).withIndex()) {
              val b = LogisticV3.sigmoid(validation.feature(row, column + w).toDouble())
              baselines[k] += if (b.isNaN()) Double.POSITIVE_INFINITY else (b - label) * (b - label)
            }
          }
          if (n == 0) continue
          val best = baselines.filter { it.isFinite() }.minOf { it } / n
          levelLines += String.format(
            Locale.ROOT, "  %-13s %-5s %-18s %6d %8.4f %+10.4f %+10.4f%s",
            tier.name, RainWindows.ALL[w].label, name, n, best, withLevel / n - best, withoutLevel / n - best,
            if (withoutLevel / n - best >= 0) "  X" else "",
          )
        }
      }
    }

    return buildString {
      appendLine("=== v3-lolo — il v3 su una localita' mai vista (diagnosi su VALIDATION, non un gate) ===")
      appendLine()
      appendLine("Generato da `v3-lolo`. ${TruthPanel.ATTRIBUTION}.")
      appendLine("Iperparametri e colonne spediti (${TrainedNowcastV3.VERSION}), un fit senza bag, righe TUNE orarie; VALIDATION con la semantica del gate.")
      appendLine("margine = Brier(modello) - Brier(migliore baseline della localita'), negativo = batte la baseline.")
      appendLine(String.format(Locale.ROOT, "Tempo: %.0f s.", (System.nanoTime() - started) / 1e9))
      appendLine()
      appendLine(String.format(Locale.ROOT, "  %-13s %-5s %-18s %6s %8s %9s %9s %11s", "livello", "fin.", "localita'", "n", "baseline", "tutti", "senza L", "senza L,39"))
      for (line in lines) {
        appendLine(
          String.format(
            Locale.ROOT, "  %-13s %-5s %-18s %6d %8.4f %+9.4f %+9.4f %+11.4f%s",
            line.tier.name, RainWindows.ALL[line.w].label, line.location, line.n, line.best, line.all, line.lolo, line.loloNo39,
            if (line.lolo >= 0) "  X" else "",
          ),
        )
      }
      appendLine()
      appendLine("Riassunto per livello (media dei margini sulle localita' europee; celle 'X' = senza L non batte la baseline):")
      for (tier in ContextTier.entries) {
        val sub = lines.filter { it.tier == tier }
        appendLine(
          String.format(
            Locale.ROOT, "  %-13s tutti %+.4f   senza L %+.4f   senza L,39 %+.4f   X senza L: %d/%d   X senza L,39: %d/%d",
            tier.name, sub.map { it.all }.average(), sub.map { it.lolo }.average(), sub.map { it.loloNo39 }.average(),
            sub.count { it.lolo >= 0 }, sub.size, sub.count { it.loloNo39 >= 0 }, sub.size,
          ),
        )
      }
      appendLine()
      appendLine("--- Livello del mare assente (quota di riferimento ignota sul telefono): candidato logistico, margine con la 39 e con la 39 NaN")
      appendLine(String.format(Locale.ROOT, "  %-13s %-5s %-18s %6s %8s %10s %10s", "livello", "fin.", "localita'", "n", "baseline", "con 39", "39 NaN"))
      levelLines.forEach { appendLine(it) }
    }
  }
}
