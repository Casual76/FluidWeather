package dev.pampa.fluidweather.nowcast.cleaning

import kotlin.math.max
import kotlin.math.sqrt

/** Una misura pronta per il filtro: valore, istante, e quanto fidarsi di lei. */
data class Measurement(
  val timestampMillis: Long,
  val valueHpa: Double,
  val noiseSigmaHpa: Double,
)

/** Lo stato stimato a un istante: livello e tendenza, ciascuno con la sua incertezza. */
data class FilteredPoint(
  val timestampMillis: Long,
  val levelHpa: Double,
  val trendHpaPerHour: Double,
  val levelSigmaHpa: Double,
  val trendSigmaHpaPerHour: Double,
)

/**
 * Filtro di Kalman a due stati — livello (hPa) e tendenza (hPa/h) — su campionamento irregolare.
 *
 * E' il pezzo che trasforma una nuvola di punti rumorosi in *un* numero con la sua incertezza,
 * ed e' il motivo per cui la pipeline puo' dire "sta scendendo di 0,8 ± 0,2 hPa/h" invece di
 * mostrare una derivata che balla. Il campionamento vero e' tutto tranne che regolare: raffiche
 * a 1 Hz, giri ogni 15-30 minuti, buchi di ore in Doze — per questo ogni passo ricostruisce le
 * matrici col suo dt, e il rumore di processo cresce con il tempo trascorso (modello
 * continuous white-noise acceleration): dopo un buco lungo il filtro sa di non sapere.
 */
class PressureKalmanFilter(
  /**
   * Quanto in fretta la tendenza puo' cambiare per cause vere: un fronte tipico passa da quiete
   * a ~1 hPa/h nel giro di due-tre ore, cioe' un'accelerazione di ~0,3-0,5 hPa/h². Piu' alto =
   * filtro piu' nervoso che insegue anche il rumore; piu' basso = fronte visto in ritardo.
   * Sara' il banco di prova (fase 4) a difendere o correggere questo numero.
   */
  accelerationSigmaHpaPerHour2: Double = 0.5,
  /** Il pavimento del rumore di misura, per i punti senza spread proprio (letture secche). */
  private val sensorNoiseHpa: Double = 0.05,
) {

  private val processNoise = accelerationSigmaHpaPerHour2 * accelerationSigmaHpaPerHour2

  fun filter(measurements: List<Measurement>): List<FilteredPoint> {
    if (measurements.isEmpty()) return emptyList()
    val ordered = measurements.sortedBy { it.timestampMillis }

    // Inizializzazione: il primo punto e' la miglior stima del livello; della tendenza non si sa
    // nulla, e la varianza iniziale (2 hPa/h)^2 lo dice — il filtro convergera' dai dati.
    var level = ordered.first().valueHpa
    var trend = 0.0
    var p00 = ordered.first().varianceFloor()
    var p01 = 0.0
    var p11 = INITIAL_TREND_SIGMA * INITIAL_TREND_SIGMA
    var lastMillis = ordered.first().timestampMillis

    val output = ArrayList<FilteredPoint>(ordered.size)
    output += FilteredPoint(lastMillis, level, trend, sqrt(p00), sqrt(p11))

    for (measurement in ordered.drop(1)) {
      val dt = (measurement.timestampMillis - lastMillis) / 3_600_000.0
      if (dt <= 0.0) continue // stesso istante: il punto non aggiunge dinamica
      lastMillis = measurement.timestampMillis

      // Predizione: x' = F x, P' = F P F^T + Q, con F = [[1, dt], [0, 1]] e
      // Q = q * [[dt^3/3, dt^2/2], [dt^2/2, dt]] (accelerazione bianca continua).
      level += trend * dt
      val q00 = processNoise * dt * dt * dt / 3.0
      val q01 = processNoise * dt * dt / 2.0
      val q11 = processNoise * dt
      val np00 = p00 + dt * (p01 + p01) + dt * dt * p11 + q00
      val np01 = p01 + dt * p11 + q01
      val np11 = p11 + q11
      p00 = np00
      p01 = np01
      p11 = np11

      // Aggiornamento con la misura z e il suo rumore R.
      val r = measurement.varianceFloor()
      val innovation = measurement.valueHpa - level
      val s = p00 + r
      val k0 = p00 / s
      val k1 = p01 / s
      level += k0 * innovation
      trend += k1 * innovation
      val up00 = (1 - k0) * p00
      val up01 = (1 - k0) * p01
      val up11 = p11 - k1 * p01
      p00 = up00
      p01 = up01
      p11 = up11

      output += FilteredPoint(lastMillis, level, trend, sqrt(p00), sqrt(p11))
    }
    return output
  }

  private fun Measurement.varianceFloor(): Double {
    val sigma = max(noiseSigmaHpa, sensorNoiseHpa)
    return sigma * sigma
  }

  private companion object {
    const val INITIAL_TREND_SIGMA = 2.0
  }
}
