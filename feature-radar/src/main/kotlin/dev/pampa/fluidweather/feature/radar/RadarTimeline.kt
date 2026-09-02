package dev.pampa.fluidweather.feature.radar

import kotlin.math.abs

/**
 * La matematica della barra del tempo del radar: etichette relative ad "adesso" (l'ultimo
 * fotogramma del passato), il ritmo dell'animazione, il fotogramma sotto il dito.
 */
object RadarTimeline {

  /** "adesso", "−1 h 40 min", "+20 min": il tempo del fotogramma rispetto a quello corrente. */
  fun label(frameMillis: Long, nowFrameMillis: Long): String {
    val minutes = ((frameMillis - nowFrameMillis) / 60_000L).toInt()
    if (minutes == 0) return "adesso"
    val sign = if (minutes < 0) "−" else "+"
    val magnitude = abs(minutes)
    val hours = magnitude / 60
    val rest = magnitude % 60
    return when {
      hours == 0 -> "$sign$rest min"
      rest == 0 -> "$sign$hours h"
      else -> "$sign$hours h $rest min"
    }
  }

  /** L'ultimo fotogramma resta piu' a lungo: e' il presente, e l'occhio vuole leggerlo. */
  fun frameDelayMillis(index: Int, count: Int): Long = if (index >= count - 1) 1_500L else 450L

  /** Il fotogramma sotto una frazione [0,1] della barra. */
  fun indexForFraction(fraction: Float, count: Int): Int {
    if (count <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * (count - 1) + 0.5f).toInt().coerceIn(0, count - 1)
  }

  fun fractionForIndex(index: Int, count: Int): Float =
    if (count <= 1) 0f else index.coerceIn(0, count - 1).toFloat() / (count - 1)
}
