package dev.pampa.fluidweather.feature.radar

import kotlin.math.abs

/**
 * La matematica della barra del tempo del radar: la distanza di un fotogramma da "adesso"
 * (l'ultimo fotogramma del passato), il ritmo dell'animazione, il fotogramma sotto il dito.
 * Le parole le mette la schermata, nella lingua del telefono (fase 17).
 */
object RadarTimeline {

  /** "adesso", "−1 h 40 min", "+20 min": il tempo del fotogramma rispetto a quello corrente, scomposto. */
  data class Offset(val minutes: Int) {
    val isNow: Boolean get() = minutes == 0

    /** Il meno tipografico, come su tutta l'app. */
    val sign: String get() = if (minutes < 0) "−" else "+"

    val hours: Int get() = abs(minutes) / 60

    val rest: Int get() = abs(minutes) % 60
  }

  fun offset(frameMillis: Long, nowFrameMillis: Long): Offset =
    Offset(((frameMillis - nowFrameMillis) / 60_000L).toInt())

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
