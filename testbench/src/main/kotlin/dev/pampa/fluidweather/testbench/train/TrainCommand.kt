package dev.pampa.fluidweather.testbench.train

import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.events.EventWindow
import dev.pampa.fluidweather.testbench.metrics.Contingency
import dev.pampa.fluidweather.testbench.metrics.Probabilistic
import dev.pampa.fluidweather.testbench.metrics.Verification
import dev.pampa.fluidweather.testbench.replay.SamplingProfile
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * L'addestramento offline: tabellare, su un portatile, in minuti. Split TEMPORALE — si impara dal
 * passato e si giudica sul futuro — e confronto logistica-vs-boosting sulle stesse feature. Cio'
 * che si spedisce e' la tabella leggibile della logistica, rigenerata in
 * nowcast/src/main/kotlin/.../verdict/TrainedNowcastV1.kt e committata.
 *
 * Le righe si costruiscono col profilo TELEFONO: raffiche corte, quota GPS che balla, buchi in
 * Doze, letture secche. Il modello impara sulla distribuzione d'ingresso che vedra' davvero,
 * invece che su un archivio orario perfetto che sul telefono non esiste.
 */
object TrainCommand {

  /** 2025-09-01T00:00:00Z: tre anni per imparare, uno per essere giudicati. */
  const val CUTOFF_MILLIS = 1_756_684_800_000L

  fun run(datasets: List<StationDataset>, profile: SamplingProfile = SamplingProfile.TELEFONO) {
    val report = StringBuilder()
    report.appendLine("=== ADDESTRAMENTO NOWCAST — profilo $profile, split temporale al 2025-09-01 ===")
    report.appendLine()

    println("costruisco il training set (un'ora di passo, profilo $profile)...")
    val builder = TrainingSetBuilder(profile = profile)
    val rows = datasets.flatMap { dataset ->
      builder.build(dataset).also { println("  ${dataset.location.name}: ${it.size} righe") }
    }
    val train = rows.filter { it.timestampMillis < CUTOFF_MILLIS }
    val eval = rows.filter { it.timestampMillis >= CUTOFF_MILLIS }
    report.appendLine("righe: ${train.size} train, ${eval.size} valutazione")

    val standardization = Standardization.fit(train.map { it.features }, FeatureExtractor.names.size)
    if (standardization.degenerate.isEmpty()) {
      report.appendLine("feature degeneri: nessuna")
    } else {
      // Se questa riga non e' vuota, il banco sta guardando un mondo troppo liscio: una feature
      // che qui non varia sul telefono varia eccome, e finirebbe nel modello senza essere stata
      // davvero addestrata. E' la riga che mancava quando incertezza-tendenza aveva sd 1e-6.
      val names = standardization.degenerate.joinToString(", ") { FeatureExtractor.names[it] }
      report.appendLine("feature degeneri (COSTANTI nel train, neutralizzate): $names")
    }
    report.appendLine()

    val trainX = train.map { standardization.apply(it.features) }
    val evalX = eval.map { standardization.apply(it.features) }
    val baggedByWindow = LinkedHashMap<String, List<DoubleArray>>()

    for (window in EventWindow.Standard) {
      val label = window.label
      val trainY = train.map { it.labels.getValue(label) }
      val evalY = eval.map { it.labels.getValue(label) }

      println("addestro $label: logistica (5 bag, Newton)...")
      val fits = LogisticTrainer().fitBagged(trainX, trainY, standardization.degenerate)
      baggedByWindow[label] = fits.map { it.weights }

      println("addestro $label: gradient boosting di confronto...")
      val gbm = Gbm()
      gbm.fit(trainX, trainY)

      val logisticVerifications = evalX.indices.map { i ->
        val p = fits.map { sigmoidScore(it.weights, evalX[i]) }.average()
        Verification(p, evalY[i])
      }
      val gbmVerifications = evalX.indices.map { i -> Verification(gbm.predict(evalX[i]), evalY[i]) }

      report.appendLine("--- finestra $label (valutazione out-of-sample, ${evalY.size} righe, base ${format(evalY.count { it }.toDouble() / evalY.size)})")
      report.appendLine(metricsLine("logistica (spedita)", logisticVerifications))
      report.appendLine(metricsLine("gbm (confronto)    ", gbmVerifications))
      // La convergenza non e' un dettaglio da log: un coefficiente piccolo puo' voler dire
      // "questa feature non conta" oppure "non abbiamo aspettato", e sono due mondi diversi.
      val reference = fits.first()
      report.appendLine(
        String.format(
          Locale.ROOT,
          "  convergenza: %d iterazioni, |gradiente|=%.3e, log-loss=%.5f%s",
          reference.iterations,
          reference.gradientNorm,
          reference.logLoss,
          if (fits.all { it.converged }) "" else "  ATTENZIONE: NON CONVERGE",
        ),
      )

      report.appendLine("  coefficienti (bag 1, feature standardizzate):")
      FeatureExtractor.names
        .mapIndexed { i, name -> name to reference.weights[i + 1] }
        .sortedByDescending { kotlin.math.abs(it.second) }
        .forEach { (name, weight) ->
          report.appendLine(String.format(Locale.ROOT, "    %-22s %+8.4f", name, weight))
        }
      report.appendLine()
    }

    emitKotlin(standardization, baggedByWindow, profile)
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
    profile: SamplingProfile,
  ) {
    val version = "v2-${LocalDate.now()}"
    val builder = StringBuilder()
    builder.appendLine("package dev.pampa.fluidweather.nowcast.verdict")
    builder.appendLine()
    builder.appendLine("/**")
    builder.appendLine(" * GENERATO da `gradlew :testbench:run --args=train` — non modificare a mano.")
    builder.appendLine(" * Addestrato su 10 localita', archivio orario fino al 2025-08-31; valutato dal 2025-09-01.")
    builder.appendLine(" * Profilo di campionamento: $profile (raffiche, quota GPS che balla, buchi in Doze).")
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
