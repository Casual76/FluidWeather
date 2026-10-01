package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.climatology.PooledPriors
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.ObservationFloors
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.nowcast.verdict.WindowCoefficients
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * La tabella logistica di un livello del v3: medie e deviazioni delle quarantadue colonne, le colonne
 * usate per finestra e il comitato (cinque bag x [intercetta, 42 coefficienti]) di ogni finestra.
 *
 * Il testo di [encode] scrive i numeri con `Double.toString`, che si rilegge identico: la tabella del
 * candidato e quella compilata in `TrainedNowcastV3<Livello>.kt` sono gli stessi bit, e hanno la
 * stessa impronta.
 */
class LogisticTableV3(
  val tier: ContextTier,
  val means: DoubleArray,
  val sds: DoubleArray,
  /** Per finestra (ordine di [RainWindows.ALL]) le colonne con coefficiente libero. */
  val used: List<IntArray>,
  /** Per finestra, i bag. */
  val bags: List<List<DoubleArray>>,
) {
  init {
    require(means.size == FeatureExtractorV3.COUNT && sds.size == FeatureExtractorV3.COUNT) { "medie/deviazioni di lunghezza sbagliata" }
    require(used.size == RainWindows.ALL.size && bags.size == RainWindows.ALL.size) { "una voce per finestra" }
    require(bags.all { w -> w.isNotEmpty() && w.all { it.size == FeatureExtractorV3.COUNT + 1 } }) { "bag di lunghezza sbagliata" }
  }

  fun windows(): List<WindowCoefficients> = RainWindows.ALL.mapIndexed { w, window -> WindowCoefficients(window.label, bags[w]) }

  fun toModel(): NowcastModel = NowcastModel(means, sds, windows(), FeatureExtractorV3.names)

  fun encode(): String = buildString {
    appendLine("tier ${tier.name}")
    appendLine("means " + means.joinToString(" ") { it.toString() })
    appendLine("sds " + sds.joinToString(" ") { it.toString() })
    for ((w, window) in RainWindows.ALL.withIndex()) {
      appendLine("used ${window.label} " + used[w].joinToString(","))
      for ((b, bag) in bags[w].withIndex()) appendLine("bag ${window.label} $b " + bag.joinToString(" ") { it.toString() })
    }
  }

  companion object {
    fun decode(text: String): LogisticTableV3 {
      var tier: ContextTier? = null
      var means: DoubleArray? = null
      var sds: DoubleArray? = null
      val used = MutableList(RainWindows.ALL.size) { IntArray(0) }
      val bags = List(RainWindows.ALL.size) { ArrayList<DoubleArray>() }
      for (line in text.lines().filter { it.isNotBlank() }) {
        val parts = line.trim().split(' ')
        when (parts[0]) {
          "tier" -> tier = ContextTier.valueOf(parts[1])
          "means" -> means = parts.drop(1).map { it.toDouble() }.toDoubleArray()
          "sds" -> sds = parts.drop(1).map { it.toDouble() }.toDoubleArray()
          "used" -> used[windowIndex(parts[1])] = if (parts.size < 3 || parts[2].isEmpty()) IntArray(0) else parts[2].split(',').map { it.toInt() }.toIntArray()
          "bag" -> bags[windowIndex(parts[1])] += parts.drop(3).map { it.toDouble() }.toDoubleArray()
          else -> error("riga sconosciuta nella tabella: ${parts[0]}")
        }
      }
      return LogisticTableV3(requireNotNull(tier), requireNotNull(means), requireNotNull(sds), used, bags)
    }

    private fun windowIndex(label: String): Int = RainWindows.ALL.indexOfFirst { it.label == label }.also { require(it >= 0) { "finestra sconosciuta: $label" } }
  }
}

/**
 * Un artefatto del v3 su disco, prima di diventare Kotlin: il candidato della scelta
 * (`build/v3/candidate/`) o il riaddestramento finale (`build/v3/release/`).
 *
 * - `logistic-<livello>.txt`: le quattro tabelle ([LogisticTableV3]);
 * - `<livello>-<finestra>.fwgb`: gli alberi, se ci sono;
 * - `pooled.txt`: il riferimento di tutti i posti (PP1) che viaggia col modello;
 * - `floors.txt`: la politica dei pavimenti ([ObservationFloors.encodeAll]);
 * - `manifest.txt`: versione, famiglia chiesta, iperparametri, impronte dei file e impronta dell'artefatto.
 *
 * L'**impronta** ([fingerprint]) e' SHA-256 di versione | famiglia | nomi delle feature | testo delle
 * quattro tabelle | SHA di ogni `.fwgb` | riferimento di tutti i posti | pavimenti, prime dieci cifre: il
 * gate la scrive nel registro, cosi' si sa **quale** artefatto e' stato giudicato.
 */
