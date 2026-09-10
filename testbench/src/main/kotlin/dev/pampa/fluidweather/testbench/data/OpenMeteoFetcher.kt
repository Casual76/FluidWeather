package dev.pampa.fluidweather.testbench.data

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Scarica gli archivi orari storici da Open-Meteo (archive-api.open-meteo.com).
 *
 * Condizioni d'uso rispettate e dichiarate: uso non commerciale, attribuzione
 * "Weather data by Open-Meteo.com" (CC BY 4.0). Il fetch e' un attrezzo da banco, non codice
 * dell'app: gira sul computer, una volta, e il risultato resta su disco cosi' com'e' arrivato.
 */
class OpenMeteoFetcher(
  private val startDate: String = "2022-09-01",
  private val endDate: String = "2026-08-31",
) {

  private val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .build()

  private val hourlyVariables = listOf(
    "temperature_2m",
    "relative_humidity_2m",
    "dew_point_2m",
    "surface_pressure",
    "pressure_msl",
    "precipitation",
    "cloud_cover",
    "wind_speed_10m",
    "wind_direction_10m",
    // CAPE: l'unica variabile convettiva che l'archivio offre e che i provider dell'app gia'
    // portano (HourlyPoint.capeJkg). La probabilita' di precipitazione, invece, nell'archivio
    // non esiste: e' una variabile di previsione, e addestrarci sopra sarebbe impossibile.
    "cape",
  ).joinToString(",")

  fun fetchAll(locations: List<BenchLocation> = BenchLocations) {
    val dataDir = StationDataset.dataFile(locations.first()).parentFile
    dataDir.mkdirs()
    for (location in locations) {
      val target = StationDataset.dataFile(location)
      if (target.exists()) {
        println("gia' presente: ${location.name} (${target.length() / 1024} KB)")
        continue
      }
      println("scarico ${location.name} (${location.why})...")
      val url = "https://archive-api.open-meteo.com/v1/archive" +
        "?latitude=${location.latitude}&longitude=${location.longitude}" +
        "&start_date=$startDate&end_date=$endDate" +
        "&hourly=$hourlyVariables" +
        "&timeformat=unixtime&timezone=UTC&format=csv"
      val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(3))
        .header("User-Agent", "FluidWeather-testbench (uso non commerciale; dev.pampa.fluidweather)")
        .GET()
        .build()
      val body = fetchWithRetry(request, location.name)
      check(body.lineSequence().count() > 100) { "risposta sospetta per ${location.name}" }
      target.writeText(body)
      println("  salvato: ${target.path} (${target.length() / 1024} KB)")
      // Gentilezza verso un servizio gratuito: nessuna raffica di richieste.
      Thread.sleep(1_000)
    }
    println("Dati meteo di Open-Meteo.com (CC BY 4.0) — uso non commerciale.")
  }

  /**
   * Un servizio gratuito ogni tanto dice "basta" (HTTP 429), e la risposta giusta non e'
   * insistere: e' aspettare piu' a lungo a ogni tentativo. Prima un 429 sull'ultima localita'
   * buttava via anche le nove gia' scaricate.
   */
  private fun fetchWithRetry(request: HttpRequest, name: String): String {
    var wait = FIRST_BACKOFF_MILLIS
    for (attempt in 1..MAX_ATTEMPTS) {
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() == 200) return response.body()
      check(response.statusCode() == 429 && attempt < MAX_ATTEMPTS) {
        "fetch fallito per $name: HTTP ${response.statusCode()}"
      }
      println("  429 (troppe richieste): aspetto ${wait / 1000} s e riprovo ($attempt/$MAX_ATTEMPTS)")
      Thread.sleep(wait)
      wait *= 2
    }
    error("fetch fallito per $name: troppi tentativi")
  }

  private companion object {
    const val MAX_ATTEMPTS = 5
    const val FIRST_BACKOFF_MILLIS = 15_000L
  }
}
