package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.WeatherKind
import java.time.OffsetDateTime
import java.util.Locale

/**
 * api.weather.gov in due passi: `points/{lat},{lon}` dice quale cella di griglia sei (e non
 * cambia mai: cache di una settimana), poi il forecast orario della cella. Il servizio parla
 * in unita' popolari — Fahrenheit, "5 mph", direzioni bussola — e la normalizzazione qui e' il
 * pedaggio per un servizio ufficiale, gratuito e senza chiave.
 */
class NwsClient(
  override val descriptor: ProviderDescriptor,
  private val http: ProviderHttp,
  private val clock: () -> Long = System::currentTimeMillis,
) : WeatherClient {

  override suspend fun fetch(latitude: Double, longitude: Double, apiKey: String?): ForecastBundle {
    val pointUrl = String.format(Locale.ROOT, "https://api.weather.gov/points/%.4f,%.4f", latitude, longitude)
    val point = http.readJson(pointUrl, GRID_TTL_MILLIS)
    val hourlyUrl = point["properties"]["forecastHourly"].string()
      ?: error("NWS: cella di griglia senza forecastHourly")

    val forecast = http.readJson(hourlyUrl, FORECAST_TTL_MILLIS)
    val points = forecast["properties"]["periods"].asArray().mapNotNull { period ->
      val start = period["startTime"].string() ?: return@mapNotNull null
      val temperatureRaw = period["temperature"].double()
      val fahrenheit = period["temperatureUnit"].string() != "C"
      HourlyPoint(
        timestampMillis = OffsetDateTime.parse(start).toInstant().toEpochMilli(),
        temperatureC = temperatureRaw?.let { if (fahrenheit) (it - 32) * 5 / 9 else it },
        dewPointC = period["dewpoint"]["value"].double(),
        relativeHumidityPercent = period["relativeHumidity"]["value"].double(),
        precipitationProbabilityPercent = period["probabilityOfPrecipitation"]["value"].double(),
        windSpeedKmh = parseWindSpeed(period["windSpeed"].string()),
        windDirectionDeg = compassDegrees(period["windDirection"].string()),
        kind = period["shortForecast"].string()?.let { forecastKind(it) },
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
    const val GRID_TTL_MILLIS = 7L * 24 * 3_600_000L

    fun parseWindSpeed(raw: String?): Double? {
      if (raw == null) return null
      val number = raw.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
      return if (raw.contains("km/h")) number else number.mphToKmh()
    }

    val compass = listOf(
      "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
      "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compassDegrees(direction: String?): Double? {
      val index = compass.indexOf(direction ?: return null)
      return if (index < 0) null else index * 22.5
    }

    fun forecastKind(text: String): WeatherKind {
      val lower = text.lowercase()
      return when {
        "thunder" in lower -> WeatherKind.THUNDERSTORM
        "heavy rain" in lower -> WeatherKind.HEAVY_RAIN
        "drizzle" in lower -> WeatherKind.DRIZZLE
        "rain" in lower || "showers" in lower -> WeatherKind.RAIN
        "sleet" in lower || "freezing" in lower -> WeatherKind.SLEET
        "heavy snow" in lower -> WeatherKind.HEAVY_SNOW
        "snow" in lower -> WeatherKind.SNOW
        "fog" in lower || "haze" in lower -> WeatherKind.FOG
        "mostly sunny" in lower || "mostly clear" in lower -> WeatherKind.MOSTLY_CLEAR
        "partly" in lower -> WeatherKind.PARTLY_CLOUDY
        "cloudy" in lower || "overcast" in lower -> WeatherKind.CLOUDY
        "sunny" in lower || "clear" in lower -> WeatherKind.CLEAR
        else -> WeatherKind.UNKNOWN
      }
    }
  }
}
