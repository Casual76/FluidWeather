package dev.pampa.fluidweather.core.ai.radar

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Quando la pioggia arriva sul punto, o quando smette, estrapolando il moto sul fotogramma di adesso. */
data class Eta(
  val arrivesInMin: Int?,
  val expectedDbz: Int?,
  val endsInMin: Int?,
  val horizonMin: Int,
)

/** L'eco piu' vicino al punto nel riquadro di campionamento. */
data class NearestEcho(val km: Double, val bearingDeg: Int, val maxDbz: Int)

/**
 * Retro-traiettoria semi-lagrangiana: fra t minuti sopra il punto ci sara' cio' che ora sta a
 * (centro − v·t). Si campiona il fotogramma di adesso a passi di 5 minuti finche' la traiettoria
 * esce dalla finestra: e' l'orizzonte, ed e' onesto dirlo.
 */
object EtaEstimator {

  const val MAX_HORIZON_MIN = 90
  const val STEP_MIN = 5
  const val RAIN_DBZ = 10

  fun estimate(now: RadarWindow, motion: Motion, rainingNow: Boolean): Eta {
    val c = now.center
    val stepX = motion.dxPxPer10Min / 10.0
    val stepY = motion.dyPxPer10Min / 10.0
    val speedPxPerMin = hypot(stepX, stepY)
    val horizon = if (speedPxPerMin < 1e-6) MAX_HORIZON_MIN else min(MAX_HORIZON_MIN, ((now.size / 2 - 2) / speedPxPerMin).toInt())
    var arrives: Int? = null
    var expected: Int? = null
    var ends: Int? = null
    var t = STEP_MIN
    while (t <= horizon) {
      val sx = (c - stepX * t).roundToInt()
      val sy = (c - stepY * t).roundToInt()
      if (sx !in 1 until now.size - 1 || sy !in 1 until now.size - 1) break
      val v = now.maxAround(sx, sy, 1)
      if (!rainingNow) {
        if (arrives == null && v >= RAIN_DBZ) {
          arrives = t
          expected = v
        } else if (arrives != null && t <= arrives + 30 && v > (expected ?: 0)) {
          expected = v
        }
      } else if (ends == null && v in 0 until RAIN_DBZ) {
        ends = t
      }
      t += STEP_MIN
    }
    return Eta(arrivesInMin = arrives, expectedDbz = expected, endsInMin = ends, horizonMin = horizon)
  }

  /** L'eco ≥ 10 dBZ piu' vicino al centro nel riquadro di lato `box`, con rilevamento e intensita'. */
  fun nearest(now: RadarWindow, box: Int, metersPerPixel: Double): NearestEcho? {
    val c = now.center
    val half = box / 2
    var bestDistance = Double.MAX_VALUE
    var bestX = 0
    var bestY = 0
    for (y in c - half until c + half) for (x in c - half until c + half) {
      if (now.at(x, y) < RAIN_DBZ) continue
      val d = hypot((x - c).toDouble(), (y - c).toDouble())
      if (d < bestDistance) {
        bestDistance = d
        bestX = x
        bestY = y
      }
    }
    if (bestDistance == Double.MAX_VALUE) return null
    return NearestEcho(
      km = bestDistance * metersPerPixel / 1000.0,
      bearingDeg = MotionEstimator.bearingDeg((bestX - c).toDouble(), (bestY - c).toDouble()),
      maxDbz = now.maxAround(bestX, bestY, 2),
    )
  }

  /** Quanta parte del riquadro piove e quanto forte al massimo. */
  fun coverage(now: RadarWindow, box: Int): Pair<Double, Int> {
    val c = now.center
    val half = box / 2
    var raining = 0
    var known = 0
    var maxDbz = 0
    for (y in c - half until c + half) for (x in c - half until c + half) {
      val v = now.at(x, y)
      if (v < 0) continue
      known++
      if (v >= RAIN_DBZ) raining++
      if (v > maxDbz) maxDbz = v
    }
    return (if (known == 0) 0.0 else raining.toDouble() / known) to maxDbz
  }

  /** Il dBZ medio (sugli eco) nel riquadro: la serie da cui si legge la tendenza. */
  fun meanDbz(window: RadarWindow, box: Int): Double {
    val c = window.center
    val half = box / 2
    var sum = 0.0
    var count = 0
    for (y in c - half until c + half) for (x in c - half until c + half) {
      val v = window.at(x, y)
      if (v < 0) continue
      sum += v
      count++
    }
    return if (count == 0) 0.0 else sum / count
  }

  /** Pendenza ai minimi quadrati di una serie (valore per passo). */
  fun slope(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val n = values.size
    val meanX = (n - 1) / 2.0
    val meanY = values.average()
    var num = 0.0
    var den = 0.0
    values.forEachIndexed { i, v ->
      num += (i - meanX) * (v - meanY)
      den += (i - meanX) * (i - meanX)
    }
    return if (den == 0.0) 0.0 else num / den
  }
}
