package dev.pampa.fluidweather.core.model

/**
 * Cio' che una persona puo' dire guardando fuori: il vocabolario della segnalazione (fase 14).
 * Le parole stanno in core-strings (fase 17): qui solo il significato.
 * Piu' stretto delle condizioni dei provider (nessuno "vede" una copertura al 60%) e con la
 * grandine, che i modelli non nominano ma un occhio riconosce al volo.
 */
enum class ObservedCondition(
  /** Se conta come precipitazione in corso: e' la verita' che entra nella verifica. */
  val wet: Boolean,
  /** La condizione equivalente nel vocabolario comune, per chi ragiona in [WeatherKind]. */
  val kind: WeatherKind,
) {
  CLEAR(false, WeatherKind.CLEAR),
  PARTLY_CLOUDY(false, WeatherKind.PARTLY_CLOUDY),
  CLOUDY(false, WeatherKind.CLOUDY),
  FOG(false, WeatherKind.FOG),
  DRIZZLE(true, WeatherKind.DRIZZLE),
  RAIN(true, WeatherKind.RAIN),
  HEAVY_RAIN(true, WeatherKind.HEAVY_RAIN),
  THUNDERSTORM(true, WeatherKind.THUNDERSTORM),
  SNOW(true, WeatherKind.SNOW),
  HAIL(true, WeatherKind.HEAVY_RAIN),
}

/** Un'osservazione dell'utente: cosa, quando, dove. Entra in archivio e nella verifica. */
data class Observation(
  val id: Long,
  val timestampMillis: Long,
  val condition: ObservedCondition,
  val latitude: Double?,
  val longitude: Double?,
  val placeName: String?,
)
