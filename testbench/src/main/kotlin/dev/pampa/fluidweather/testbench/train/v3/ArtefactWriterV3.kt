package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import java.io.File

/**
 * `train-v3 emit [--from candidate|release]`: l'artefatto su disco diventa Kotlin dentro `:nowcast` —
 * quattro tabelle `TrainedNowcastV3<Livello>.kt`, l'indice `TrainedNowcastV3.kt` (versione, famiglia,
 * nomi delle feature, riferimento di tutti i posti, impronte degli alberi) e, se la famiglia e' GBM, le
 * dodici risorse `.fwgb`.
 *
 * I numeri si scrivono con `Double.toString()`, che si rilegge identico: la tabella compilata e quella
 * del candidato sono gli stessi bit (e hanno la stessa impronta, vedi [CandidateArtefact.fingerprint]).
 */
object ArtefactWriterV3 {

  val SOURCE_DIR: File = File("../nowcast/src/main/kotlin/dev/pampa/fluidweather/nowcast/verdict")
  val RESOURCE_DIR: File = File("../nowcast/src/main/resources/" + TieredNowcastModel.RESOURCE_DIR)

  private val CLASS_NAMES: Map<ContextTier, String> = mapOf(
    ContextTier.FRESH to "TrainedNowcastV3Fresh",
    ContextTier.STALE to "TrainedNowcastV3Stale",
    ContextTier.NONE to "TrainedNowcastV3None",
    ContextTier.NONE_NOCLIMA to "TrainedNowcastV3NoneNoClima",
  )

  fun className(tier: ContextTier): String = CLASS_NAMES.getValue(tier)

  /** Scrive tutto e ritorna il rapporto `model-v3.txt`. */
  fun emit(
    artefact: CandidateArtefact,
    origin: String,
    sourceDir: File = SOURCE_DIR,
    resourceDir: File = RESOURCE_DIR,
  ): String {
    val version = artefact.version
    require(Regex("v3-\\d{4}-\\d{2}-\\d{2}").matches(version)) { "la versione spedita dev'essere v3-<data>, non $version" }
    sourceDir.mkdirs()
    for (tier in ContextTier.entries) {
      File(sourceDir, "${className(tier)}.kt").writeText(tierSource(tier, artefact.logistic.getValue(tier), artefact, origin))
    }
    val shipsTrees = artefact.requestedFamily == ModelFamily.GBM
    resourceDir.listFiles()?.filter { it.name.endsWith(".fwgb") }?.forEach { it.delete() }
    if (shipsTrees) {
      val trees = requireNotNull(artefact.trees) { "la famiglia e' GBM ma l'artefatto non ha alberi" }
      resourceDir.mkdirs()
      for ((name, bytes) in trees) File(resourceDir, name).writeBytes(bytes)
    }
    File(sourceDir, "TrainedNowcastV3.kt").writeText(indexSource(artefact, origin, shipsTrees))
    return report(artefact, origin, shipsTrees)
  }

  private fun header(artefact: CandidateArtefact, origin: String): String = buildString {
    appendLine(" * GENERATO da `gradlew :testbench:run --args=\"train-v3 emit --from $origin\"` — non modificare a mano.")
    appendLine(" * ${artefact.info["trained"] ?: "addestramento del v3"}.")
    appendLine(" * Profilo di campionamento: TELEFONO; etichetta: PANNELLO (panel-1); tabelle di baseline dagli altri anni (jackknife).")
    appendLine(" * Dati: Open-Meteo.com (CC BY 4.0), uso non commerciale. Metriche in reports/training-v3*.txt e reports/gate-*-v3*.txt.")
  }

  private fun tierSource(tier: ContextTier, table: LogisticTableV3, artefact: CandidateArtefact, origin: String): String = buildString {
    appendLine("package dev.pampa.fluidweather.nowcast.verdict")
    appendLine()
    appendLine("/**")
    append(header(artefact, origin))
    appendLine(" *")
    appendLine(" * \"Il tuo barometro\" v3, livello ${tier.name}: la tabella logistica, cinque bag per finestra.")
    appendLine(" * Ordine delle feature = FeatureExtractorV3.names (42); ogni bag = [intercetta, 42 pesi] su scala standardizzata,")
    appendLine(" * con l'ancora (se c'e') gia' dentro; i pesi fuori da [used] sono zero per costruzione.")
    appendLine(" */")
    appendLine("object ${className(tier)} {")
    appendLine("  val means: DoubleArray = doubleArrayOf(")
    append(numbers(table.means, "    "))
    appendLine("  )")
    appendLine()
    appendLine("  val sds: DoubleArray = doubleArrayOf(")
    append(numbers(table.sds, "    "))
    appendLine("  )")
    appendLine()
    appendLine("  /** Le colonne con un coefficiente libero, per finestra. */")
    appendLine("  val used: Map<String, IntArray> = mapOf(")
    for ((w, window) in RainWindows.ALL.withIndex()) appendLine("    \"${window.label}\" to intArrayOf(${table.used[w].joinToString(", ")}),")
    appendLine("  )")
    appendLine()
    appendLine("  val windows: List<WindowCoefficients> = listOf(")
    for ((w, window) in RainWindows.ALL.withIndex()) {
      appendLine("    WindowCoefficients(")
      appendLine("      window = \"${window.label}\",")
      appendLine("      bags = listOf(")
      for (bag in table.bags[w]) {
        appendLine("        doubleArrayOf(")
        append(numbers(bag, "          "))
        appendLine("        ),")
      }
      appendLine("      ),")
      appendLine("    ),")
    }
    appendLine("  )")
    appendLine("}")
  }

