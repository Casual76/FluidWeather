package dev.pampa.fluidweather.core.model

/**
 * Cio' che una persona puo' dire guardando fuori: il vocabolario della segnalazione (fase 14).
 * Piu' stretto delle condizioni dei provider (nessuno "vede" una copertura al 60%) e con la
 * grandine, che i modelli non nominano ma un occhio riconosce al volo.
 */
enum class ObservedCondition(
  val label: String,
  /** Se conta come precipitazione in corso: e' la verita' che entra nella verifica. */
  val wet: Boolean,
  /** La condizione equivalente nel vocabolario comune, per chi ragiona in [WeatherKind]. */
  val kind: WeatherKind,
) {
  CLEAR("Sereno", false, WeatherKind.CLEAR),
  PARTLY_CLOUDY("Poco nuvoloso", false, WeatherKind.PARTLY_CLOUDY),
  CLOUDY("Nuvoloso", false, WeatherKind.CLOUDY),
  FOG("Nebbia", false, WeatherKind.FOG),
  DRIZZLE("Pioviggine", true, WeatherKind.DRIZZLE),
  RAIN("Pioggia", true, WeatherKind.RAIN),
  HEAVY_RAIN("Pioggia forte", true, WeatherKind.HEAVY_RAIN),
  THUNDERSTORM("Temporale", true, WeatherKind.THUNDERSTORM),
  SNOW("Neve", true, WeatherKind.SNOW),
  HAIL("Grandine", true, WeatherKind.HEAVY_RAIN),
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
