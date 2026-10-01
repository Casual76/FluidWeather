package dev.pampa.fluidweather.nowcast.verdict

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Un albero di [TreeEnsemble], nodi in preordine: il figlio sinistro di un nodo interno e' il nodo
 * dopo, il destro e' in [rights]. I valori sono gia' moltiplicati per il passo di apprendimento.
 *
 * - [flags]: bit 0 = foglia, bit 1 = il NaN va a sinistra.
 * - [values]: per una foglia il suo contributo al punteggio; per un nodo interno il valore di Newton
 *   che avrebbe come foglia — non entra nel punteggio, serve a spiegarlo (vedi [TreeEnsemble.explain]).
 * - [features], [thresholds]: la colonna e la soglia di un nodo interno (`x <= soglia` va a sinistra).
 */
class Tree(
  val flags: ByteArray,
  val values: FloatArray,
  val features: IntArray,
  val thresholds: FloatArray,
  val rights: IntArray,
) {
  val size: Int get() = flags.size

  init {
    require(size in 1..MAX_NODES) { "un albero ha da 1 a $MAX_NODES nodi, non $size" }
    require(values.size == size && features.size == size && thresholds.size == size && rights.size == size) {
      "colonne dell'albero di lunghezza diversa"
    }
    // Il preordine deve reggere: ogni sottoalbero finisce dove comincia il fratello destro.
    val end = subtreeEnd(0)
    require(end == size) { "albero malformato: il preordine finisce a $end su $size nodi" }
  }

  fun isLeaf(node: Int): Boolean = flags[node].toInt() and LEAF != 0

  fun nanGoesLeft(node: Int): Boolean = flags[node].toInt() and NAN_LEFT != 0

  /** Dove finisce (escluso) il sottoalbero che comincia in [node]; controlla le frecce strada facendo. */
  private fun subtreeEnd(node: Int): Int {
    require(node in 0 until size) { "albero malformato: nodo $node fuori da 0..${size - 1}" }
    if (isLeaf(node)) return node + 1
    val leftEnd = subtreeEnd(node + 1)
    require(rights[node] == leftEnd) { "albero malformato: il figlio destro di $node e' ${rights[node]}, atteso $leftEnd" }
    return subtreeEnd(rights[node])
  }

  /** Il figlio che prende [x]. */
  fun childOf(node: Int, x: DoubleArray): Int {
    val value = x[features[node]]
    val left = if (value.isNaN()) nanGoesLeft(node) else value <= thresholds[node].toDouble()
    return if (left) node + 1 else rights[node]
  }

  /** La foglia che prende [x]. */
  fun leafOf(x: DoubleArray): Int {
    var node = 0
    while (!isLeaf(node)) node = childOf(node, x)
    return node
  }

  companion object {
    const val LEAF: Int = 1
    const val NAN_LEFT: Int = 2

    /** Il massimo che un u16 sa indicizzare. */
    const val MAX_NODES: Int = 65_535
  }
}

/** La spiegazione di un punteggio: [score] = [bias] + la somma di [contributions], esattamente. */
class TreeExplanation(
  val score: Double,
  val bias: Double,
  /** Per colonna, quanto quella feature ha spostato il punteggio (log-odds). */
  val contributions: DoubleArray,
)

/**
 * Un comitato di alberi di regressione sul log-odds (gradient boosting) per un (livello, finestra),
 * e il suo formato binario `.fwgb`.
 *
 * **Il punteggio.** `score = base + ancora + somma delle foglie`, dove l'ancora e' il valore grezzo
 * della feature [offsetFeature] (una baseline in log-odds, vedi
 * [dev.pampa.fluidweather.nowcast.features.FeatureSubsets.anchorCandidates]) o niente se e' -1. Un
 * NaN sull'ancora vale la sua media di addestramento ([offsetMean]): neutro, come nella logistica.
 *
 * **Perche' resta spiegabile.** Ogni nodo interno porta il valore che avrebbe come foglia. Lungo il
 * cammino di un vettore, ogni split sposta il punteggio da `valore(nodo)` a `valore(figlio)`, e quella
 * differenza e' merito della feature su cui lo split si decide (contributi di Saabas). La somma sul
 * cammino e' esattamente `foglia - radice`; le radici e la base finiscono nel [TreeExplanation.bias],
 * l'ancora contribuisce `ancora - offsetMean`. Cosi' i fattori del verdetto sono ancora grandezze con
 * un nome e un segno, come nella logistica.
 *
 * **Il formato** (little-endian): `"FWGB"`, versione u16 = 1, numero di feature u16, livello u8,
 * finestra u8, feature dell'ancora i16 (-1 = nessuna), base f64, media dell'ancora f64, numero di alberi
 * u32; per albero il numero di nodi u16 e poi i nodi in preordine: flags u8, valore f32, e per i nodi
 * interni feature u16, soglia f32, figlio destro u16.
 */
