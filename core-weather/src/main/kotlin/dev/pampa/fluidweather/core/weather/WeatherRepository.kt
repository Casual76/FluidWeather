package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.nowcast.features.NowcastContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** L'esito per-provider: o il bundle, o il motivo del fallimento — mai un silenzio. */
data class ProviderFetch(
  val descriptor: ProviderDescriptor,
  val bundle: ForecastBundle?,
  val error: String?,
)

/**
 * L'orchestratore del livello provider: per un punto interroga IN PARALLELO tutti e soli i
 * provider che lo coprono (registro) e per cui c'e' la chiave se serve. Un provider che fallisce
 * e' una riga con l'errore, non un'eccezione che affonda gli altri: la fusione (fase 7) vuole
 * tutte le opinioni disponibili, non la prima che inciampa.
 */
class WeatherRepository(
  private val clients: Map<String, WeatherClient>,
  private val keysStore: ProviderKeysStore,
) {

  suspend fun fetchAll(latitude: Double, longitude: Double): List<ProviderFetch> = coroutineScope {
    // Al millesimo di grado: un fix GPS che balla di qualche metro non vale un URL nuovo.
    val (lat, lon) = WeatherPoint.round(latitude, longitude)
    val keys = keysStore.current()
    ProviderRegistry.available(lat, lon, keys)
      .mapNotNull { descriptor -> clients[descriptor.id]?.let { descriptor to it } }
      .map { (descriptor, client) ->
        async {
          runCatching { client.fetch(lat, lon, keys[descriptor.id]) }
            .fold(
              onSuccess = { ProviderFetch(descriptor, it, null) },
              onFailure = { ProviderFetch(descriptor, null, it.message ?: it.javaClass.simpleName) },
            )
        }
      }
      .map { it.await() }
  }

  /**
   * Il contesto sinottico per il verdetto del telefono: dall'opinione piu' completa disponibile
   * (Open-Meteo best_match: ha tutte le variabili e le 6 ore passate). Una sola chiamata,
   * ammortizzata dalla cache — il contesto non merita dieci richieste.
   */
  suspend fun contextFor(latitude: Double, longitude: Double, nowMillis: Long): NowcastContext? {
    val client = clients[ProviderRegistry.OPEN_METEO] ?: return null
    val bundle = runCatching { client.fetch(latitude, longitude, null) }.getOrNull() ?: return null
    return bundle.toContext(nowMillis)
  }
}

/** Da un bundle orario al contesto del nowcast: adesso, tre ore fa, e la pioggia recente. */
fun ForecastBundle.toContext(nowMillis: Long): NowcastContext? {
  val now = at(nowMillis) ?: return null
  val threeAgo = at(nowMillis - 3 * 3_600_000L)
  val rainLast3 = (0..2).mapNotNull { at(nowMillis - it * 3_600_000L)?.precipitationMm }
    .takeIf { it.isNotEmpty() }
    ?.sum()
  val dewSpread = if (now.temperatureC != null && now.dewPointC != null) {
    now.temperatureC!! - now.dewPointC!!
  } else {
    null
  }
  return NowcastContext(
    relativeHumidityPercent = now.relativeHumidityPercent,
    dewPointSpreadC = dewSpread,
    cloudCoverPercent = now.cloudCoverPercent,
    windSpeedKmh = now.windSpeedKmh,
    windDirectionDeg = now.windDirectionDeg,
    windDirectionDeg3hAgo = threeAgo?.windDirectionDeg,
    rainLastHourMm = now.precipitationMm,
    rainLast3hMm = rainLast3,
  )
}

/** La fabbrica della costellazione: descrittori del registro -> client concreti. */
fun buildWeatherClients(http: ProviderHttp): Map<String, WeatherClient> {
  fun descriptor(id: String): ProviderDescriptor = ProviderRegistry.all.first { it.id == id }
  return listOf(
    OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO), http, model = null),
    OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO_ICON), http, model = "icon_seamless"),
    OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO_ECMWF), http, model = "ecmwf_ifs025"),
    OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO_GFS), http, model = "gfs_seamless"),
    OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO_AROME), http, model = "meteofrance_seamless"),
    OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO_JMA), http, model = "jma_seamless"),
    MetNorwayClient(descriptor(ProviderRegistry.MET_NORWAY), http),
    NwsClient(descriptor(ProviderRegistry.NWS), http),
    OpenWeatherMapClient(descriptor(ProviderRegistry.OPENWEATHERMAP), http),
    MeteosourceClient(descriptor(ProviderRegistry.METEOSOURCE), http),
  ).associateBy { it.descriptor.id }
}
