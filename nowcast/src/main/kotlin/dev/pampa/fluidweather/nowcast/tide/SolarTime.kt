package dev.pampa.fluidweather.nowcast.tide

import java.time.Instant
import java.time.ZoneOffset

/**
 * Tempo solare medio locale: e' il metronomo delle maree atmosferiche, che sono termiche e
 * inseguono il sole, non i fusi orari. La conversione e' UTC + longitudine/15.
 *
 * Si ignora l'equazione del tempo (la differenza fra sole vero e sole medio, ±15 minuti
 * nell'anno): sposta la fase di S2 di al piu' 7 gradi, molto sotto l'incertezza dei priori —
 * e il fit locale la assorbe da solo, perche' impara la fase dai dati.
 */
internal object SolarTime {

  private const val MILLIS_PER_DAY = 86_400_000L
  private const val MILLIS_PER_HOUR = 3_600_000.0

  /** Ora solare media locale in [0, 24). */
  fun solarHours(timestampMillis: Long, longitudeDeg: Double): Double {
    val utcHours = Math.floorMod(timestampMillis, MILLIS_PER_DAY) / MILLIS_PER_HOUR
    return ((utcHours + longitudeDeg / 15.0) % 24.0 + 24.0) % 24.0
  }

  /** Giorno dell'anno (UTC): basta per le modulazioni stagionali, che sono lente. */
  fun dayOfYear(timestampMillis: Long): Int =
    Instant.ofEpochMilli(timestampMillis).atOffset(ZoneOffset.UTC).dayOfYear
}
