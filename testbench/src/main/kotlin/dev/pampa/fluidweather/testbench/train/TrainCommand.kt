package dev.pampa.fluidweather.testbench.train

import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.events.EventWindow
import dev.pampa.fluidweather.testbench.metrics.Contingency
import dev.pampa.fluidweather.testbench.metrics.Probabilistic
import dev.pampa.fluidweather.testbench.metrics.Verification
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * L'addestramento offline promesso dal piano: tabellare, su un portatile, in minuti. Split
 * TEMPORALE — si impara dal passato e si giudica sul futuro (dal 2025-01-01 in poi), mai il
 * contrario — e confronto logistica-vs-boosting sulle stesse feature. Cio' che si spedisce e'
 * la tabella leggibile della logistica, rigenerata in
 * nowcast/src/main/kotlin/.../verdict/TrainedNowcastV1.kt e committata.
 */
object TrainCommand {

  /** 2025-01-01T00:00:00Z: 16 mesi per imparare, 8 per essere giudicati. */
  const val CUTOFF_MILLIS = 1_735_689_600_000L

  fun run(datasets: List<StationDataset>) {
    val report = StringBuilder()
    report.appendLine("=== ADDESTRAMENTO NOWCAST V1 — split temporale al 2025-01-01 ===")
    report.appendLine()

    println("costruisco il training set (un'ora di passo, 10 localita')...")
    val builder = TrainingSetBuilder()
    val rows = datasets.flatMap { dataset ->
      builder.build(dataset).also { println("  ${dataset.location.name}: ${it.size} righe") }
    }
    val train = rows.filter { it.timestampMillis < CUTOFF_MILLIS }
    val eval = rows.filter { it.timestampMillis >= CUTOFF_MILLIS }
    report.appendLine("righe: ${train.size} train, ${eval.size} valutazione")
    report.appendLine()

    val standardization = Standardization.fit(train.map { it.features }, FeatureExtractor.names.size)
    val trainX = train.map { standardization.apply(it.features) }
    val evalX = eval.map { standardization.apply(it.features) }

    val baggedByWindow = mutableMapOf<String, List<DoubleArray>>()
    for (window in EventWindow.Standard) {
      val label = window.label
      val trainY = train.map { it.labels.getValue(label) }
      val evalY = eval.map { it.labels.getValue(label) }

      println("addestro $label: logistica (5 bag)...")
      val bags = LogisticTrainer().fitBagged(trainX, trainY)
      baggedByWindow[label] = bags

      println("addestro $label: gradient boosting di confronto...")
      val gbm = Gbm()
      gbm.fit(trainX, trainY)

      val logisticVerifications = evalX.indices.map { i ->
        val p = bags.map { sigmoidScore(it, evalX[i]) }.average()
        Verification(p, evalY[i])
      }
      val gbmVerifications = evalX.indices.map { i -> Verification(gbm.predict(evalX[i]), evalY[i]) }

      report.appendLine("--- finestra $label (valutazione out-of-sample, ${evalY.size} righe, base ${format(evalY.count { it }.toDouble() / evalY.size)})")
      report.appendLine(metricsLine("logistica (spedita)", logisticVerifications))
      report.appendLine(metricsLine("gbm (confronto)    ", gbmVerifications))

      report.appendLine("  coefficienti (bag 1, feature standardizzate):")
      val reference = bags.first()
      FeatureExtractor.names
        .mapIndexed { i, name -> name to reference[i + 1] }
        .sortedByDescending { kotlin.math.abs(it.second) }
        .forEach { (name, weight) ->
          report.appendLine(String.format(Locale.ROOT, "    %-22s %+8.4f", name, weight))
        }
      report.appendLine()
    }

    emitKotlin(standardization, baggedByWindow)
    report.appendLine(">> coefficienti rigenerati in nowcast/.../verdict/TrainedNowcastV1.kt")

    println(report.toString())
    File("reports").mkdirs()
    File("reports/training.txt").writeText(report.toString())
    println(">> scritto reports/training.txt")
  }

  private fun metricsLine(name: String, verifications: List<Verification>): String {
    val contingency = Contingency.at(0.5, verifications)
    return String.format(
      Locale.ROOT,
      "  %s  Brier=%s BSS=%s POD=%s FAR=%s CSI=%s",
      name,
      format(Probabilistic.brier(verifications)),
      format(Probabilistic.brierSkillScore(verifications)),
      format(contingency.pod),
      format(contingency.far),
      format(contingency.csi),
    )
  }

  private fun sigmoidScore(weights: DoubleArray, x: DoubleArray): Double {
    var score = weights[0]
    for (i in x.indices) score += weights[i + 1] * x[i]
    return 1.0 / (1.0 + kotlin.math.exp(-score))
  }

  private fun format(value: Double): String =
    if (value.isNaN()) "—" else String.format(Locale.ROOT, "%.3f", value)

  /** La tabella spedita: un file Kotlin generato, leggibile, diffabile, versionato in git. */
  private fun emitKotlin(
    standardization: Standardization,
    baggedByWindow: Map<String, List<DoubleArray>>,
  ) {
    val version = "v1-${LocalDate.now()}"
    val builder = StringBuilder()
    builder.appendLine("package dev.pampa.fluidweather.nowcast.verdict")
    builder.appendLine()
    builder.appendLine("/**")
    builder.appendLine(" * GENERATO da `gradlew :testbench:run --args=train` — non modificare a mano.")
    builder.appendLine(" * Addestrato su 10 localita', archivio orario fino al 2024-12-31; valutato dal 2025-01-01.")
    builder.appendLine(" * Dati: Open-Meteo.com (CC BY 4.0), uso non commerciale. Metriche in reports/training.txt.")
    builder.appendLine(" * Ordine delle feature = FeatureExtractor.names; ogni bag = [intercetta, pesi...].")
    builder.appendLine(" */")
    builder.appendLine("object TrainedNowcastV1 {")
    builder.appendLine("  const val VERSION: String = \"$version\"")
    builder.appendLine()
    builder.appendLine("  val means: DoubleArray = doubleArrayOf(")
    builder.appendLine("    ${standardization.means.joinToString(", ") { number(it) }},")
    builder.appendLine("  )")
    builder.appendLine()
    builder.appendLine("  val sds: DoubleArray = doubleArrayOf(")
    builder.appendLine("    ${standardization.sds.joinToString(", ") { number(it) }},")
    builder.appendLine("  )")
    builder.appendLine()
    builder.appendLine("  val windows: List<WindowCoefficients> = listOf(")
    for ((window, bags) in baggedByWindow) {
      builder.appendLine("    WindowCoefficients(")
      builder.appendLine("      window = \"$window\",")
      builder.appendLine("      bags = listOf(")
      for (bag in bags) {
        builder.appendLine("        doubleArrayOf(${bag.joinToString(", ") { number(it) }}),")
      }
      builder.appendLine("      ),")
      builder.appendLine("    ),")
    }
    builder.appendLine("  )")
    builder.appendLine("}")

    val target = File("../nowcast/src/main/kotlin/dev/pampa/fluidweather/nowcast/verdict/TrainedNowcastV1.kt")
    target.writeText(builder.toString())
  }

  private fun number(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
