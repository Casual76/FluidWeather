package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.AirQualityNow
import dev.pampa.fluidweather.core.model.AqiBand
import dev.pampa.fluidweather.core.model.AqiPoint
import dev.pampa.fluidweather.core.model.Eaqi
import dev.pampa.fluidweather.core.model.Pollutant
import dev.pampa.fluidweather.core.model.PollenLevels
import kotlinx.serialization.json.JsonElement

/**
 * La qualita' dell'aria da Open-Meteo (CAMS): keyless, EAQI europeo, inquinanti e — solo in
 * Europa — i pollini. Fuori dalla costellazione della fusione: e' un dato ausiliario con un
 * solo fornitore, non un'opinione da pesare. Con la pagina (fase 11b) porta anche gli
 * inquinanti uno a uno, col sotto-indice sulla loro scala, e la previsione dell'indice.
 */
class AirQualityClient(
  private val http: ProviderHttp,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  suspend fun now(latitude: Double, longitude: Double): AirQualityNow? {
    val (lat, lon) = WeatherPoint.round(latitude, longitude)
    val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
      "?latitude=$lat&longitude=$lon" +
      "&current=european_aqi,pm2_5,pm10,ozone,nitrogen_dioxide,sulphur_dioxide" +
      "&hourly=european_aqi,alder_pollen,birch_pollen,grass_pollen,olive_pollen,ragweed_pollen" +
      "&timeformat=unixtime&timezone=UTC&forecast_days=2"
    val root = runCatching { http.readJson(url, FORECAST_TTL_MILLIS) }.getOrNull() ?: return null

    val current = root["current"]
    val aqi = current["european_aqi"].double()?.toInt() ?: return null

    val pollutants = listOf(
      "PM2.5" to current["pm2_5"].double(),
      "PM10" to current["pm10"].double(),
      "Ozono" to current["ozone"].double(),
      "NO2" to current["nitrogen_dioxide"].double(),
      "SO2" to current["sulphur_dioxide"].double(),
    ).mapNotNull { (name, value) ->
      val concentration = value ?: return@mapNotNull null
      Eaqi.subIndex(name, concentration)?.let { Pollutant(name, concentration, it) }
    }
    // L'inquinante dominante nell'EAQI e' quello col sotto-indice peggiore: ogni inquinante ha
    // le sue soglie, quindi si confrontano i sotto-indici, mai le concentrazioni.
    val dominant = pollutants.maxByOrNull { it.subIndex }

    return AirQualityNow(
      europeanAqi = aqi,
      band = AqiBand.of(aqi),
      dominantPollutant = dominant?.name,
      dominantValue = dominant?.valueUgm3,
      pollen = pollenNow(root),
      pollutants = pollutants,
      forecast = forecast(root),
    )
  }

  private fun forecast(root: JsonElement): List<AqiPoint> {
    val hourly = root["hourly"]
    val times = hourly["time"].asArray()
    val values = hourly["european_aqi"].asArray()
    val fromSeconds = clock() / 1_000 - 3_600
    return times.indices.mapNotNull { i ->
      val seconds = times[i].double()?.toLong() ?: return@mapNotNull null
      if (seconds < fromSeconds) return@mapNotNull null
      val value = values.getOrNull(i).double() ?: return@mapNotNull null
      AqiPoint(seconds * 1_000, value.toInt())
    }
  }

  private fun pollenNow(root: JsonElement): PollenLevels? {
    val hourly = root["hourly"]
    val times = hourly["time"].asArray()
    if (times.isEmpty()) return null
    val nowSeconds = clock() / 1_000
    val index = times.indices.minByOrNull { i ->
      kotlin.math.abs((times[i].double() ?: 0.0) - nowSeconds)
    } ?: return null

    fun at(name: String): Double? = hourly[name].asArray().getOrNull(index).double()

    val levels = PollenLevels(
      alder = at("alder_pollen"),
      birch = at("birch_pollen"),
      grass = at("grass_pollen"),
      olive = at("olive_pollen"),
      ragweed = at("ragweed_pollen"),
    )
    // Fuori Europa il modello non serve pollini: tutte null -> il dato non esiste, non e' zero.
    val allNull = listOf(levels.alder, levels.birch, levels.grass, levels.olive, levels.ragweed)
      .all { it == null }
    return if (allNull) null else levels
  }
}
