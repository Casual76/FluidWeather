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
 * Il giro comune di ogni client: cache prima, rete poi, cache aggiornata dopo. Il TTL e' del
 * chiamante perche' e' un fatto del dato, non della rete.
 */
class ProviderHttp(
  private val http: EngineHttp,
  private val cache: UrlCache,
) {

  suspend fun readText(url: String, maxAgeMillis: Long, headers: Map<String, String> = emptyMap()): String {
    cache.read(url, maxAgeMillis)?.let { return it }
    val body = http.readText(url, headers)
    cache.write(url, body)
    return body
  }

  suspend fun readJson(url: String, maxAgeMillis: Long, headers: Map<String, String> = emptyMap()): JsonElement =
    Json.parseToJsonElement(readText(url, maxAgeMillis, headers))
}

// --- Navigazione JSON senza modelli: ogni campo mancante e' una decisione esplicita. ---

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal fun JsonElement?.at(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

internal fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

/** m/s -> km/h: la valuta canonica del vento e' il km/h. */
internal fun Double.metersPerSecondToKmh(): Double = this * 3.6

internal fun Double.mphToKmh(): Double = this * 1.609344
