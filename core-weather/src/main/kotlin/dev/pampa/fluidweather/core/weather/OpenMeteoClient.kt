package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.WeatherKind

/**
 * Un client, molte fisiche: Open-Meteo espone i modelli nazionali dietro lo stesso contratto,
 * e ogni [ProviderDescriptor] con un [model] diverso e' a tutti gli effetti un provider diverso
 * nella costellazione. `past_hours=6` non e' un vezzo: le ore appena passate (analisi) sono il
 * contesto del nowcast — pioggia recente, rotazione del vento.
 */
class OpenMeteoClient(
  override val descriptor: ProviderDescriptor,
  private val http: ProviderHttp,
  /** Il parametro `models` di Open-Meteo; null = best_match. */
  private val model: String? = null,
  private val clock: () -> Long = System::currentTimeMillis,
) : WeatherClient {

  override suspend fun fetch(latitude: Double, longitude: Double, apiKey: String?): ForecastBundle {
    val url = buildString {
      append("https://api.open-meteo.com/v1/forecast")
      append("?latitude=$latitude&longitude=$longitude")
      append("&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,pressure_msl,")
      append("precipitation,precipitation_probability,cloud_cover,wind_speed_10m,")
      append("wind_direction_10m,wind_gusts_10m,cape,uv_index,visibility,weather_code")
      append("&past_hours=6&forecast_days=${(descriptor.horizonHours + 23) / 24}")
      append("&timeformat=unixtime&timezone=UTC&wind_speed_unit=kmh")
      if (model != null) append("&models=$model")
    }
    val root = http.readJson(url, FORECAST_TTL_MILLIS)
    val hourly = root["hourly"]

    val times = hourly["time"].asArray()
    fun series(name: String): List<Double?> = hourly[name].asArray().map { it.double() }

    val temperature = series("temperature_2m")
    val humidity = series("relative_humidity_2m")
    val dewPoint = series("dew_point_2m")
    val pressure = series("pressure_msl")
    val precipitation = series("precipitation")
    val probability = series("precipitation_probability")
    val cloud = series("cloud_cover")
    val windSpeed = series("wind_speed_10m")
    val windDirection = series("wind_direction_10m")
    val gust = series("wind_gusts_10m")
    val cape = series("cape")
    val uv = series("uv_index")
    val visibility = series("visibility")
    val code = series("weather_code")

    val points = times.mapIndexedNotNull { i, time ->
      val timestamp = time.double()?.toLong()?.times(1_000) ?: return@mapIndexedNotNull null
      HourlyPoint(
        timestampMillis = timestamp,
        temperatureC = temperature.getOrNull(i),
        relativeHumidityPercent = humidity.getOrNull(i),
        dewPointC = dewPoint.getOrNull(i),
        pressureMslHpa = pressure.getOrNull(i),
        precipitationMm = precipitation.getOrNull(i),
        precipitationProbabilityPercent = probability.getOrNull(i),
        cloudCoverPercent = cloud.getOrNull(i),
        windSpeedKmh = windSpeed.getOrNull(i),
        windDirectionDeg = windDirection.getOrNull(i),
        windGustKmh = gust.getOrNull(i),
        capeJkg = cape.getOrNull(i),
        uvIndex = uv.getOrNull(i),
        visibilityMeters = visibility.getOrNull(i),
        kind = code.getOrNull(i)?.toInt()?.let { wmoKind(it) },
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
    /** La tabella WMO 4677 ridotta al vocabolario comune. */
    fun wmoKind(code: Int): WeatherKind = when (code) {
      0 -> WeatherKind.CLEAR
      1 -> WeatherKind.MOSTLY_CLEAR
      2 -> WeatherKind.PARTLY_CLOUDY
      3 -> WeatherKind.CLOUDY
      45, 48 -> WeatherKind.FOG
      in 51..57 -> WeatherKind.DRIZZLE
      61, 63, 80, 81 -> WeatherKind.RAIN
      65, 82 -> WeatherKind.HEAVY_RAIN
      66, 67 -> WeatherKind.SLEET
      71, 73, 77, 85 -> WeatherKind.SNOW
      75, 86 -> WeatherKind.HEAVY_SNOW
      in 95..99 -> WeatherKind.THUNDERSTORM
      else -> WeatherKind.UNKNOWN
    }
  }
}
