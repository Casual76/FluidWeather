package dev.pampa.fluidweather.core.model

/*
 * Le unita' di misura (fase 17): dedotte dal locale, sovrascrivibili una per una. Il dominio
 * resta metrico (°C, km/h, hPa, mm, km): la conversione avviene solo quando un numero viene
 * mostrato, mai quando viene calcolato o archiviato. Cosi' un cambio di unita' non tocca un
 * solo byte di storia.
 */

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

enum class WindUnit { KMH, MS, MPH, KNOTS, BEAUFORT }

enum class PressureUnit { HPA, MBAR, MMHG, INHG }

enum class PrecipitationUnit { MM, INCH }

enum class DistanceUnit { KM, MILES }

/** Le unita' effettive con cui l'app parla. */
data class UnitPreferences(
  val temperature: TemperatureUnit,
  val wind: WindUnit,
  val pressure: PressureUnit,
  val precipitation: PrecipitationUnit,
  val distance: DistanceUnit,
) {
  companion object {
    val METRIC = UnitPreferences(TemperatureUnit.CELSIUS, WindUnit.KMH, PressureUnit.HPA, PrecipitationUnit.MM, DistanceUnit.KM)

    /**
     * Cosa si aspetta chi vive in [countryCode] (ISO 3166-1 alpha-2, maiuscolo o no). Gli Stati
     * Uniti sono l'unico paese pienamente imperiale; il Regno Unito misura le strade in miglia
     * e il vento in mph ma il resto e' metrico; i pochi paesi che usano ancora i Fahrenheit
     * sono elencati per nome. Tutto il resto del mondo e' metrico.
     */
    fun forCountry(countryCode: String?): UnitPreferences {
      val country = countryCode?.uppercase().orEmpty()
      return UnitPreferences(
        temperature = if (country in FAHRENHEIT_COUNTRIES) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS,
        wind = if (country in MILES_COUNTRIES) WindUnit.MPH else WindUnit.KMH,
        pressure = if (country == "US") PressureUnit.INHG else PressureUnit.HPA,
        precipitation = if (country == "US") PrecipitationUnit.INCH else PrecipitationUnit.MM,
        distance = if (country in MILES_COUNTRIES) DistanceUnit.MILES else DistanceUnit.KM,
      )
    }

    val FAHRENHEIT_COUNTRIES: Set<String> = setOf("US", "BS", "BZ", "KY", "PW", "FM", "MH", "LR")
    val MILES_COUNTRIES: Set<String> = setOf("US", "GB", "LR", "MM")
  }
}

/** Le scelte esplicite dell'utente: null = "come dice il locale". */
data class UnitOverrides(
  val temperature: TemperatureUnit? = null,
  val wind: WindUnit? = null,
  val pressure: PressureUnit? = null,
  val precipitation: PrecipitationUnit? = null,
  val distance: DistanceUnit? = null,
) {
  fun resolve(countryCode: String?): UnitPreferences {
    val defaults = UnitPreferences.forCountry(countryCode)
    return UnitPreferences(
      temperature = temperature ?: defaults.temperature,
      wind = wind ?: defaults.wind,
      pressure = pressure ?: defaults.pressure,
      precipitation = precipitation ?: defaults.precipitation,
      distance = distance ?: defaults.distance,
    )
  }
}

/** Le conversioni, tutte dal metrico del dominio. Puro, verificato sul computer. */
object UnitMath {

  const val KMH_PER_MPH = 1.609344
  const val KMH_PER_KNOT = 1.852
  const val MMHG_PER_HPA = 0.7500616827
  const val INHG_PER_HPA = 0.02952998751
  const val MM_PER_INCH = 25.4

  fun temperature(celsius: Double, unit: TemperatureUnit): Double = when (unit) {
    TemperatureUnit.CELSIUS -> celsius
    TemperatureUnit.FAHRENHEIT -> celsius * 9.0 / 5.0 + 32.0
  }

  /** Una differenza di temperatura: niente offset di 32. */
  fun temperatureDelta(deltaCelsius: Double, unit: TemperatureUnit): Double = when (unit) {
    TemperatureUnit.CELSIUS -> deltaCelsius
    TemperatureUnit.FAHRENHEIT -> deltaCelsius * 9.0 / 5.0
  }

  fun wind(kmh: Double, unit: WindUnit): Double = when (unit) {
    WindUnit.KMH -> kmh
    WindUnit.MS -> kmh / 3.6
    WindUnit.MPH -> kmh / KMH_PER_MPH
    WindUnit.KNOTS -> kmh / KMH_PER_KNOT
    WindUnit.BEAUFORT -> beaufort(kmh).toDouble()
  }

  /**
   * La scala di Beaufort sulle soglie in km/h della tabella OMM (i limiti superiori di ogni
   * grado): 0 sotto 1 km/h, 12 da 118 in su.
   */
  fun beaufort(kmh: Double): Int {
    val upper = BEAUFORT_UPPER_KMH
    val index = upper.indexOfFirst { kmh < it }
    return if (index < 0) 12 else index
  }

  private val BEAUFORT_UPPER_KMH = doubleArrayOf(1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0)

  fun pressure(hPa: Double, unit: PressureUnit): Double = when (unit) {
    PressureUnit.HPA, PressureUnit.MBAR -> hPa
    PressureUnit.MMHG -> hPa * MMHG_PER_HPA
    PressureUnit.INHG -> hPa * INHG_PER_HPA
  }

  fun precipitation(mm: Double, unit: PrecipitationUnit): Double = when (unit) {
    PrecipitationUnit.MM -> mm
    PrecipitationUnit.INCH -> mm / MM_PER_INCH
  }

  fun distance(km: Double, unit: DistanceUnit): Double = when (unit) {
    DistanceUnit.KM -> km
    DistanceUnit.MILES -> km / KMH_PER_MPH
  }

  /**
   * I decimali con cui un'unita' e' leggibile: gli inHg cambiano al centesimo dove gli hPa
   * cambiano all'unita'; un pollice di pioggia e' tanta pioggia, quindi due decimali.
   */
  fun pressureDecimals(unit: PressureUnit, metricDecimals: Int): Int = when (unit) {
    PressureUnit.HPA, PressureUnit.MBAR -> metricDecimals
    PressureUnit.MMHG -> metricDecimals
    PressureUnit.INHG -> metricDecimals + 2
  }

  fun precipitationDecimals(unit: PrecipitationUnit, metricDecimals: Int): Int = when (unit) {
    PrecipitationUnit.MM -> metricDecimals
    PrecipitationUnit.INCH -> metricDecimals + 1
  }

  fun windDecimals(unit: WindUnit, metricDecimals: Int): Int = when (unit) {
    WindUnit.MS -> metricDecimals + 1
    WindUnit.BEAUFORT -> 0
    else -> metricDecimals
  }
}
