package dev.pampa.fluidweather.core.ai.radar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lo spostamento fra due fotogrammi, in pixel, con la qualita' del picco di correlazione. */
data class PairMotion(val dx: Double, val dy: Double, val psr: Double, val atSearchEdge: Boolean, val echoFraction: Double)

/** Il moto complessivo: in km/h, con la direzione "da" (meteorologica) e "verso". */
data class Motion(
  val speedKmh: Double,
  val fromDeg: Double,
  val towardDeg: Double,
  val pairsUsed: Int,
  val meanPsr: Double,
  val agreement: Boolean,
  val atSearchEdge: Boolean,
  val dxPxPer10Min: Double,
  val dyPxPer10Min: Double,
) {
  val stationary: Boolean get() = speedKmh < STATIONARY_KMH

  companion object {
    const val STATIONARY_KMH = 5.0
  }
}

/**
 * Block matching fra fotogrammi consecutivi: il pezzo centrale del fotogramma nuovo (64 px, ~20
 * km) si cerca nel precedente entro ±28 px con la cross-correlazione normalizzata a media zero
 * (ZNCC), prima a meta' risoluzione e poi raffinando intorno al migliore. Il rapporto picco/rumore
 * (psr) dice se il picco e' vero. Il campo correlato e' `max(dBZ-5, 0)`: le celle deboli contano
 * poco, gli ignoti niente.
 */
object MotionEstimator {

  const val TEMPLATE = 64
  const val SEARCH = 28
  const val MIN_ECHO_FRACTION = 0.02
  const val RELIABLE_PSR = 3.0

  fun pairMotion(previous: RadarWindow, next: RadarWindow): PairMotion? {
    require(previous.size == next.size)
    val size = next.size
    val half = TEMPLATE / 2
    val t0 = size / 2 - half
    val nextField = weights(next)
    val prevField = weights(previous)
    var echo = 0
    for (y in t0 until t0 + TEMPLATE) for (x in t0 until t0 + TEMPLATE) if (next.at(x, y) >= 10) echo++
    val echoFraction = echo.toDouble() / (TEMPLATE * TEMPLATE)
    if (echoFraction < MIN_ECHO_FRACTION) return null

    // Grossolano: campi dimezzati, ricerca ±14.
    val coarseNext = downsample(nextField, size)
    val coarsePrev = downsample(prevField, size)
    val coarseSize = size / 2
    val coarseHalf = half / 2
    val ct0 = coarseSize / 2 - coarseHalf
    val coarseScores = HashMap<Pair<Int, Int>, Double>()
    var bestCoarse = 0 to 0
    var bestCoarseScore = Double.NEGATIVE_INFINITY
    for (dy in -SEARCH / 2..SEARCH / 2) for (dx in -SEARCH / 2..SEARCH / 2) {
      val score = zncc(coarseNext, coarsePrev, coarseSize, ct0, TEMPLATE / 2, dx, dy)
      coarseScores[dx to dy] = score
      if (score > bestCoarseScore) {
        bestCoarseScore = score
        bestCoarse = dx to dy
      }
    }
    // Fine: ±3 attorno al doppio del migliore grossolano, a piena risoluzione.
    val scores = HashMap<Pair<Int, Int>, Double>()
    var best = bestCoarse.first * 2 to bestCoarse.second * 2
    var bestScore = Double.NEGATIVE_INFINITY
    for (dy in best.second - 3..best.second + 3) for (dx in best.first - 3..best.first + 3) {
      if (abs(dx) > SEARCH || abs(dy) > SEARCH) continue
      val score = zncc(nextField, prevField, size, t0, TEMPLATE, dx, dy)
      scores[dx to dy] = score
      if (score > bestScore) {
        bestScore = score
        best = dx to dy
      }
    }
    if (bestScore.isNaN() || bestScore == Double.NEGATIVE_INFINITY) return null
    // Sub-pixel: parabola sui tre punteggi attorno al massimo, per asse.
    val subX = parabolic(scores[best.first - 1 to best.second], bestScore, scores[best.first + 1 to best.second])
    val subY = parabolic(scores[best.first to best.second - 1], bestScore, scores[best.first to best.second + 1])
    // Qualita': il picco rispetto alla distribuzione grossolana (piu' campioni, piu' onesta).
    val all = coarseScores.values.filter { !it.isNaN() }
    val mean = all.average()
    val std = sqrt(all.sumOf { (it - mean) * (it - mean) } / max(1, all.size - 1))
    val psr = if (std > 1e-9) (bestCoarseScore - mean) / std else 0.0
    return PairMotion(
      dx = best.first + subX,
      dy = best.second + subY,
      psr = psr,
      atSearchEdge = abs(best.first) >= SEARCH - 1 || abs(best.second) >= SEARCH - 1,
      echoFraction = echoFraction,
    )
  }