class CandidateArtefact(
  val version: String,
  val requestedFamily: ModelFamily,
  val logistic: Map<ContextTier, LogisticTableV3>,
  /** Nome del file -> byte; null se l'artefatto e' solo logistico. */
  val trees: Map<String, ByteArray>?,
  val pooled: String,
  val floors: Map<ContextTier, ObservationFloors>,
  /** Righe informative (iperparametri, periodi, decisioni): chiave -> valore. */
  val info: Map<String, String> = emptyMap(),
) {

  init {
    require(ContextTier.entries.all { it in logistic }) { "manca una tabella" }
    require(ModelVersionsGuard.isTaggable(version)) { "versione non etichettabile: $version" }
  }

  /** L'impronta per la famiglia [family] (di default quella chiesta). */
  fun fingerprint(family: ModelFamily = requestedFamily): String = fingerprintOf(
    version, family, ContextTier.entries.map { logistic.getValue(it).encode() },
    if (family == ModelFamily.GBM) trees.orEmpty().toSortedMap().mapValues { TieredNowcastModel.sha256(it.value) } else emptyMap(),
    pooled, ObservationFloors.encodeAll(floors),
  )

  /** Il modello, con la famiglia chiesta o quella forzata. */
  fun toModel(family: ModelFamily = requestedFamily): TieredNowcastModel = TieredNowcastModel.assemble(
    version = version,
    family = family,
    logistic = logistic.mapValues { it.value.toModel() },
    treeBytes = trees?.let { map -> { name: String -> map[name] } },
    expectedSha = trees?.mapValues { TieredNowcastModel.sha256(it.value) },
    pooled = PooledPriors.decode(pooled),
    floors = floors,
  )

  /** Il peso degli alberi in byte (zero senza alberi). */
  val treeBytes: Int get() = trees?.values?.sumOf { it.size } ?: 0

  fun write(dir: File) {
    dir.mkdirs()
    dir.listFiles()?.filter { it.name.endsWith(".fwgb") }?.forEach { it.delete() }
    for ((tier, table) in logistic) File(dir, "logistic-${TieredNowcastModel.slug(tier)}.txt").writeText(table.encode())
    trees?.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
    File(dir, "pooled.txt").writeText(pooled + "\n")
    File(dir, "floors.txt").writeText(ObservationFloors.encodeAll(floors) + "\n")
    File(dir, MANIFEST).writeText(
      buildString {
        appendLine("version=$version")
        appendLine("family=${requestedFamily.name}")
        appendLine("features=${FeatureExtractorV3.VERSION}")
        for ((key, value) in info) appendLine("$key=$value")
        for ((tier, table) in logistic) appendLine("sha.logistic-${TieredNowcastModel.slug(tier)}.txt=${TieredNowcastModel.sha256(table.encode().toByteArray())}")
        trees?.toSortedMap()?.forEach { (name, bytes) -> appendLine("sha.$name=${TieredNowcastModel.sha256(bytes)}") }
        appendLine("fingerprint.LOGISTICA=${fingerprint(ModelFamily.LOGISTICA)}")
        if (trees != null) appendLine("fingerprint.GBM=${fingerprint(ModelFamily.GBM)}")
      },
    )
  }

  /** Lo stesso artefatto con un'altra famiglia chiesta, altri pavimenti o altre righe informative. */
  fun copy(
    requestedFamily: ModelFamily = this.requestedFamily,
    floors: Map<ContextTier, ObservationFloors> = this.floors,
    info: Map<String, String> = this.info,
    version: String = this.version,
  ): CandidateArtefact = CandidateArtefact(version, requestedFamily, logistic, trees, pooled, floors, info)

  companion object {
    const val MANIFEST: String = "manifest.txt"

    fun exists(dir: File): Boolean = File(dir, MANIFEST).exists()

    fun read(dir: File): CandidateArtefact {
      require(exists(dir)) { "nessun artefatto in ${dir.path}: prima `train-v3 tune`" }
      val manifest = File(dir, MANIFEST).readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
      val logistic = ContextTier.entries.associateWith { tier ->
        LogisticTableV3.decode(File(dir, "logistic-${TieredNowcastModel.slug(tier)}.txt").readText())
      }
      val treeFiles = TieredNowcastModel.RESOURCE_NAMES.map { File(dir, it) }
      val trees = if (treeFiles.all { it.exists() }) treeFiles.associate { it.name to it.readBytes() } else null
      val floorsFile = File(dir, "floors.txt")
      val floors = if (floorsFile.exists()) ObservationFloors.decodeAll(floorsFile.readText()) ?: error("floors.txt illeggibile") else ObservationFloors.none()
      val reserved = setOf("version", "family", "features")
      val info = manifest.filterKeys { it !in reserved && !it.startsWith("sha.") && !it.startsWith("fingerprint.") }
      return CandidateArtefact(
        version = manifest.getValue("version"),
        requestedFamily = ModelFamily.valueOf(manifest.getValue("family")),
        logistic = logistic,
        trees = trees,
        pooled = File(dir, "pooled.txt").readText().trim(),
        floors = floors,
        info = info,
      )
    }

    fun fingerprintOf(
      version: String,
      family: ModelFamily,
      tableTexts: List<String>,
      treeSha: Map<String, String>,
      pooled: String,
      floorsText: String,
    ): String {
      val text = buildString {
        append(version).append('|').append(family.name).append('|')
        append(FeatureExtractorV3.names.joinToString(",")).append('|')
        tableTexts.forEach { append(it).append('|') }
        treeSha.toSortedMap().forEach { (name, sha) -> append(name).append('=').append(sha).append('|') }
        append(pooled).append('|').append(floorsText)
      }
      return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }.take(10)
    }
  }
}

/** Una versione si puo' mettere in un'etichetta ([dev.pampa.fluidweather.nowcast.verdict.ModelVersions])? */
internal object ModelVersionsGuard {
  fun isTaggable(version: String): Boolean = version.isNotEmpty() && '+' !in version && ' ' !in version
}
