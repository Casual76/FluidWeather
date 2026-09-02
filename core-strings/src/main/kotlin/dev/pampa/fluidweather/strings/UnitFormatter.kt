package dev.pampa.fluidweather.strings

import android.content.res.Resources
import dev.pampa.fluidweather.core.model.DistanceUnit
import dev.pampa.fluidweather.core.model.PrecipitationUnit
import dev.pampa.fluidweather.core.model.PressureUnit
import dev.pampa.fluidweather.core.model.TemperatureUnit
import dev.pampa.fluidweather.core.model.UnitMath
import dev.pampa.fluidweather.core.model.UnitPreferences
import dev.pampa.fluidweather.core.model.WindUnit
import java.util.Locale

/**
 * Il solo posto in cui un numero metrico diventa un testo con la sua unita'. Riceve gradi,
 * km/h, hPa, mm e km (il dominio) e risponde nella lingua e nelle unita' dell'utente, coi
 * decimali che quell'unita' merita. Tutto cio' che si vede passa di qui; niente di cio' che
 * si calcola.
 */
class UnitFormatter(
  private val resources: Resources,
  val units: UnitPreferences,
  val locale: Locale = Locale.getDefault(),
) {

  // ------------------------------------------------------------------ temperatura

  /** "21°": il grado senza lettera, per i numeri grandi della testata e delle tabelle. */
  fun degrees(celsius: Double, decimals: Int = 0): String = num(UnitMath.temperature(celsius, units.temperature), decimals) + "°"

  /** "21 °C" per dove l'unita' va detta per esteso. */
  fun temperature(celsius: Double, decimals: Int = 0): String =
    num(UnitMath.temperature(celsius, units.temperature), decimals) + " " + temperatureSymbol()

  fun temperatureSymbol(): String = resources.getString(
    when (units.temperature) {
      TemperatureUnit.CELSIUS -> R.string.unit_celsius
      TemperatureUnit.FAHRENHEIT -> R.string.unit_fahrenheit
    },
  )

  /** Un'ampiezza di temperatura senza segno (un errore medio, un'escursione): "1,5°". */
  fun temperatureSpan(deltaCelsius: Double, decimals: Int = 1): String =
    num(UnitMath.temperatureDelta(deltaCelsius, units.temperature), decimals) + "°"

  /** Una differenza di temperatura col segno: "+1,5°". */
  fun degreesDelta(deltaCelsius: Double, decimals: Int = 1): String =
    signed(UnitMath.temperatureDelta(deltaCelsius, units.temperature), decimals) + "°"

  // ------------------------------------------------------------------------ vento

  fun wind(kmh: Double, decimals: Int = 0): String = windValue(kmh, decimals) + " " + windSymbol()

  fun windValue(kmh: Double, decimals: Int = 0): String =
    num(UnitMath.wind(kmh, units.wind), UnitMath.windDecimals(units.wind, decimals))

  fun windSeries(kmh: List<Double>): List<Double> = kmh.map { UnitMath.wind(it, units.wind) }

  fun windSymbol(): String = resources.getString(
    when (units.wind) {
      WindUnit.KMH -> R.string.unit_kmh
      WindUnit.MS -> R.string.unit_ms
      WindUnit.MPH -> R.string.unit_mph
      WindUnit.KNOTS -> R.string.unit_knots
      WindUnit.BEAUFORT -> R.string.unit_beaufort
    },
  )

  // -------------------------------------------------------------------- pressione

  fun pressure(hPa: Double, decimals: Int = 0): String = pressureValue(hPa, decimals) + " " + pressureSymbol()

  fun pressureValue(hPa: Double, decimals: Int = 0): String =
    num(UnitMath.pressure(hPa, units.pressure), UnitMath.pressureDecimals(units.pressure, decimals))

  fun pressureSymbol(): String = resources.getString(
    when (units.pressure) {
      PressureUnit.HPA -> R.string.unit_hpa
      PressureUnit.MBAR -> R.string.unit_mbar
      PressureUnit.MMHG -> R.string.unit_mmhg
      PressureUnit.INHG -> R.string.unit_inhg
    },
  )

  /** Una differenza di pressione col segno: "+1,2 hPa". */
  fun pressureDelta(deltaHpa: Double, decimals: Int = 1): String = pressureDeltaValue(deltaHpa, decimals) + " " + pressureSymbol()

  fun pressureDeltaValue(deltaHpa: Double, decimals: Int = 1): String =
    signed(UnitMath.pressure(deltaHpa, units.pressure), UnitMath.pressureDecimals(units.pressure, decimals))

  /** "±0,3 hPa": l'incertezza. */
  fun pressureSigma(sigmaHpa: Double, decimals: Int = 1): String =
    "±" + num(UnitMath.pressure(sigmaHpa, units.pressure), UnitMath.pressureDecimals(units.pressure, decimals)) + " " + pressureSymbol()

  /** Una tendenza oraria col segno: "−1,2 hPa/h". */
  fun pressureRate(hPaPerHour: Double, decimals: Int = 1): String = pressureDeltaValue(hPaPerHour, decimals) + " " + pressureRateSymbol()

  fun pressureRateSymbol(): String = pressureSymbol() + "/h"

  /** I valori di una serie convertiti per un grafico, nell'unita' scelta. */
  fun pressureSeries(hPa: List<Double>): List<Double> = hPa.map { UnitMath.pressure(it, units.pressure) }

  fun pressureChartDecimals(): Int = UnitMath.pressureDecimals(units.pressure, 0)

  // ------------------------------------------------------------------- precipitazione

  fun precipitation(mm: Double, decimals: Int = 1): String = precipitationValue(mm, decimals) + " " + precipitationSymbol()

  fun precipitationValue(mm: Double, decimals: Int = 1): String =
    num(UnitMath.precipitation(mm, units.precipitation), UnitMath.precipitationDecimals(units.precipitation, decimals))

  fun precipitationSymbol(): String = resources.getString(
    when (units.precipitation) {
      PrecipitationUnit.MM -> R.string.unit_mm
      PrecipitationUnit.INCH -> R.string.unit_inch
    },
  )

  fun precipitationSeries(mm: List<Double>): List<Double> = mm.map { UnitMath.precipitation(it, units.precipitation) }

  // ------------------------------------------------------------------------ distanze

  fun distance(km: Double, decimals: Int = 0): String = distanceValue(km, decimals) + " " + distanceSymbol()

  fun distanceValue(km: Double, decimals: Int = 0): String = num(UnitMath.distance(km, units.distance), decimals)

  fun distanceSeries(km: List<Double>): List<Double> = km.map { UnitMath.distance(it, units.distance) }

  /** "384.400 km": per i numeri grandi, con il separatore delle migliaia del locale. */
  fun distanceGrouped(km: Double): String =
    String.format(locale, "%,.0f", UnitMath.distance(km, units.distance)) + " " + distanceSymbol()

  fun distanceSymbol(): String = resources.getString(
    when (units.distance) {
      DistanceUnit.KM -> R.string.unit_km
      DistanceUnit.MILES -> R.string.unit_mi
    },
  )

  /** La visibilita' arriva in metri dai provider. */
  fun visibilityMeters(meters: Double, decimals: Int = 0): String = distance(meters / 1000.0, decimals)

  // ---------------------------------------------------------------------- numeri

  fun num(value: Double, decimals: Int): String = String.format(locale, "%.${decimals}f", value)

  fun signed(value: Double, decimals: Int): String {
    val text = String.format(locale, "%+.${decimals}f", value)
    // Il meno tipografico: il trattino corto sparisce accanto ai numeri grandi.
    return if (text.startsWith("-")) "−" + text.substring(1) else text
  }

  fun percent(fraction01: Double): String = "${(fraction01 * 100).toInt()}%"
}
