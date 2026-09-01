package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.WeatherKind
import java.time.Instant

/**
 * Locationforecast 2.0 compact. Le condizioni d'uso chiedono uno User-Agent descrittivo: lo
 * fornisce l'EngineHttp costruito nel grafo dell'app, unico per tutte le chiamate.
 *
 * La precipitazione oraria esiste solo dove il servizio pubblica `next_1_hours` (le prime ~48
 * ore); oltre, il campo resta null invece di spalmare i totali a 6 ore su ore inventate.
 */
class MetNorwayClient(
  override val descriptor: ProviderDescriptor,
  private val http: ProviderHttp,
  private val clock: () -> Long = System::currentTimeMillis,
) : WeatherClient {

  override suspend fun fetch(latitude: Double, longitude: Double, apiKey: String?): ForecastBundle {
    val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$latitude&lon=$longitude"
    val root = http.readJson(url, FORECAST_TTL_MILLIS)

    val points = root["properties"]["timeseries"].asArray().mapNotNull { entry ->
      val time = entry["time"].string() ?: return@mapNotNull null
      val details = entry["data"]["instant"]["details"]
      val nextHour = entry["data"]["next_1_hours"]
      val nextSix = entry["data"]["next_6_hours"]
      val symbol = nextHour["summary"]["symbol_code"].string()
        ?: nextSix["summary"]["symbol_code"].string()
      HourlyPoint(
        timestampMillis = Instant.parse(time).toEpochMilli(),
        temperatureC = details["air_temperature"].double(),
        relativeHumidityPercent = details["relative_humidity"].double(),
        pressureMslHpa = details["air_pressure_at_sea_level"].double(),
        cloudCoverPercent = details["cloud_area_fraction"].double(),
        windSpeedKmh = details["wind_speed"].double()?.metersPerSecondToKmh(),
        windDirectionDeg = details["wind_from_direction"].double(),
        precipitationMm = nextHour["details"]["precipitation_amount"].double(),
        kind = symbol?.let { symbolKind(it) },
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
    /** I symbol code MET sono "famiglia_variante": conta la famiglia. */
    fun symbolKind(symbol: String): WeatherKind {
      val family = symbol.substringBefore('_')
      return when {
        family.contains("thunder") -> WeatherKind.THUNDERSTORM
        family == "clearsky" -> WeatherKind.CLEAR
        family == "fair" -> WeatherKind.MOSTLY_CLEAR
        family == "partlycloudy" -> WeatherKind.PARTLY_CLOUDY
        family == "cloudy" -> WeatherKind.CLOUDY
        family == "fog" -> WeatherKind.FOG
        family.contains("heavyrain") -> WeatherKind.HEAVY_RAIN
        family.contains("lightrain") -> WeatherKind.DRIZZLE
        family.contains("rain") -> WeatherKind.RAIN
        family.contains("sleet") -> WeatherKind.SLEET
        family.contains("heavysnow") -> WeatherKind.HEAVY_SNOW
        family.contains("snow") -> WeatherKind.SNOW
        else -> WeatherKind.UNKNOWN
      }
    }
  }
}
