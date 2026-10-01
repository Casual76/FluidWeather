package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Una rete finta che risponde con [respond] e ricorda ogni URL chiesto, nell'ordine: i test della
 * verita' devono poter dire *che cosa* e' stato chiesto, e quante volte.
 */
class RecordingHttp(var respond: (String) -> String) : EngineHttp() {
  val urls = mutableListOf<String>()

  override suspend fun readText(url: String, headers: Map<String, String>): String {
    urls += url
    return respond(url)
  }
}

/** Un [ProviderHttp] su una cache in una cartella temporanea, con l'orologio dato. */
fun providerHttpOf(http: EngineHttp, clock: () -> Long): ProviderHttp =
  ProviderHttp(http, UrlCache(Files.createTempDirectory("cache").toFile(), clock), clock)

/**
 * Una risposta historical-forecast come la da' Open-Meteo: `hourly.time` in secondi e una serie
 * per chiave. [series] va da chiave (per esempio `precipitation_ukmo_seamless`) a valori allineati
 * a [timesMillis]; un null diventa un `null` JSON.
 */
fun archiveJson(timesMillis: List<Long>, series: Map<String, List<Double?>>): String = buildJsonObject {
  put(
    "hourly",
    buildJsonObject {
      put("time", JsonArray(timesMillis.map { JsonPrimitive(it / 1_000L) }))
      for ((key, values) in series) {
        put(key, JsonArray(values.map { value -> value?.let { JsonPrimitive(it) } ?: JsonNull }))
      }
    },
  )
}.toString()

const val HOUR: Long = 3_600_000L

/** 2026-09-01T00:00Z: un'ora piena e una mezzanotte, cosi' i conti degli slot si leggono a occhio. */
const val MIDNIGHT: Long = 1_788_220_800_000L
