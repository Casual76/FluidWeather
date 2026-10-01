package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Un valore scaricato e l'istante in cui e' arrivato **dalla rete**: per un colpo di cache e'
 * l'istante del download originale, non quello della lettura.
 */
data class Downloaded<T>(val value: T, val downloadedAtMillis: Long)

/**
 * Il giro comune di ogni client: cache prima, rete poi, cache aggiornata dopo. Il TTL e' del
 * chiamante perche' e' un fatto del dato, non della rete.
 *
 * Le versioni "Timed" dicono anche quando il dato e' stato scaricato davvero. E' cio' che finisce
 * in `ForecastBundle.fetchedAtMillis`: un bundle servito dalla cache dopo venticinque minuti ha
 * venticinque minuti, e chi decide se un contesto e' fresco, o se una verita' e' definitiva, deve
 * saperlo.
 */
class ProviderHttp(
  private val http: EngineHttp,
  private val cache: UrlCache,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  suspend fun readText(url: String, maxAgeMillis: Long, headers: Map<String, String> = emptyMap()): String =
    readTextTimed(url, maxAgeMillis, headers).value

  suspend fun readJson(url: String, maxAgeMillis: Long, headers: Map<String, String> = emptyMap()): JsonElement =
    readJsonTimed(url, maxAgeMillis, headers).value

  /**
   * Il corpo e l'istante del download. [useCache] = false va sempre in rete e non scrive niente:
   * serve a chi giudica (la verita' del pannello), per cui una risposta di prima — scaricata quando
   * le ore da giudicare erano ancora previsioni — sarebbe un giudizio falso con l'aria di uno vero.
   */
  suspend fun readTextTimed(
    url: String,
    maxAgeMillis: Long,
    headers: Map<String, String> = emptyMap(),
    useCache: Boolean = true,
  ): Downloaded<String> {
    if (useCache) cache.readTimed(url, maxAgeMillis)?.let { return Downloaded(it.text, it.writtenAtMillis) }
    val body = http.readText(url, headers)
    val downloadedAt = if (useCache) cache.write(url, body) else clock()
    return Downloaded(body, downloadedAt)
  }

  suspend fun readJsonTimed(
    url: String,
    maxAgeMillis: Long,
    headers: Map<String, String> = emptyMap(),
    useCache: Boolean = true,
  ): Downloaded<JsonElement> {
    val text = readTextTimed(url, maxAgeMillis, headers, useCache)
    return Downloaded(Json.parseToJsonElement(text.value), text.downloadedAtMillis)
  }
}

// --- Navigazione JSON senza modelli: ogni campo mancante e' una decisione esplicita. ---

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun JsonElement?.at(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull

fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

/** m/s -> km/h: la valuta canonica del vento e' il km/h. */
internal fun Double.metersPerSecondToKmh(): Double = this * 3.6

internal fun Double.mphToKmh(): Double = this * 1.609344
