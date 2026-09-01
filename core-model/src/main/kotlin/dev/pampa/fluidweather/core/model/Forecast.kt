package dev.pampa.fluidweather.core.model

/**
 * Il vocabolario comune delle condizioni: ogni provider parla la sua lingua (codici WMO, symbol
 * code, icone), la normalizzazione traduce qui e il resto dell'app non sa piu' chi ha parlato.
 */
enum class WeatherKind {
  CLEAR,
  MOSTLY_CLEAR,
  PARTLY_CLOUDY,
  CLOUDY,
  FOG,
  DRIZZLE,
  RAIN,
  HEAVY_RAIN,
  SLEET,
  SNOW,
  HEAVY_SNOW,
  THUNDERSTORM,
  UNKNOWN,
}

/**
 * Un'ora normalizzata: unita' canoniche (celsius, hPa, mm, %, km/h, gradi, J/kg, metri), campi
 * null quando il provider non serve quella variabile — mai zero al posto di "non lo so".
 */
data class HourlyPoint(
  val timestampMillis: Long,
  val temperatureC: Double? = null,
  val relativeHumidityPercent: Double? = null,
  val dewPointC: Double? = null,
  val pressureMslHpa: Double? = null,
  val precipitationMm: Double? = null,
  val precipitationProbabilityPercent: Double? = null,
  val cloudCoverPercent: Double? = null,
  val windSpeedKmh: Double? = null,
  val windDirectionDeg: Double? = null,
  val windGustKmh: Double? = null,
  val capeJkg: Double? = null,
  val uvIndex: Double? = null,
  val visibilityMeters: Double? = null,
  val kind: WeatherKind? = null,
)

/**
 * La risposta di un provider per un punto, gia' normalizzata. [hourly] puo' includere ore
 * passate (analisi): servono al contesto del nowcast (pioggia recente, rotazione del vento).
 */
data class ForecastBundle(
  val providerId: String,
  val fetchedAtMillis: Long,
  val latitude: Double,
  val longitude: Double,
  val hourly: List<HourlyPoint>,
) {

  fun at(timestampMillis: Long): HourlyPoint? =
    hourly.minByOrNull { kotlin.math.abs(it.timestampMillis - timestampMillis) }
      ?.takeIf { kotlin.math.abs(it.timestampMillis - timestampMillis) <= 90 * 60_000L }

  val horizonMillis: Long get() = hourly.lastOrNull()?.timestampMillis ?: 0L
}
