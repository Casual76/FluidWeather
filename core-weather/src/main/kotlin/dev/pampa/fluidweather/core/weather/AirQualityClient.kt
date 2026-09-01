package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.AirQualityNow
import dev.pampa.fluidweather.core.model.AqiBand
import dev.pampa.fluidweather.core.model.PollenLevels

/**
 * La qualita' dell'aria da Open-Meteo (CAMS): keyless, EAQI europeo, inquinanti e — solo in
 * Europa — i pollini. Fuori dalla costellazione della fusione: e' un dato ausiliario con un
 * solo fornitore, non un'opinione da pesare.
 */
class AirQualityClient(
  private val http: ProviderHttp,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  suspend fun now(latitude: Double, longitude: Double): AirQualityNow? {
    val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
      "?latitude=$latitude&longitude=$longitude" +
      "&current=european_aqi,pm2_5,pm10,ozone,nitrogen_dioxide,sulphur_dioxide" +
      "&hourly=alder_pollen,birch_pollen,grass_pollen,olive_pollen,ragweed_pollen" +
      "&timeformat=unixtime&timezone=UTC&forecast_days=1"
    val root = runCatching { http.readJson(url, FORECAST_TTL_MILLIS) }.getOrNull() ?: return null

    val current = root["current"]
    val aqi = current["european_aqi"].double()?.toInt() ?: return null

    val pollutants = listOf(
      "PM2.5" to current["pm2_5"].double(),
      "PM10" to current["pm10"].double(),
      "Ozono" to current["ozone"].double(),
      "NO2" to current["nitrogen_dioxide"].double(),
      "SO2" to current["sulphur_dioxide"].double(),
    )
    // L'inquinante dominante nell'EAQI e' quello col sotto-indice peggiore; le soglie di banda
    // differiscono per inquinante, quindi si normalizza ciascuno sulla PROPRIA scala EAQI.
    val dominant = pollutants
      .mapNotNull { (name, value) -> value?.let { name to it / eaqiScale(name) } }
      .maxByOrNull { it.second }

    val pollen = pollenNow(root)

    return AirQualityNow(
      europeanAqi = aqi,
      band = AqiBand.of(aqi),
      dominantPollutant = dominant?.first,
      dominantValue = pollutants.firstOrNull { it.first == dominant?.first }?.second,
      pollen = pollen,
    )
  }

  /** Il valore (ug/m3) che vale "100 EAQI" per ciascun inquinante: la scala ufficiale EEA. */
  private fun eaqiScale(pollutant: String): Double = when (pollutant) {
    "PM2.5" -> 50.0
    "PM10" -> 100.0
    "Ozono" -> 240.0
    "NO2" -> 230.0
    "SO2" -> 500.0
    else -> 100.0
  }

  private fun pollenNow(root: kotlinx.serialization.json.JsonElement): PollenLevels? {
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
