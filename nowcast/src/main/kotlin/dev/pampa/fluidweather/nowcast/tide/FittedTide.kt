package dev.pampa.fluidweather.nowcast.tide

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * La marea del *tuo* posto: quattro coefficienti armonici (S1 e S2 in seno/coseno, cosi' la fase
 * e' libera) stimati ai minimi quadrati sull'archivio locale. Quando c'e', batte qualsiasi
 * formula globale: impara l'ampiezza e la fase che il microclima, la quota e il continente
 * intorno hanno davvero prodotto.
 */
class FittedTide(
  private val longitude: Double,
  private val a1: Double,
  private val b1: Double,
  private val a2: Double,
  private val b2: Double,
) : TidalModel {

  override val source = TideSource.FITTED
  override val s1AmplitudeHpa = hypot(a1, b1)
  override val s2AmplitudeHpa = hypot(a2, b2)

  override fun tideAt(timestampMillis: Long): Double {
    val angle = 2 * PI * SolarTime.solarHours(timestampMillis, longitude) / 24.0
    return a1 * cos(angle) + b1 * sin(angle) + a2 * cos(2 * angle) + b2 * sin(2 * angle)
  }
}

/**
 * Il fit: rimozione della baseline sinottica, poi minimi quadrati sulle armoniche.
 *
 * Il problema da risolvere e' che la marea (~1 hPa) vive sommersa dalla variazione sinottica
 * (±10 hPa su giorni). La separazione sfrutta le frequenze: la baseline di ogni punto e' la
 * media dei punti entro ±12 ore — una finestra di esattamente un periodo S1 (e due S2), su cui
 * qualsiasi sinusoide a quelle frequenze media a zero. Cio' che resta e' marea + rumore, e li'
 * i minimi quadrati fanno il loro mestiere.
 */
class HarmonicFitter {

  data class Fit(val a1: Double, val b1: Double, val a2: Double, val b2: Double)

  data class Sample(val timestampMillis: Long, val valueHpa: Double)

  fun fit(samples: List<Sample>, longitude: Double): Fit? {
    val residuals = removeBaseline(samples) ?: return null

    // Design: [1, cos wt, sin wt, cos 2wt, sin 2wt] — l'intercetta assorbe la media residua
    // della detrendizzazione invece di lasciarla sporcare le armoniche.
    val ata = Array(5) { DoubleArray(5) }
    val atb = DoubleArray(5)
    for ((timestamp, value) in residuals) {
      val angle = 2 * PI * SolarTime.solarHours(timestamp, longitude) / 24.0
      val row = doubleArrayOf(1.0, cos(angle), sin(angle), cos(2 * angle), sin(2 * angle))
      for (i in 0 until 5) {
        atb[i] += row[i] * value
        for (j in 0 until 5) ata[i][j] += row[i] * row[j]
      }
    }
    val solution = solve(ata, atb) ?: return null
    return Fit(a1 = solution[1], b1 = solution[2], a2 = solution[3], b2 = solution[4])
  }

  /**
   * Ogni punto diventa (valore - media della finestra ±12 h). Punti con finestra povera — meno
   * di [MIN_WINDOW_POINTS] vicini o copertura sotto [MIN_WINDOW_SPAN_MILLIS] — si buttano: una
   * baseline stimata male e' peggio di un punto in meno.
   */
  private fun removeBaseline(samples: List<Sample>): List<Sample>? {
    if (samples.size < MIN_WINDOW_POINTS) return null
    val ordered = samples.sortedBy { it.timestampMillis }
    val result = mutableListOf<Sample>()
    var low = 0
    var high = 0
    for (sample in ordered) {
      while (ordered[low].timestampMillis < sample.timestampMillis - HALF_WINDOW_MILLIS) low++
      while (high < ordered.size &&
        ordered[high].timestampMillis <= sample.timestampMillis + HALF_WINDOW_MILLIS
      ) {
        high++
      }
      val window = ordered.subList(low, high)
      val span = window.last().timestampMillis - window.first().timestampMillis
      if (window.size < MIN_WINDOW_POINTS || span < MIN_WINDOW_SPAN_MILLIS) continue
      val baseline = window.sumOf { it.valueHpa } / window.size
      result += Sample(sample.timestampMillis, sample.valueHpa - baseline)
    }
    return result.ifEmpty { null }
  }

  /** Eliminazione gaussiana con pivot parziale: 5x5, nessuna dipendenza, nessuna magia. */
  private fun solve(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
    val n = vector.size
    val a = Array(n) { matrix[it].copyOf() }
    val b = vector.copyOf()
    for (column in 0 until n) {
      var pivot = column
      for (row in column + 1 until n) {
        if (kotlin.math.abs(a[row][column]) > kotlin.math.abs(a[pivot][column])) pivot = row
      }
      if (kotlin.math.abs(a[pivot][column]) < 1e-12) return null
      if (pivot != column) {
        val tmpRow = a[pivot]; a[pivot] = a[column]; a[column] = tmpRow
        val tmpB = b[pivot]; b[pivot] = b[column]; b[column] = tmpB
      }
      for (row in column + 1 until n) {
        val factor = a[row][column] / a[column][column]
        for (k in column until n) a[row][k] -= factor * a[column][k]
        b[row] -= factor * b[column]
      }
    }
    val x = DoubleArray(n)
    for (row in n - 1 downTo 0) {
      var sum = b[row]
      for (k in row + 1 until n) sum -= a[row][k] * x[k]
      x[row] = sum / a[row][row]
    }
    return x
  }

  private companion object {
    const val HALF_WINDOW_MILLIS = 12 * 60 * 60_000L
    const val MIN_WINDOW_POINTS = 8
    const val MIN_WINDOW_SPAN_MILLIS = 18 * 60 * 60_000L
  }
}
