package dev.pampa.fluidweather.nowcast.cleaning

import kotlin.math.pow

/**
 * Riduzione della pressione di stazione al livello del mare.
 *
 * E' *il* punto in cui i barometri da smartphone sbagliano (Mass & Madaus): non il sensore, ma
 * la riduzione fatta con l'atmosfera standard quando l'aria vera e' piu' calda o piu' fredda.
 * Qui la formula ipsometrica prende la temperatura reale; il default 15 °C e' l'atmosfera
 * standard, dichiarato come ripiego finche' il livello provider (fase 6) non porta quella vera.
 *
 *     p0 = p * (1 - L*h / (T + L*h))^(-g*M/(R*L))
 *
 * con L = 0,0065 K/m (gradiente standard), T in kelvin alla stazione, e l'esponente
 * g*M/(R*L) = 9,80665 * 0,0289644 / (8,31447 * 0,0065) = 5,257.
 */
object SeaLevel {

  const val STANDARD_TEMPERATURE_CELSIUS: Double = 15.0

  private const val LAPSE_RATE_K_PER_M = 0.0065
  private const val EXPONENT = 5.257

  fun reduce(
    stationPressureHpa: Double,
    altitudeMeters: Double,
    temperatureCelsius: Double = STANDARD_TEMPERATURE_CELSIUS,
  ): Double {
    if (altitudeMeters == 0.0) return stationPressureHpa
    val temperatureKelvin = temperatureCelsius + 273.15
    val lift = LAPSE_RATE_K_PER_M * altitudeMeters
    val base = 1.0 - lift / (temperatureKelvin + lift)
    return stationPressureHpa * base.pow(-EXPONENT)
  }
}