class TreeEnsemble(
  val featureCount: Int,
  /** L'ordinale del livello di contesto: il file dice a chi appartiene, e chi lo carica controlla. */
  val tier: Int,
  /** L'indice della finestra in `RainWindows.ALL`. */
  val window: Int,
  val offsetFeature: Int,
  val baseScore: Double,
  val offsetMean: Double,
  val trees: List<Tree>,
) {

  init {
    require(featureCount in 1..0xFFFF) { "numero di feature fuori scala: $featureCount" }
    require(tier in 0..0xFF && window in 0..0xFF) { "livello o finestra fuori scala" }
    require(offsetFeature == -1 || offsetFeature in 0 until featureCount) { "ancora fuori dalle feature: $offsetFeature" }
    require(!baseScore.isNaN() && !offsetMean.isNaN()) { "base o media dell'ancora NaN" }
    for (tree in trees) {
      for (node in 0 until tree.size) {
        require(!tree.values[node].isNaN()) { "valore NaN in un albero" }
        if (!tree.isLeaf(node)) {
          require(tree.features[node] in 0 until featureCount) { "split su una feature fuori scala: ${tree.features[node]}" }
          require(!tree.thresholds[node].isNaN()) { "soglia NaN" }
        }
      }
    }
  }

  private fun offsetOf(x: DoubleArray): Double {
    if (offsetFeature < 0) return 0.0
    val value = x[offsetFeature]
    return if (value.isNaN()) offsetMean else value
  }

  /** Il punteggio (log-odds) di [x]. */
  fun score(x: DoubleArray): Double {
    require(x.size == featureCount) { "attese $featureCount feature, arrivate ${x.size}" }
    var sum = baseScore + offsetOf(x)
    for (tree in trees) sum += tree.values[tree.leafOf(x)].toDouble()
    return sum
  }

  /** Il punteggio di [x] con il merito di ogni feature: `score = bias + somma(contributi)`. */
  fun explain(x: DoubleArray): TreeExplanation {
    require(x.size == featureCount) { "attese $featureCount feature, arrivate ${x.size}" }
    val contributions = DoubleArray(featureCount)
    var bias = baseScore
    var score = baseScore
    if (offsetFeature >= 0) {
      val offset = offsetOf(x)
      bias += offsetMean
      contributions[offsetFeature] += offset - offsetMean
      score += offset
    }
    for (tree in trees) {
      var node = 0
      bias += tree.values[0].toDouble()
      while (!tree.isLeaf(node)) {
        val child = tree.childOf(node, x)
        contributions[tree.features[node]] += tree.values[child].toDouble() - tree.values[node].toDouble()
        node = child
      }
      score += tree.values[node].toDouble()
    }
    return TreeExplanation(score, bias, contributions)
  }

  /** I byte del file `.fwgb`: [parse] li rilegge identici. */
  fun encode(): ByteArray {
    val buffer = ByteBuffer.allocate(encodedSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(MAGIC)
    buffer.putShort(FORMAT_VERSION.toShort())
    buffer.putShort(featureCount.toShort())
    buffer.put(tier.toByte())
    buffer.put(window.toByte())
    buffer.putShort(offsetFeature.toShort())
    buffer.putDouble(baseScore)
    buffer.putDouble(offsetMean)
    buffer.putInt(trees.size)
    for (tree in trees) {
      buffer.putShort(tree.size.toShort())
      for (node in 0 until tree.size) {
        buffer.put(tree.flags[node])
        buffer.putFloat(tree.values[node])
        if (!tree.isLeaf(node)) {
          buffer.putShort(tree.features[node].toShort())
          buffer.putFloat(tree.thresholds[node])
          buffer.putShort(tree.rights[node].toShort())
        }
      }
    }
    return buffer.array()
  }

  /** Quanti byte occupa il file. */
  val encodedSize: Int
    get() {
      var size = HEADER_BYTES
      for (tree in trees) {
        size += 2
        for (node in 0 until tree.size) size += if (tree.isLeaf(node)) LEAF_BYTES else INTERNAL_BYTES
      }
      return size
    }

  /** Quanti nodi ha il comitato: per il rapporto. */
  val nodeCount: Int get() = trees.sumOf { it.size }

  companion object {
    val MAGIC: ByteArray = "FWGB".toByteArray(Charsets.US_ASCII)
    const val FORMAT_VERSION: Int = 1
    const val HEADER_BYTES: Int = 4 + 2 + 2 + 1 + 1 + 2 + 8 + 8 + 4
    const val LEAF_BYTES: Int = 1 + 4
    const val INTERNAL_BYTES: Int = 1 + 4 + 2 + 4 + 2

    /**
     * Rilegge un `.fwgb`. [expectedFeatureCount] e' il contratto delle feature di chi lo carica: un file
     * scritto per un altro contratto non si usa. Qualunque cosa fuori posto — firma, versione, lunghezza,
     * alberi malformati, byte in piu' — e' un [IllegalArgumentException]: meglio nessun albero che uno sbagliato.
     */
    fun parse(bytes: ByteArray, expectedFeatureCount: Int): TreeEnsemble {
      require(bytes.size >= HEADER_BYTES) { "file troppo corto per l'intestazione: ${bytes.size} byte" }
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val magic = ByteArray(4).also { buffer.get(it) }
      require(magic.contentEquals(MAGIC)) { "non e' un file FWGB" }
      val version = buffer.short.toInt() and 0xFFFF
      require(version == FORMAT_VERSION) { "versione FWGB $version, attesa $FORMAT_VERSION" }
      val featureCount = buffer.short.toInt() and 0xFFFF
      require(featureCount == expectedFeatureCount) { "il file conosce $featureCount feature, il contratto $expectedFeatureCount" }
      val tier = buffer.get().toInt() and 0xFF
      val window = buffer.get().toInt() and 0xFF
      val offsetFeature = buffer.short.toInt()
      val baseScore = buffer.double
      val offsetMean = buffer.double
      val treeCount = buffer.int
      require(treeCount >= 0 && treeCount.toLong() * (2 + LEAF_BYTES) <= buffer.remaining()) { "numero di alberi impossibile: $treeCount" }
      val trees = ArrayList<Tree>(treeCount)
      try {
        repeat(treeCount) {
          val nodes = buffer.short.toInt() and 0xFFFF
          val flags = ByteArray(nodes)
          val values = FloatArray(nodes)
          val features = IntArray(nodes) { -1 }
          val thresholds = FloatArray(nodes) { Float.NaN }
          val rights = IntArray(nodes) { -1 }
          for (node in 0 until nodes) {
            flags[node] = buffer.get()
            values[node] = buffer.float
            if (flags[node].toInt() and Tree.LEAF == 0) {
              features[node] = buffer.short.toInt() and 0xFFFF
              thresholds[node] = buffer.float
              rights[node] = buffer.short.toInt() and 0xFFFF
            }
          }
          trees += Tree(flags, values, features, thresholds, rights)
        }
      } catch (e: java.nio.BufferUnderflowException) {
        throw IllegalArgumentException("file FWGB troncato", e)
      }
      require(!buffer.hasRemaining()) { "file FWGB con ${buffer.remaining()} byte in piu'" }
      return TreeEnsemble(featureCount, tier, window, offsetFeature, baseScore, offsetMean, trees)
    }
  }
}
