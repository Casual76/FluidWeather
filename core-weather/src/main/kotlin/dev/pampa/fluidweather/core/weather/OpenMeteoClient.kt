package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.MinutePoint
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
  /**
   * Il parametro `models` di Open-Meteo; null = best_match. Pubblico perche' un modello che fa da
   * giudice nel pannello della verita' non deve mai finire in classifica pioggia, e il test che lo
   * garantisce deve poterlo leggere.
   */
  val model: String? = null,
) : WeatherClient {

  override suspend fun fetch(latitude: Double, longitude: Double, apiKey: String?): ForecastBundle {
    val url = buildString {
      append("https://api.open-meteo.com/v1/forecast")
      append("?latitude=$latitude&longitude=$longitude")
      append("&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,pressure_msl,")
      append("precipitation,precipitation_probability,cloud_cover,wind_speed_10m,")
      append("wind_direction_10m,wind_gusts_10m,cape,uv_index,visibility,weather_code")
      // Il quarto d'ora: due giorni bastano e avanzano — serve a sapere cosa sta succedendo
      // adesso e nell'ora che viene, non a fare previsioni lunghe. `past_minutely_15` porta
      // l'ultima ora e mezza gia' accaduta, che e' la parte che risponde a "sta piovendo?".
      append("&current=temperature_2m,weather_code,precipitation")
      append("&minutely_15=precipitation,precipitation_probability")
      append("&past_minutely_15=8&forecast_minutely_15=8")
      append("&past_hours=6&forecast_days=${(descriptor.horizonHours + 23) / 24}")
      append("&timeformat=unixtime&timezone=auto&wind_speed_unit=kmh")
      if (model != null) append("&models=$model")
    }
    val (root, downloadedAt) = http.readJsonTimed(url, if (model == null) 5 * 60_000L else FORECAST_TTL_MILLIS)
    val hourly = root["hourly"]

    val times = hourly["time"].asArray()
    fun series(name: String): List<Double?> = hourly[name].asArray().map { it.double()?.takeIf { value -> value.isFinite() } }

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
      val timestamp = time.double()?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 && it % 1.0 == 0.0 }?.toLong()?.times(1_000) ?: return@mapIndexedNotNull null
      HourlyPoint(
        timestampMillis = timestamp,
        temperatureC = temperature.getOrNull(i),
        relativeHumidityPercent = humidity.getOrNull(i),
        dewPointC = dewPoint.getOrNull(i),
        pressureMslHpa = pressure.getOrNull(i),
        precipitationMm = precipitation.getOrNull(i)?.takeIf { it >= 0 },
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
      // L'istante del download vero: un colpo di cache non ringiovanisce il dato.
      fetchedAtMillis = downloadedAt,
      latitude = latitude,
      longitude = longitude,
      hourly = points,
      minutely = minutelyPoints(root),
      zoneId = WeatherZones.validId(root["timezone"].string()),
      current = parseCurrent(root["current"], downloadedAt),
      hourlyPrecipitation = PrecipitationAccumulation(3_600_000L, AccumulationAnchor.PRECEDING),
      minutelyPrecipitation = PrecipitationAccumulation(900_000L, AccumulationAnchor.PRECEDING),
      minutelyPossiblyInterpolated = true,
    )
  }

  /**
   * Il blocco a quindici minuti, quando c'e'. Non tutti i modelli di Open-Meteo lo espongono e
   * non tutte le localita' lo hanno: un blocco mancante e' una lista vuota, non un errore — il
   * verdetto deve continuare a esistere anche senza.
   */
  private fun minutelyPoints(root: kotlinx.serialization.json.JsonElement): List<MinutePoint> {
    val block = runCatching { root["minutely_15"] }.getOrNull() ?: return emptyList()
    val times = runCatching { block["time"].asArray() }.getOrNull() ?: return emptyList()
    fun series(name: String): List<Double?> =
      runCatching { block[name].asArray().map { it.double()?.takeIf { value -> value.isFinite() } } }.getOrDefault(emptyList())
    val precipitation = series("precipitation")
    val probability = series("precipitation_probability")
    return times.mapIndexedNotNull { i, time ->
      val timestamp = time.double()?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 && it % 1.0 == 0.0 }?.toLong()?.times(1_000) ?: return@mapIndexedNotNull null
      MinutePoint(
        timestampMillis = timestamp,
        precipitationMm = precipitation.getOrNull(i)?.takeIf { it >= 0 },
        precipitationProbabilityPercent = probability.getOrNull(i),
      )
    }
  }

  private fun parseCurrent(block: kotlinx.serialization.json.JsonElement?, downloadedAt: Long): ProviderCurrent? {
    fun finite(name: String) = block[name].double()?.takeIf { it.isFinite() }
    val time = finite("time")?.takeIf { it >= 0 && it < Long.MAX_VALUE / 1000.0 && it % 1.0 == 0.0 }
      ?.toLong()?.times(1000) ?: return null
    val interval = finite("interval")?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000.0 && it % 1.0 == 0.0 }
      ?.toLong()?.times(1000)
    val temperature = finite("temperature_2m")
    val kind = finite("weather_code")?.takeIf { it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()?.let(::wmoKind)
    val precipitation = finite("precipitation")?.takeIf { it >= 0 }
    if (temperature == null && kind == null && precipitation == null) return null
    return ProviderCurrent(time, temperature, kind, precipitation, interval, downloadedAt, true)
  }

  companion object {
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
