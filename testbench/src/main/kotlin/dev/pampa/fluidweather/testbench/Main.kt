package dev.pampa.fluidweather.testbench

import dev.pampa.fluidweather.testbench.data.BenchLocations
import dev.pampa.fluidweather.testbench.data.OpenMeteoFetcher
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.metrics.Contingency
import dev.pampa.fluidweather.testbench.metrics.Probabilistic
import dev.pampa.fluidweather.testbench.metrics.Verification
import dev.pampa.fluidweather.testbench.replay.Replayer
import dev.pampa.fluidweather.testbench.replay.SamplingProfile
import dev.pampa.fluidweather.testbench.replay.TaggedVerification
import dev.pampa.fluidweather.testbench.stages.StageBenches
import dev.pampa.fluidweather.testbench.train.TrainCommand
import java.io.File
import java.util.Locale

/**
 * Il banco di prova da riga di comando:
 *
 *   gradlew :testbench:run --args="fetch"     scarica gli archivi storici (10 localita', 2 anni)
 *   gradlew :testbench:run --args="replay"    tabellone POD/FAR/CSI/Brier per predittore/finestra
 *   gradlew :testbench:run --args="stages"    metriche per stadio: riduzione, marea, screening, tendenza
 *   gradlew :testbench:run --args="all"       tutte e tre, in fila
 *
 * Dati meteo di Open-Meteo.com (CC BY 4.0), uso non commerciale.
 */
fun main(args: Array<String>) {
  when (args.firstOrNull() ?: "all") {
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
    else -> println("comandi: fetch | replay | replay-ideale | stages | train | replay-oos | all")
  }
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