  private fun indexSource(artefact: CandidateArtefact, origin: String, shipsTrees: Boolean): String = buildString {
    appendLine("package dev.pampa.fluidweather.nowcast.verdict")
    appendLine()
    appendLine("import dev.pampa.fluidweather.nowcast.features.ContextTier")
    appendLine()
    appendLine("/**")
    append(header(artefact, origin))
    appendLine(" *")
    appendLine(" * L'indice del v3: versione, famiglia scelta da D4, nomi delle feature (il contratto), il riferimento di tutti")
    appendLine(" * i posti che viaggia col modello (PP1: NONE_NOCLIMA e ripiego delle tabelle locali), le risorse degli alberi")
    appendLine(" * con la loro impronta SHA-256 (controllata al caricamento: se non torna parla la logistica) e gli iperparametri.")
    appendLine(" */")
    appendLine("object TrainedNowcastV3 {")
    appendLine("  const val VERSION: String = \"${artefact.version}\"")
    appendLine()
    appendLine("  /** La famiglia che il telefono carica: ${if (shipsTrees) "gli alberi, con la logistica come paracadute" else "la logistica"}. */")
    appendLine("  const val FAMILY: String = \"${artefact.requestedFamily.name}\"")
    appendLine()
    appendLine("  const val FEATURES_VERSION: String = \"${FeatureExtractorV3.VERSION}\"")
    appendLine()
    appendLine("  const val PRIORS_FORMATS: String = \"WC1+LB2+PP1\"")
    appendLine()
    appendLine("  /** Il riferimento di tutti i posti (PP1) sulla storia delle baseline del periodo di prova. */")
    appendLine("  const val POOLED: String = \"${escape(artefact.pooled)}\"")
    appendLine()
    appendLine("  val FEATURE_NAMES: List<String> = listOf(")
    for (name in FeatureExtractorV3.names) appendLine("    \"$name\",")
    appendLine("  )")
    appendLine()
    appendLine("  val GBM_RESOURCES: List<String> = listOf(")
    if (shipsTrees) for (name in TieredNowcastModel.RESOURCE_NAMES) appendLine("    \"$name\",")
    appendLine("  )")
    appendLine()
    appendLine("  val GBM_SHA256: Map<String, String> = mapOf(")
    if (shipsTrees) for (name in TieredNowcastModel.RESOURCE_NAMES) appendLine("    \"$name\" to \"${TieredNowcastModel.sha256(artefact.trees!!.getValue(name))}\",")
    appendLine("  )")
    appendLine()
    appendLine("  /** Gli iperparametri per (livello.finestra): logistica (ancora, lambda, peso fuori Europa) e alberi (se ci sono). */")
    appendLine("  val HYPERPARAMETERS: Map<String, String> = mapOf(")
    for ((key, value) in shippedHyperparameters(artefact, shipsTrees)) appendLine("    \"${escape(key)}\" to \"${escape(value)}\",")
    appendLine("  )")
    appendLine()
    appendLine("  const val TRAINING: String = \"${escape(artefact.info["trained"] ?: "")}\"")
    appendLine()
    appendLine("  /** Le quattro tabelle logistiche come modelli. */")
    appendLine("  fun logisticTables(): Map<ContextTier, NowcastModel> = mapOf(")
    for (tier in ContextTier.entries) {
      val c = className(tier)
      appendLine("    ContextTier.${tier.name} to NowcastModel($c.means, $c.sds, $c.windows, FEATURE_NAMES),")
    }
    appendLine("  )")
    appendLine("}")
  }

  private fun report(artefact: CandidateArtefact, origin: String, shipsTrees: Boolean): String = buildString {
    appendLine("=== model-v3 — l'artefatto spedito ===")
    appendLine()
    appendLine("Generato da `train-v3 emit --from $origin`.")
    appendLine("Versione ${artefact.version}, famiglia ${artefact.requestedFamily}, feature ${FeatureExtractorV3.VERSION} (${FeatureExtractorV3.COUNT}).")
    appendLine("Impronta (logistica): ${artefact.fingerprint(ModelFamily.LOGISTICA)}" + if (shipsTrees) "; (alberi): ${artefact.fingerprint(ModelFamily.GBM)}" else "")
    appendLine("Addestramento: ${artefact.info["trained"]}")
    appendLine()
    appendLine("File Kotlin: TrainedNowcastV3.kt + ${ContextTier.entries.joinToString(", ") { className(it) + ".kt" }}")
    if (shipsTrees) {
      appendLine("Risorse (${artefact.treeBytes} byte):")
      for (name in TieredNowcastModel.RESOURCE_NAMES) appendLine("  $name  ${artefact.trees!!.getValue(name).size} byte  sha256 ${TieredNowcastModel.sha256(artefact.trees.getValue(name))}")
    } else {
      appendLine("Nessuna risorsa: la famiglia e' la logistica.")
    }
    appendLine()
    appendLine("Iperparametri:")
    for ((key, value) in shippedHyperparameters(artefact, shipsTrees)) appendLine("  $key: $value")
    appendLine()
    appendLine("Pavimenti: ${artefact.floors.values.joinToString(" / ") { it.encode() }}")
  }

  /** Gli iperparametri di cio' che si spedisce: quelli degli alberi solo se la famiglia li carica. */
  private fun shippedHyperparameters(artefact: CandidateArtefact, shipsTrees: Boolean): Map<String, String> =
    artefact.info.filterKeys { it.startsWith("cell.") && (shipsTrees || !it.endsWith(".gbm")) }
      .mapKeys { it.key.removePrefix("cell.") }.toSortedMap()

  private fun numbers(values: DoubleArray, indent: String): String = buildString {
    for (chunk in values.toList().chunked(6)) {
      append(indent)
      append(chunk.joinToString(", ") { it.toString() })
      appendLine(",")
    }
  }

  private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}
