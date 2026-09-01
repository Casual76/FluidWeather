package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.WeatherKind
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Il piano gratuito di Meteosource: orario per ~24 ore, con la chiave dell'utente. Le date
 * arrivano SENZA offset, nel fuso della localita' dichiarato a parte nel payload: la
 * conversione passa da li', non da supposizioni.
 */
class MeteosourceClient(
  override val descriptor: ProviderDescriptor,
  private val http: ProviderHttp,
  private val clock: () -> Long = System::currentTimeMillis,
) : WeatherClient {

  override suspend fun fetch(latitude: Double, longitude: Double, apiKey: String?): ForecastBundle {
    requireNotNull(apiKey) { "Meteosource richiede la chiave dell'utente" }
    val lat = "${abs(latitude)}${if (latitude >= 0) "N" else "S"}"
    val lon = "${abs(longitude)}${if (longitude >= 0) "E" else "W"}"
    val url = "https://www.meteosource.com/api/v1/free/point" +
      "?lat=$lat&lon=$lon&sections=hourly&units=metric&key=$apiKey"
    val root = http.readJson(url, FORECAST_TTL_MILLIS)

    val zone = ZoneId.of(root["timezone"].string() ?: "UTC")
    val points = root["hourly"]["data"].asArray().mapNotNull { entry ->
      val date = entry["date"].string() ?: return@mapNotNull null
      HourlyPoint(
        timestampMillis = LocalDateTime.parse(date).atZone(zone).toInstant().toEpochMilli(),
        temperatureC = entry["temperature"].double(),
        cloudCoverPercent = entry["cloud_cover"]["total"].double(),
        precipitationMm = entry["precipitation"]["total"].double(),
        windSpeedKmh = entry["wind"]["speed"].double()?.metersPerSecondToKmh(),
        windDirectionDeg = entry["wind"]["angle"].double(),
        kind = entry["weather"].string()?.let { iconKind(it) },
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
    fun iconKind(icon: String): WeatherKind = when {
      "thunder" in icon || "tstorm" in icon -> WeatherKind.THUNDERSTORM
      icon == "clear" || icon == "sunny" -> WeatherKind.CLEAR
      "mostly_clear" in icon || "mostly_sunny" in icon -> WeatherKind.MOSTLY_CLEAR
      "partly" in icon -> WeatherKind.PARTLY_CLOUDY
      "overcast" in icon || "cloudy" in icon -> WeatherKind.CLOUDY
      "fog" in icon -> WeatherKind.FOG
      "drizzle" in icon || "light_rain" in icon -> WeatherKind.DRIZZLE
      "heavy_rain" in icon -> WeatherKind.HEAVY_RAIN
      "rain" in icon || "shower" in icon -> WeatherKind.RAIN
      "sleet" in icon || "freezing" in icon -> WeatherKind.SLEET
      "snow" in icon -> WeatherKind.SNOW
      else -> WeatherKind.UNKNOWN
    }
  }
}