  /** Media vettoriale pesata (coppie piu' recenti e piu' nitide pesano di piu'), con l'accordo. */
  fun combine(pairs: List<PairMotion>, metersPerPixel: Double, minutesPerPair: Double = 10.0): Motion? {
    val reliable = pairs.filter { it.psr >= RELIABLE_PSR }
    val used = if (reliable.isNotEmpty()) reliable else pairs
    if (used.isEmpty()) return null
    var sumX = 0.0
    var sumY = 0.0
    var sumW = 0.0
    used.forEachIndexed { index, pair ->
      val w = (index + 1) * max(pair.psr, 0.5)
      sumX += pair.dx * w
      sumY += pair.dy * w
      sumW += w
    }
    val dx = sumX / sumW
    val dy = sumY / sumW
    val speedKmh = hypot(dx, dy) * metersPerPixel / (minutesPerPair * 60.0) * 3.6
    val towardDeg = (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
    val fromDeg = (towardDeg + 180.0) % 360.0
    val meanDir = towardDeg
    val agreement = used.size == 1 || used.all { pair ->
      val dir = (Math.toDegrees(atan2(pair.dx, -pair.dy)) + 360.0) % 360.0
      val diff = abs(((dir - meanDir + 540.0) % 360.0) - 180.0)
      val speed = hypot(pair.dx, pair.dy)
      val mean = hypot(dx, dy)
      diff <= 45.0 && (mean < 1.0 || speed / mean in 1.0 / 1.6..1.6)
    }
    return Motion(
      speedKmh = speedKmh,
      fromDeg = fromDeg,
      towardDeg = towardDeg,
      pairsUsed = used.size,
      meanPsr = used.map { it.psr }.average(),
      agreement = agreement,
      atSearchEdge = used.any { it.atSearchEdge },
      dxPxPer10Min = dx / minutesPerPair * 10.0,
      dyPxPer10Min = dy / minutesPerPair * 10.0,
    )
  }

  private fun weights(window: RadarWindow): DoubleArray = DoubleArray(window.size * window.size) { i ->
    val v = window.dbz[i]
    if (v < 0) 0.0 else max(v - 5, 0).toDouble()
  }

  private fun downsample(field: DoubleArray, size: Int): DoubleArray {
    val half = size / 2
    val out = DoubleArray(half * half)
    for (y in 0 until half) for (x in 0 until half) {
      val i = (2 * y) * size + 2 * x
      out[y * half + x] = (field[i] + field[i + 1] + field[i + size] + field[i + size + 1]) / 4.0
    }
    return out
  }

  /** ZNCC del template (angolo t0, lato n) del campo `next` contro `prev` spostato di (dx, dy). */
  private fun zncc(next: DoubleArray, prev: DoubleArray, size: Int, t0: Int, n: Int, dx: Int, dy: Int): Double {
    var sumA = 0.0
    var sumB = 0.0
    var count = 0
    for (y in t0 until t0 + n) for (x in t0 until t0 + n) {
      val px = x - dx
      val py = y - dy
      if (px < 0 || py < 0 || px >= size || py >= size) continue
      sumA += next[y * size + x]
      sumB += prev[py * size + px]
      count++
    }
    if (count < n * n / 2) return Double.NEGATIVE_INFINITY
    val meanA = sumA / count
    val meanB = sumB / count
    var num = 0.0
    var denA = 0.0
    var denB = 0.0
    for (y in t0 until t0 + n) for (x in t0 until t0 + n) {
      val px = x - dx
      val py = y - dy
      if (px < 0 || py < 0 || px >= size || py >= size) continue
      val a = next[y * size + x] - meanA
      val b = prev[py * size + px] - meanB
      num += a * b
      denA += a * a
      denB += b * b
    }
    val den = sqrt(denA * denB)
    return if (den < 1e-9) 0.0 else num / den
  }

  private fun parabolic(left: Double?, centre: Double, right: Double?): Double {
    if (left == null || right == null || left.isNaN() || right.isNaN() || left == Double.NEGATIVE_INFINITY || right == Double.NEGATIVE_INFINITY) return 0.0
    val denominator = left - 2 * centre + right
    if (abs(denominator) < 1e-9) return 0.0
    return (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
  }

  /** Utility per i test e per l'ETA: ruota un vettore (dx, dy) in pixel di un angolo in gradi. */
  fun rotate(dx: Double, dy: Double, degrees: Double): Pair<Double, Double> {
    val r = Math.toRadians(degrees)
    return (dx * cos(r) - dy * sin(r)) to (dx * sin(r) + dy * cos(r))
  }

  fun bearingDeg(dxPx: Double, dyPx: Double): Int = ((Math.toDegrees(atan2(dxPx, -dyPx)) + 360.0) % 360.0).roundToInt() % 360
}
