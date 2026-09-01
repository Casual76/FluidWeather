package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.WeatherKind

/**
 * Il piano gratuito di OpenWeatherMap: previsione a passi di 3 ore per 5 giorni, con la chiave
 * dell'utente (BYO-key: mai nell'APK, vive nel DataStore del dispositivo). La precipitazione e'
 * l'accumulo del passo di 3 ore, riportato cosi' com'e' sull'ora del passo.
 */
class OpenWeatherMapClient(
  override val descriptor: ProviderDescriptor,
  private val http: ProviderHttp,
  private val clock: () -> Long = System::currentTimeMillis,
) : WeatherClient {

  override suspend fun fetch(latitude: Double, longitude: Double, apiKey: String?): ForecastBundle {
    requireNotNull(apiKey) { "OpenWeatherMap richiede la chiave dell'utente" }
    val url = "https://api.openweathermap.org/data/2.5/forecast" +
      "?lat=$latitude&lon=$longitude&units=metric&appid=$apiKey"
    val root = http.readJson(url, FORECAST_TTL_MILLIS)

    val points = root["list"].asArray().mapNotNull { entry ->
      val timestamp = entry["dt"].double()?.toLong()?.times(1_000) ?: return@mapNotNull null
      val main = entry["main"]
      HourlyPoint(
        timestampMillis = timestamp,
        temperatureC = main["temp"].double(),
        relativeHumidityPercent = main["humidity"].double(),
        // `sea_level` quando c'e'; `pressure` come ripiego (che OWM riporta gia' al mare).
        pressureMslHpa = main["sea_level"].double() ?: main["pressure"].double(),
        cloudCoverPercent = entry["clouds"]["all"].double(),
        // units=metric lascia comunque il vento in m/s.
        windSpeedKmh = entry["wind"]["speed"].double()?.metersPerSecondToKmh(),
        windDirectionDeg = entry["wind"]["deg"].double(),
        windGustKmh = entry["wind"]["gust"].double()?.metersPerSecondToKmh(),
        precipitationProbabilityPercent = entry["pop"].double()?.times(100),
        precipitationMm = entry["rain"]["3h"].double() ?: entry["snow"]["3h"].double(),
        visibilityMeters = entry["visibility"].double(),
        kind = entry["weather"].at(0)["id"].double()?.toInt()?.let { owmKind(it) },
      )
    }

    return ForecastBundle(
      providerId = descriptor.id,
      fetchedAtMillis = clock(),
      latitude = latitude,
      longitude = longitude,
      hourly = points,
    )
  }

  private companion object {
    fun owmKind(id: Int): WeatherKind = when (id) {
      in 200..299 -> WeatherKind.THUNDERSTORM
      in 300..399 -> WeatherKind.DRIZZLE
      in 500..501 -> WeatherKind.RAIN
      in 502..599 -> WeatherKind.HEAVY_RAIN
      611, 612, 613, 615, 616 -> WeatherKind.SLEET
      600, 601, 620, 621 -> WeatherKind.SNOW
      602, 622 -> WeatherKind.HEAVY_SNOW
      in 700..799 -> WeatherKind.FOG
      800 -> WeatherKind.CLEAR
      801 -> WeatherKind.MOSTLY_CLEAR
      802 -> WeatherKind.PARTLY_CLOUDY
      803, 804 -> WeatherKind.CLOUDY
      else -> WeatherKind.UNKNOWN
    }
  }
}
