package dev.pampa.fluidweather.core.ai.provider

import dev.pampa.fluidweather.core.ai.net.AiHttp
import dev.pampa.fluidweather.core.weather.double
import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Cosa `GET /key` dice della chiave: crediti, uso, se e' un account senza acquisti. */
data class OpenRouterKeyInfo(
  val label: String?,
  val limitUsd: Double?,
  val limitRemainingUsd: Double?,
  val usageUsd: Double?,
  val usageDailyUsd: Double?,
  val isFreeTier: Boolean,
)

/**
 * OpenRouter: lo stesso dialetto di Groq piu' il routing (`models` di riserva, `provider` con la
 * privacy), il `reasoning` unificato, l'uso col costo. Le trascrizioni viaggiano in JSON base64
 * su `/audio/transcriptions`. Il catalogo e' pubblico e grande: lo legge [ModelCatalog].
 */
class OpenRouterProvider(
  http: AiHttp,
  apiKey: String,
  private val referer: String,
  private val title: String,
  /** Fino a due modelli che OpenRouter prova da solo se il primario fallisce. */
  private val fallbackModels: List<String> = emptyList(),
  private val allowDataCollection: Boolean = false,
) : OpenAiCompatProvider(http, BASE_URL, apiKey) {

  override val id: ProviderId = ProviderId.OPENROUTER

  override val extraHeaders: Map<String, String> = mapOf("HTTP-Referer" to referer, "X-Title" to title)

  override fun JsonObjectBuilder.providerFields(request: ChatRequest, stream: Boolean, dropped: Set<String>) {
    if (fallbackModels.isNotEmpty()) {
      put("models", buildJsonArray { (listOf(request.model) + fallbackModels).distinct().forEach { add(JsonPrimitive(it)) } })
    }
    put(
      "provider",
      buildJsonObject {
        put("data_collection", if (allowDataCollection) "allow" else "deny")
        put("sort", "price")
      },
    )
    put("usage", buildJsonObject { put("include", true) })
    if ("reasoning" !in dropped) {
      put(
        "reasoning",
        buildJsonObject {
          put(
            "effort",
            when (request.reasoning) {
              ReasoningLevel.NONE -> "none"
              ReasoningLevel.LOW -> "low"
              ReasoningLevel.MEDIUM -> "medium"
              ReasoningLevel.HIGH -> "high"
            },
          )
          // Il testo del ragionamento non serve a nessuno qui: solo i dettagli da rimandare.
          put("exclude", true)
        },
      )
    }
  }

  override suspend fun listModels(): ModelCatalogue {
    val response = http.getJson("$BASE_URL/models", headers())
    return ModelCatalog.openRouter(response.body)
  }

  suspend fun keyInfo(): OpenRouterKeyInfo {
    val data = http.getJson("$BASE_URL/key", headers()).body["data"]
    return OpenRouterKeyInfo(
      label = data["label"].string(),
      limitUsd = data["limit"].double(),
      limitRemainingUsd = data["limit_remaining"].double(),
      usageUsd = data["usage"].double(),
      usageDailyUsd = data["usage_daily"].double(),
      isFreeTier = data["is_free_tier"].string()?.toBooleanStrictOrNull() ?: (data["is_free_tier"].double()?.let { it != 0.0 } ?: true),
    )
  }

  override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript {
    val format = when {
      mime.contains("wav") -> "wav"
      mime.contains("mp4") || mime.contains("m4a") -> "m4a"
      mime.contains("ogg") -> "ogg"
      mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
      else -> "wav"
    }
    val body = buildJsonObject {
      put("model", options.model)
      put(
        "input_audio",
        buildJsonObject {
          put("data", Base64.getEncoder().encodeToString(audio.readBytes()))
          put("format", format)
        },
      )
      options.language?.let { put("language", it) }
      put("temperature", 0)
      put("response_format", "json")
    }
    val response = http.postJson("$BASE_URL/audio/transcriptions", headers(), body)
    return Transcript(text = response.body["text"].string()?.trim().orEmpty(), language = options.language)
  }

  companion object {
    const val BASE_URL = "https://openrouter.ai/api/v1"
  }
}
