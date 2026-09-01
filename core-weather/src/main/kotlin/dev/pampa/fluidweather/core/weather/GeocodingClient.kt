package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.Place
import java.net.URLEncoder

/**
 * La ricerca dei posti: geocoding Open-Meteo, keyless, in lingua. I risultati diventano [Place]
 * con id proprio del geocoder (stabile: due ricerche dello stesso posto non creano doppioni).
 */
class GeocodingClient(private val http: ProviderHttp) {

  suspend fun search(query: String, language: String = "it"): List<Place> {
    if (query.isBlank()) return emptyList()
    val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
    val url = "https://geocoding-api.open-meteo.com/v1/search" +
      "?name=$encoded&count=8&language=$language&format=json"
    val root = http.readJson(url, maxAgeMillis = 24 * 3_600_000L)

    return root["results"].asArray().mapNotNull { result ->
      val id = result["id"].double()?.toLong() ?: return@mapNotNull null
      val name = result["name"].string() ?: return@mapNotNull null
      val latitude = result["latitude"].double() ?: return@mapNotNull null
      val longitude = result["longitude"].double() ?: return@mapNotNull null
      val region = listOfNotNull(result["admin1"].string(), result["country"].string())
        .joinToString(", ")
        .ifBlank { null }
      Place(id = id, name = name, region = region, latitude = latitude, longitude = longitude)
    }
  }
}
