package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TruthKind
import dev.pampa.fluidweather.testbench.tiers.TierHalf
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import kotlin.math.sqrt

/**
 * Medie e deviazioni delle quarantadue colonne per un livello, sulle righe di addestramento di quel
 * livello: il contratto di standardizzazione della sua tabella (una per livello, come nel telefono).
 *
 * Una colonna costante (o tutta NaN) nelle righe del livello e' **degenere**: media al suo valore,
 * deviazione uno, e fuori dal modello (vedi `Standardization` del v2: una feature che a banco non varia
 * e sul telefono si', divisa per una deviazione minuscola, diventerebbe il fattore piu' forte di tutti).
 */
class TierStandardization(
  val tier: ContextTier,
  val means: DoubleArray,
  val sds: DoubleArray,
  val degenerate: Set<Int>,
) {

  fun standardize(column: Int, value: Float): Double =
    if (value.isNaN()) 0.0 else (value.toDouble() - means[column]) / sds[column]

  companion object {
    /** Sotto questa deviazione una colonna e' una costante col rumore numerico attorno. */
    const val DEGENERATE_SD: Double = 1e-3

    fun fit(rows: V3Rows, tier: ContextTier): TierStandardization {
      val count = FeatureExtractorV3.COUNT
      val sums = DoubleArray(count)
      val squares = DoubleArray(count)
      val n = IntArray(count)
      for (row in 0 until rows.size) {
        if (rows.tier[row].toInt() != tier.ordinal) continue
        for (c in 0 until count) {
          val v = rows.feature(row, c)
          if (v.isNaN()) continue
          val d = v.toDouble()
          sums[c] += d
          squares[c] += d * d
          n[c]++
        }
      }
      val means = DoubleArray(count)
      val sds = DoubleArray(count)
      val degenerate = LinkedHashSet<Int>()
      val constant = rows.constantColumns(tier).toSet()
      for (c in 0 until count) {
        val mean = if (n[c] == 0) 0.0 else sums[c] / n[c]
        val variance = if (n[c] < 2) 0.0 else ((squares[c] - n[c] * mean * mean) / (n[c] - 1)).coerceAtLeast(0.0)
        val sd = sqrt(variance)
        means[c] = mean
        if (n[c] == 0 || c in constant || sd < DEGENERATE_SD) {
          degenerate += c
          sds[c] = 1.0
        } else {
          sds[c] = sd
        }
      }
      return TierStandardization(tier, means, sds, degenerate)
    }
  }
}

/**
 * Le righe di un (livello, finestra) pronte per un addestratore: quelle giudicabili dal pannello, con le
 * sole colonne che il modello puo' vedere, i pesi delle localita' fuori Europa, i blocchi (localita',
 * giorno) del bootstrap e — per le righe di VALIDATION — la meta' A/B e il gruppo.
 *
 * [raw] sono i valori grezzi (Float, NaN dove mancano: servono agli alberi), [z] quelli standardizzati
 * con NaN a zero (servono alla logistica); entrambi `riga x colonne`, righe una dopo l'altra.
 */
class CellDesign(
  val tier: ContextTier,
  val window: Int,
  /** Le colonne (indici 0..41) che il modello usa, in ordine crescente. */
  val columns: IntArray,
  val n: Int,
  val raw: FloatArray,
  val z: DoubleArray,
  val y: ByteArray,
  /** La localita' della riga (indice in [V3Rows.locations]) e se e' europea. */
  val location: ShortArray,
  val europe: BooleanArray,
  /** Il blocco del bootstrap: (localita', giorno UTC dell'ancora), numerato da zero. */
  val block: IntArray,
  val blockCount: Int,
  /** Solo per VALIDATION: la meta' della riga (settimana ISO pari/dispari). */
  val half: Array<TierHalf>?,
  /** Il t0 della riga: per ricostruire il passo di tre ore del gate dalle righe orarie. */
  val t0: LongArray,
  /** Le colonne grezze di tutte le 42 feature della riga (per le colonne di baseline e le ancore). */
  private val allFeatures: (row: Int, column: Int) -> Float,
) {
  val d: Int get() = columns.size

  /** Il valore grezzo di una colonna qualsiasi (anche fuori da [columns]). */
  fun feature(row: Int, column: Int): Float = allFeatures(row, column)

  /** Il vettore di quarantadue feature di una riga, in Double: per i modelli esportati. */
  fun fullRow(row: Int): DoubleArray = DoubleArray(FeatureExtractorV3.COUNT) { allFeatures(row, it).toDouble() }

  /** I pesi per riga: 1 in Europa, [nonEuropeWeight] fuori; normalizzati a somma = numero di righe col peso positivo. */
  fun weights(nonEuropeWeight: Double, multiplicity: DoubleArray? = null): DoubleArray {
    val w = DoubleArray(n) { (if (europe[it]) 1.0 else nonEuropeWeight) * (multiplicity?.get(it) ?: 1.0) }
    val positive = w.count { it > 0 }
    val sum = w.sum()
    if (sum > 0) for (i in 0 until n) w[i] *= positive / sum
    return w
  }

  /** L'ancora di una riga (valore grezzo della colonna [anchor]); 0 se non c'e' ancora. NaN -> [nanValue]. */
  fun offsets(anchor: Int?, nanValue: Double = 0.0): DoubleArray = DoubleArray(n) { row ->
    if (anchor == null) 0.0 else allFeatures(row, anchor).toDouble().let { if (it.isNaN()) nanValue else it }
  }

  companion object {

    /** Le righe di [tier] con la finestra [window] giudicabile sul pannello, sulle colonne [columns]. */
    fun of(
      rows: V3Rows,
      tier: ContextTier,
      window: Int,
      columns: IntArray,
      standardization: TierStandardization,
      withHalves: Boolean,
    ): CellDesign {
      val selected = (0 until rows.size).filter { rows.tier[it].toInt() == tier.ordinal && rows.label(it, window, TruthKind.PANEL) >= 0 }
      val n = selected.size
      val d = columns.size
      val raw = FloatArray(n * d)
      val z = DoubleArray(n * d)
      val y = ByteArray(n)
      val location = ShortArray(n)
      val europe = BooleanArray(n)
      val block = IntArray(n)
      val t0 = LongArray(n)
      val blocks = HashMap<Long, Int>()
      val europeIndex = rows.locations.map { it in TierGroups.EUROPA }
      for ((i, row) in selected.withIndex()) {
        for ((j, c) in columns.withIndex()) {
          val v = rows.feature(row, c)
          raw[i * d + j] = v
          z[i * d + j] = standardization.standardize(c, v)
        }
        y[i] = rows.label(row, window, TruthKind.PANEL).toByte()
        location[i] = rows.locationIndex[row]
        europe[i] = europeIndex[rows.locationIndex[row].toInt()]
        val key = rows.locationIndex[row].toLong() * 1_000_000L + rows.epochDay(row)
        block[i] = blocks.getOrPut(key) { blocks.size }
        t0[i] = rows.t0[row]
      }
      val half = if (withHalves) Array(n) { TierPeriods.half(t0[it]) } else null
      val indexOf = selected.toIntArray()
      return CellDesign(
        tier, window, columns, n, raw, z, y, location, europe, block, blocks.size, half, t0,
      ) { row, column -> rows.feature(indexOf[row], column) }
    }
  }
}
