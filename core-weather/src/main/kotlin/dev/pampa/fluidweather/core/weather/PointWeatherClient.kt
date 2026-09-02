package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.WeatherKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Il "adesso" di un punto: quello che serve a un pin sulla mappa. */
data class PointNow(
  val latitude: Double,
  val longitude: Double,
  val temperatureC: Double?,
  val kind: WeatherKind?,
)

/**
 * Molti punti, una chiamata: Open-Meteo accetta liste di coordinate separate da virgola e
 * risponde con un array nello stesso ordine (un oggetto solo quando il punto e' uno). E' cio'
 * che riempie i pin delle localita' salvate sul radar senza dieci richieste.
 */
class PointWeatherClient(private val http: ProviderHttp) {

  suspend fun current(points: List<Pair<Double, Double>>): List<PointNow> {
    if (points.isEmpty()) return emptyList()
    val rounded = points.map { (latitude, longitude) -> WeatherPoint.round(latitude, longitude) }
    val url = "https://api.open-meteo.com/v1/forecast" +
      "?latitude=${rounded.joinToString(",") { it.first.toString() }}" +
      "&longitude=${rounded.joinToString(",") { it.second.toString() }}" +
      "&current=temperature_2m,weather_code&timeformat=unixtime&timezone=UTC"
    val root = http.readJson(url, CURRENT_TTL_MILLIS)
    val entries: List<JsonElement> = if (root is JsonArray) root.toList() else listOf(root)
    return entries.mapIndexed { index, entry ->
      val requested = rounded.getOrNull(index)
      PointNow(
        latitude = entry["latitude"].double() ?: requested?.first ?: 0.0,
        longitude = entry["longitude"].double() ?: requested?.second ?: 0.0,
        temperatureC = entry["current"]["temperature_2m"].double(),
        kind = entry["current"]["weather_code"].double()?.toInt()?.let { OpenMeteoClient.wmoKind(it) },
      )
    }
  }

  private companion object {
    /** Dieci minuti: un pin non ha bisogno di piu', e Open-Meteo aggiorna il "current" ogni quarto d'ora. */
    const val CURRENT_TTL_MILLIS = 10 * 60_000L
  }
}
