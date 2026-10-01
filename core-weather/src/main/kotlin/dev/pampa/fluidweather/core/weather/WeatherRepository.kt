package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.nowcast.features.ContextVariable
import dev.pampa.fluidweather.nowcast.features.ContextSlots
import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.nowcast.features.NowcastContext
import dev.pampa.fluidweather.nowcast.truth.RainWindows
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
              // Non piu' `error.message` grezzo: "rete: Unable to resolve host" e un HTTP 500 sono
              // due situazioni opposte, e la Diagnostica le mostrava identiche.
              onFailure = { ProviderFetch(descriptor, null, failureTextOf(it)) },
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

/**
 * Da un bundle orario al contesto del nowcast, letto "com'era al download": il riferimento e' il
 * piu' presto fra adesso e l'istante in cui il bundle e' stato scaricato ([ForecastBundle.fetchedAtMillis]).
 * Vedi [contextAsOf].
 */
fun ForecastBundle.toContext(nowMillis: Long): NowcastContext? =
  contextAsOf(minOf(nowMillis, fetchedAtMillis))

/**
 * Il contesto sinottico com'era all'istante [refMillis], **solo con slot chiusi**, calcolato da
 * [ContextSlots.contextAt]: la stessa funzione con cui il banco ha costruito le righe del v3.
 *
 * Prima si leggeva la riga piu' vicina a "adesso" (`at`, fino a novanta minuti di scarto) e si
 * poteva pescare un valore istantaneo di mezz'ora nel futuro del download: una previsione spacciata
 * per osservazione. Poi (P2a) gli slot chiusi, ma con la pioggia dell'ultima ora presa dai quarti
 * d'ora quando c'erano: piu' fresca, e diversa da quella su cui il v3 e' stato addestrato (lo slot
 * orario). Col v3 la parita' col banco vale piu' di mezz'ora di freschezza: la lettura e' esatta sul
 * timestamp delle righe orarie, nessuna riga con fine oltre [refMillis] entra mai, e senza la riga S
 * il contesto non c'e'. I campi nuovi del v3 (temperatura e rugiada adesso e tre ore fa, nuvole tre
 * ore fa, le sette ore di pioggia) vengono dalle stesse righe.
 */
fun ForecastBundle.contextAsOf(refMillis: Long): NowcastContext? {
  val rows = hourly.associateBy { it.timestampMillis }
  return ContextSlots.contextAt(refMillis) { slotEnd, variable -> rows[slotEnd]?.valueOf(variable) }
}

private fun HourlyPoint.valueOf(variable: ContextVariable): Double? = when (variable) {
  ContextVariable.TEMPERATURE -> temperatureC
  ContextVariable.RELATIVE_HUMIDITY -> relativeHumidityPercent
  ContextVariable.DEW_POINT -> dewPointC
  ContextVariable.PRESSURE_MSL -> pressureMslHpa
  ContextVariable.PRECIPITATION -> precipitationMm
  ContextVariable.CLOUD_COVER -> cloudCoverPercent
  ContextVariable.WIND_SPEED -> windSpeedKmh
  ContextVariable.WIND_DIRECTION -> windDirectionDeg
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
