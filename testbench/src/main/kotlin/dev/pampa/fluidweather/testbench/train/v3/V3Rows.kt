package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.testbench.replay.TruthKind

/**
 * Le righe di addestramento (o di valutazione) del v3, in colonne: una riga per (emissione, livello),
 * con le quarantadue feature di [FeatureExtractorV3] e le tre etichette per finestra, PANNELLO ed ERA5.
 *
 * **Perche' in colonne.** Duecentomila emissioni per quattro livelli sono ottocentomila righe da
 * quarantadue numeri: come oggetti sarebbero gigabyte, come `FloatArray` sono centoquaranta megabyte.
 * Le feature stanno in un solo array piatto (`riga x 42`), in `Float`: l'errore di arrotondamento
 * (un decimilionesimo) e' un ordine di grandezza sotto il rumore di qualunque feature, e i NaN restano NaN.
 *
 * Le etichette sono byte: -1 = la finestra non si puo' giudicare con quella verita' (o l'etichetta e' fuori
 * dal limite della fase, vedi [JackknifePlan.labelCutoffMillis]), 0 = asciutta, 1 = bagnata. Si addestra
 * solo sul PANNELLO; ERA5 e' per riportare. L'ordine delle righe e' deterministico (localita' nell'ordine
 * dei dati, poi `t0`, poi livello): rifare il giro con piu' o meno thread da' le stesse righe negli stessi
 * posti.
 */
class V3Rows(
  /** I nomi delle localita': `locationIndex` punta qui. */
  val locations: List<String>,
  /** `riga x [FeatureExtractorV3.COUNT]`, NaN dove la feature manca. */
  val features: FloatArray,
  /** `riga x 3`: l'esito della finestra sul pannello. */
  val panel: ByteArray,
  /** `riga x 3`: l'esito della finestra su ERA5. */
  val era5: ByteArray,
  val locationIndex: ShortArray,
  /** L'ordinale del [ContextTier] della riga. */
  val tier: ByteArray,
  /** L'ancora delle finestre. */
  val t0: LongArray,
  /** L'istante di emissione del telefono. */
  val issue: LongArray,
  /** Bit di [FLAG_HISTORY_KNOWN] e [FLAG_LOCAL_PRIORS]: cosa lo scenario ha estratto per questa riga. */
  val flags: ByteArray,
) {

  val size: Int get() = t0.size

  init {
    require(features.size == size * FeatureExtractorV3.COUNT) { "feature di lunghezza sbagliata" }
    require(panel.size == size * WINDOWS && era5.size == size * WINDOWS) { "etichette di lunghezza sbagliata" }
    require(locationIndex.size == size && tier.size == size && issue.size == size && flags.size == size) { "colonne di lunghezza diversa" }
  }

  fun tierOf(row: Int): ContextTier = ContextTier.entries[tier[row].toInt()]

  fun locationOf(row: Int): String = locations[locationIndex[row].toInt()]

  fun feature(row: Int, column: Int): Float = features[row * FeatureExtractorV3.COUNT + column]

  /** Le quarantadue feature di una riga in `Double`: per passarle a un modello. */
  fun featureRow(row: Int): DoubleArray = DoubleArray(FeatureExtractorV3.COUNT) { features[row * FeatureExtractorV3.COUNT + it].toDouble() }

  /** L'esito di una finestra (0..2) sotto [truth]: -1 ingiudicabile, 0 asciutta, 1 bagnata. */
  fun label(row: Int, window: Int, truth: TruthKind = TruthKind.PANEL): Int =
    (if (truth == TruthKind.PANEL) panel else era5)[row * WINDOWS + window].toInt()

  /** Il giorno (UTC) dell'ancora: e' il blocco del bootstrap per giorni. */
  fun epochDay(row: Int): Int = Math.floorDiv(t0[row], DAY_MILLIS).toInt()

  fun historyKnown(row: Int): Boolean = flags[row].toInt() and FLAG_HISTORY_KNOWN != 0

  fun localPriors(row: Int): Boolean = flags[row].toInt() and FLAG_LOCAL_PRIORS != 0

  /** Le righe che passano [keep], nello stesso ordine. */
  fun select(keep: (row: Int) -> Boolean): V3Rows {
    val rows = (0 until size).filter(keep)
    val f = FloatArray(rows.size * FeatureExtractorV3.COUNT)
    val p = ByteArray(rows.size * WINDOWS)
    val e = ByteArray(rows.size * WINDOWS)
    for ((i, row) in rows.withIndex()) {
      features.copyInto(f, i * FeatureExtractorV3.COUNT, row * FeatureExtractorV3.COUNT, (row + 1) * FeatureExtractorV3.COUNT)
      panel.copyInto(p, i * WINDOWS, row * WINDOWS, (row + 1) * WINDOWS)
      era5.copyInto(e, i * WINDOWS, row * WINDOWS, (row + 1) * WINDOWS)
    }
    return V3Rows(
      locations, f, p, e,
      ShortArray(rows.size) { locationIndex[rows[it]] },
      ByteArray(rows.size) { tier[rows[it]] },
      LongArray(rows.size) { t0[rows[it]] },
      LongArray(rows.size) { issue[rows[it]] },
      ByteArray(rows.size) { flags[rows[it]] },
    )
  }

  /** Quante righe ha un livello. */
  fun count(tier: ContextTier): Int = this.tier.count { it.toInt() == tier.ordinal }

  /** Quante righe di un livello hanno la finestra [window] giudicabile sul pannello, e quante di queste bagnate. */
  fun labelCounts(tier: ContextTier, window: Int): Pair<Int, Int> {
    var judged = 0
    var wet = 0
    for (row in 0 until size) {
      if (this.tier[row].toInt() != tier.ordinal) continue
      val label = label(row, window)
      if (label < 0) continue
      judged++
      if (label == 1) wet++
    }
    return judged to wet
  }

  /**
   * Le colonne costanti (stesso valore su tutte le righe non NaN, o tutte NaN) di un livello: il
   * modello non le puo' usare, e l'addestratore le toglie e le riporta. NaN non e' un valore: una colonna
   * costante con qualche NaN e' costante.
   */
  fun constantColumns(tier: ContextTier): List<Int> {
    val columns = ArrayList<Int>()
    for (column in 0 until FeatureExtractorV3.COUNT) {
      var first = Float.NaN
      var seen = false
      var constant = true
      for (row in 0 until size) {
        if (this.tier[row].toInt() != tier.ordinal) continue
        val value = feature(row, column)
        if (value.isNaN()) continue
        if (!seen) {
          first = value
          seen = true
        } else if (value != first) {
          constant = false
          break
        }
      }
      if (constant) columns += column
    }
    return columns
  }

  companion object {
    const val WINDOWS: Int = 3
    const val FLAG_HISTORY_KNOWN: Int = 1
    const val FLAG_LOCAL_PRIORS: Int = 2
    private const val DAY_MILLIS = 86_400_000L

    fun empty(locations: List<String>): V3Rows = V3Rows(
      locations, FloatArray(0), ByteArray(0), ByteArray(0), ShortArray(0), ByteArray(0), LongArray(0), LongArray(0), ByteArray(0),
    )

    /** Le righe di [parts] una dopo l'altra (tutte con le stesse localita'). */
    fun concat(locations: List<String>, parts: List<V3Rows>): V3Rows {
      require(parts.all { it.locations == locations }) { "le parti non hanno le stesse localita'" }
      val n = parts.sumOf { it.size }
      val f = FloatArray(n * FeatureExtractorV3.COUNT)
      val p = ByteArray(n * WINDOWS)
      val e = ByteArray(n * WINDOWS)
      val loc = ShortArray(n)
      val tier = ByteArray(n)
      val t0 = LongArray(n)
      val issue = LongArray(n)
      val flags = ByteArray(n)
      var at = 0
      for (part in parts) {
        part.features.copyInto(f, at * FeatureExtractorV3.COUNT)
        part.panel.copyInto(p, at * WINDOWS)
        part.era5.copyInto(e, at * WINDOWS)
        part.locationIndex.copyInto(loc, at)
        part.tier.copyInto(tier, at)
        part.t0.copyInto(t0, at)
        part.issue.copyInto(issue, at)
        part.flags.copyInto(flags, at)
        at += part.size
      }
      return V3Rows(locations, f, p, e, loc, tier, t0, issue, flags)
    }
  }
}

/** Il cantiere di un [V3Rows]: si aggiunge una riga alla volta, poi [build]. */
internal class V3RowsWriter(private val locations: List<String>) {
  private var features = FloatArray(1024 * FeatureExtractorV3.COUNT)
  private var panel = ByteArray(1024 * V3Rows.WINDOWS)
  private var era5 = ByteArray(1024 * V3Rows.WINDOWS)
  private var locationIndex = ShortArray(1024)
  private var tier = ByteArray(1024)
  private var t0 = LongArray(1024)
  private var issue = LongArray(1024)
  private var flags = ByteArray(1024)
  var size = 0
    private set

  fun add(
    location: Int,
    tierOrdinal: Int,
    t0Millis: Long,
    issueMillis: Long,
    featureValues: DoubleArray,
    panelLabels: IntArray,
    era5Labels: IntArray,
    flagBits: Int,
  ) {
    if (size == t0.size) grow()
    for (i in 0 until FeatureExtractorV3.COUNT) features[size * FeatureExtractorV3.COUNT + i] = featureValues[i].toFloat()
    for (w in 0 until V3Rows.WINDOWS) {
      panel[size * V3Rows.WINDOWS + w] = panelLabels[w].toByte()
      era5[size * V3Rows.WINDOWS + w] = era5Labels[w].toByte()
    }
    locationIndex[size] = location.toShort()
    tier[size] = tierOrdinal.toByte()
    t0[size] = t0Millis
    issue[size] = issueMillis
    flags[size] = flagBits.toByte()
    size++
  }

  private fun grow() {
    val n = t0.size * 2
    features = features.copyOf(n * FeatureExtractorV3.COUNT)
    panel = panel.copyOf(n * V3Rows.WINDOWS)
    era5 = era5.copyOf(n * V3Rows.WINDOWS)
    locationIndex = locationIndex.copyOf(n)
    tier = tier.copyOf(n)
    t0 = t0.copyOf(n)
    issue = issue.copyOf(n)
    flags = flags.copyOf(n)
  }

  fun build(): V3Rows = V3Rows(
    locations,
    features.copyOf(size * FeatureExtractorV3.COUNT),
    panel.copyOf(size * V3Rows.WINDOWS),
    era5.copyOf(size * V3Rows.WINDOWS),
    locationIndex.copyOf(size),
    tier.copyOf(size),
    t0.copyOf(size),
    issue.copyOf(size),
    flags.copyOf(size),
  )
}
