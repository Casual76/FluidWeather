package dev.pampa.fluidweather.core.ai.provider

import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.net.AiErrorMapper
import dev.pampa.fluidweather.core.ai.net.AiHttp
import dev.pampa.fluidweather.core.ai.net.RateLimitInfo
import dev.pampa.fluidweather.core.weather.asArray
import dev.pampa.fluidweather.core.weather.at
import dev.pampa.fluidweather.core.weather.double
import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gemini via `generateContent` (stabile; l'Interactions API e' ancora beta). Le parti del modello
 * si conservano grezze in [Message.Assistant.raw] e si rimandano identiche: Gemini 3 pretende di
 * rivedere le sue thought signature sulle `functionCall`, e una parte ricostruita a mano senza
 * firma e' un 400. Per le chiamate nate altrove (dopo un cambio di provider) c'e' il valore di
 * bypass documentato.
 */
class GeminiProvider(private val http: AiHttp, private val apiKey: String) : ChatProvider {

  override val id: ProviderId = ProviderId.GEMINI

  private fun headers(): Map<String, String> = mapOf("x-goog-api-key" to apiKey)

  /** Le degradazioni successive quando un 400 nomina un campo: prima il livello, poi via tutto. */
  private enum class Fallback { NONE, THINKING_LOW, NO_THINKING, OPENAPI_SCHEMA }

  override suspend fun complete(request: ChatRequest): ChatTurn {
    var fallbacks = emptySet<Fallback>()
    while (true) {
      try {
        val response = http.postJson(url(request.model, "generateContent"), headers(), body(request, fallbacks))
        return GeminiCodec.parseResponse(response.body, response.rateLimit)
      } catch (e: AiError.BadRequest) {
        fallbacks = nextFallback(e, fallbacks) ?: throw e
      }
    }
  }

  override fun stream(request: ChatRequest): Flow<ChatDelta> = flow {
    var fallbacks = emptySet<Fallback>()
    while (true) {
      var started = false
      var rateLimit = RateLimitInfo.EMPTY
      val state = GeminiCodec.StreamState()
      try {
        http.postJsonStream(
          url = url(request.model, "streamGenerateContent") + "?alt=sse",
          headers = headers(),
          body = body(request, fallbacks),
          onHeaders = { rateLimit = AiErrorMapper.parseRateLimit(it) },
        ).collect { payload ->
          started = true
          GeminiCodec.parseStreamChunk(payload, state).forEach { emit(it) }
        }
        state.raw()?.let { emit(ChatDelta.Raw(it)) }
        emit(ChatDelta.Finish(state.finish ?: FinishReason.STOP, state.usage, rateLimit))
        return@flow
      } catch (e: AiError.BadRequest) {
        if (started) throw e
        fallbacks = nextFallback(e, fallbacks) ?: throw e
      }
    }
  }

  private fun nextFallback(e: AiError.BadRequest, current: Set<Fallback>): Set<Fallback>? {
    val message = e.message.orEmpty().lowercase()
    return when {
      "thinking" in message && Fallback.THINKING_LOW !in current -> current + Fallback.THINKING_LOW
      "thinking" in message && Fallback.NO_THINKING !in current -> current + Fallback.NO_THINKING
      ("schema" in message || "response_json" in message) && Fallback.OPENAPI_SCHEMA !in current -> current + Fallback.OPENAPI_SCHEMA
      else -> null
    }
  }

  private fun body(request: ChatRequest, fallbacks: Set<Fallback>): JsonObject = buildJsonObject {
    GeminiCodec.systemInstruction(request.messages)?.let { put("systemInstruction", it) }
    put("contents", GeminiCodec.contents(request.messages))
    if (request.tools.isNotEmpty()) {
      put("tools", buildJsonArray { add(buildJsonObject { put("functionDeclarations", GeminiCodec.declarations(request.tools)) }) })
      put("toolConfig", buildJsonObject { put("functionCallingConfig", GeminiCodec.callingConfig(request.toolChoice)) })
    }
    put(
      "generationConfig",
      buildJsonObject {
        if (Fallback.NO_THINKING !in fallbacks) {
          val level = if (Fallback.THINKING_LOW in fallbacks) "low" else GeminiCodec.thinkingLevel(request.reasoning)
          put("thinkingConfig", buildJsonObject { put("thinkingLevel", level) })
        }
        request.maxOutputTokens?.let { put("maxOutputTokens", it) }
        request.temperature?.let { put("temperature", it) }
        request.jsonSchema?.let { schema ->
          put("responseMimeType", "application/json")
          if (Fallback.OPENAPI_SCHEMA in fallbacks) {
            put("responseSchema", GeminiSchema.sanitize(schema))
          } else {
            put("responseJsonSchema", schema)
          }
        }
      },
    )
  }

  override suspend fun listModels(): ModelCatalogue {
    val models = mutableListOf<JsonElement>()
    var pageToken: String? = null
    do {
      val url = "$BASE_URL/models?pageSize=200" + (pageToken?.let { "&pageToken=$it" } ?: "")
      val body = http.getJson(url, headers()).body
      models += body["models"].asArray()
      pageToken = body["nextPageToken"].string()?.takeIf { it.isNotBlank() }
    } while (pageToken != null && models.size < 2_000)
    return ModelCatalog.gemini(JsonArray(models))
  }

  override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript {
    val prompt = buildString {
      append("Transcribe the speech in this audio verbatim")
      options.language?.let { append(" (language: $it)") }
      append(". Reply with the transcript only, no comments.")
      options.prompt?.takeIf { it.isNotBlank() }?.let { append(" Context: ").append(it) }
    }
    val body = buildJsonObject {
      put(
        "contents",
        buildJsonArray {
          add(
            buildJsonObject {
              put(
                "parts",
                buildJsonArray {
                  add(buildJsonObject { put("text", prompt) })
                  add(
                    buildJsonObject {
                      put(
                        "inlineData",
                        buildJsonObject {
                          put("mimeType", mime)
                          put("data", Base64.getEncoder().encodeToString(audio.readBytes()))
                        },
                      )
                    },
                  )
                },
              )
            },
          )
        },
      )
      put("generationConfig", buildJsonObject { put("temperature", 0) })
    }
    val response = try {
      http.postJson(url(options.model, "generateContent"), headers(), body)
    } catch (e: AiError.BadRequest) {
      // Il modello di trascrizione puo' non esistere per questa chiave: un flash lo fa lo stesso.
      if (options.model == FALLBACK_TRANSCRIBE_MODEL) throw e
      http.postJson(url(FALLBACK_TRANSCRIBE_MODEL, "generateContent"), headers(), body)
    }
    val text = response.body["candidates"].at(0)["content"]["parts"].asArray()
      .mapNotNull { it["text"].string() }
      .joinToString("")
      .trim()
    return Transcript(text = text, language = options.language)
  }

  private fun url(model: String, method: String): String = "$BASE_URL/models/${model.removePrefix("models/")}:$method"

  companion object {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val FALLBACK_TRANSCRIBE_MODEL = "gemini-3.6-flash"
  }
}

/** Il dialetto `contents/parts` di Gemini, puro e testabile. */
object GeminiCodec {

  /** Il valore di bypass documentato per le functionCall che Gemini non ha firmato lui. */
  const val SKIP_SIGNATURE = "skip_thought_signature_validator"

  fun systemInstruction(messages: List<Message>): JsonObject? {
    val text = messages.filterIsInstance<Message.System>().joinToString("\n\n") { it.text }.trim()
    if (text.isEmpty()) return null
    return buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) }) }
  }

  fun contents(messages: List<Message>): JsonArray = buildJsonArray {
    var pendingResults = mutableListOf<Message.ToolResult>()
    fun flushResults() {
      if (pendingResults.isEmpty()) return
      add(
        buildJsonObject {
          put("role", "user")
          put(
            "parts",
            buildJsonArray {
              pendingResults.forEach { result ->
                add(
                  buildJsonObject {
                    put(
                      "functionResponse",
                      buildJsonObject {
                        put("name", result.name)
                        put("response", buildJsonObject { put("content", result.content) })
                      },
                    )
                  },
                )
              }
            },
          )
        },
      )
      pendingResults = mutableListOf()
    }
    messages.forEach { message ->
      when (message) {
        is Message.System -> Unit
        is Message.User -> {
          flushResults()
          add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", message.text) }) }) })
        }
        is Message.Assistant -> {
          flushResults()
          val rawParts = (message.raw as? JsonArray)?.takeIf { message.rawProvider == ProviderId.GEMINI && it.isNotEmpty() }
          add(
            buildJsonObject {
              put("role", "model")
              put(
                "parts",
                rawParts ?: buildJsonArray {
                  message.text?.takeIf { it.isNotBlank() }?.let { add(buildJsonObject { put("text", it) }) }
                  message.toolCalls.forEach { call ->
                    add(
                      buildJsonObject {
                        put("functionCall", buildJsonObject { put("name", call.name); put("args", call.arguments) })
                        put("thoughtSignature", call.thoughtSignature ?: SKIP_SIGNATURE)
                      },
                    )
                  }
                  if (message.text.isNullOrBlank() && message.toolCalls.isEmpty()) add(buildJsonObject { put("text", "") })
                },
              )
            },
          )
        }
        is Message.ToolResult -> pendingResults += message
      }
    }
    flushResults()
  }

  fun declarations(tools: List<ToolSpec>): JsonArray = buildJsonArray {
    tools.forEach { tool ->
      add(
        buildJsonObject {
          put("name", tool.name)
          put("description", tool.description)
          put("parameters", GeminiSchema.sanitize(tool.parameters))
        },
      )
    }
  }

  fun callingConfig(choice: ToolChoice): JsonObject = buildJsonObject {
    when (choice) {
      ToolChoice.Auto -> put("mode", "AUTO")
      ToolChoice.None -> put("mode", "NONE")
      ToolChoice.Required -> put("mode", "ANY")
      is ToolChoice.Named -> {
        put("mode", "ANY")
        put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(choice.name)) })
      }
    }
  }

  fun thinkingLevel(level: ReasoningLevel): String = when (level) {
    ReasoningLevel.NONE -> "minimal"
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH -> "high"
  }

  fun usage(json: JsonElement?): Usage? {
    val prompt = json["promptTokenCount"].double()?.toInt() ?: return null
    val completion = json["candidatesTokenCount"].double()?.toInt() ?: 0
    val thoughts = json["thoughtsTokenCount"].double()?.toInt() ?: 0
    val total = json["totalTokenCount"].double()?.toInt() ?: (prompt + completion + thoughts)
    return Usage(prompt, completion + thoughts, total)
  }

  fun finishReason(raw: String?, hasCalls: Boolean): FinishReason = when (raw) {
    "STOP", null -> if (hasCalls) FinishReason.TOOL_CALLS else FinishReason.STOP
    "MAX_TOKENS" -> FinishReason.LENGTH
    "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" -> FinishReason.BLOCKED
    else -> FinishReason.OTHER
  }

  fun parseResponse(body: JsonElement?, rateLimit: RateLimitInfo): ChatTurn {
    body["promptFeedback"]["blockReason"].string()?.let { throw AiError.BadRequest(200, "bloccato: $it") }
    val candidate = body["candidates"].at(0) ?: throw AiError.Parse("nessun candidato nella risposta")
    val parts = candidate["content"]["parts"].asArray().filter { it["thought"].string()?.toBooleanStrictOrNull() != true }
    val text = parts.mapNotNull { it["text"].string() }.joinToString("").takeIf { it.isNotEmpty() }
    val calls = parts.mapNotNull { part ->
      val call = part["functionCall"] ?: return@mapNotNull null
      val name = call["name"].string() ?: return@mapNotNull null
      ToolCall(
        id = call["id"].string()?.takeIf { it.isNotBlank() } ?: ToolCallAssembler.localCallId(),
        name = name,
        arguments = (call["args"] as? JsonObject) ?: JsonObject(emptyMap()),
        thoughtSignature = part["thoughtSignature"].string(),
      )
    }
    val reason = candidate["finishReason"].string()
    val assistant = if (reason == "MALFORMED_FUNCTION_CALL") {
      Message.Assistant(text = "(chiamata malformata, riprovo)", toolCalls = emptyList())
    } else {
      Message.Assistant(text, calls, raw = JsonArray(parts), rawProvider = ProviderId.GEMINI)
    }
    return ChatTurn(
      message = assistant,
      finishReason = if (reason == "MALFORMED_FUNCTION_CALL") FinishReason.OTHER else finishReason(reason, calls.isNotEmpty()),
      usage = usage(body["usageMetadata"]),
      rateLimit = rateLimit,
    )
  }

  class StreamState {
    val parts = mutableListOf<JsonElement>()
    var callIndex = 0
    var finish: FinishReason? = null
    var usage: Usage? = null
    fun raw(): JsonArray? = parts.takeIf { it.isNotEmpty() }?.let { JsonArray(it) }
  }

  fun parseStreamChunk(payload: String, state: StreamState): List<ChatDelta> {
    val chunk = runCatching { Json.parseToJsonElement(payload) }.getOrElse { throw AiError.Parse("pezzo di stream non JSON", it) }
    chunk["error"]?.let { throw AiError.Server(200, it["message"].string() ?: "errore Gemini durante lo stream") }
    chunk["promptFeedback"]["blockReason"].string()?.let { throw AiError.BadRequest(200, "bloccato: $it") }
    val deltas = mutableListOf<ChatDelta>()
    val candidate = chunk["candidates"].at(0)
    candidate["content"]["parts"].asArray().forEach { part ->
      if (part["thought"].string()?.toBooleanStrictOrNull() == true) return@forEach
      state.parts += part
      part["text"].string()?.takeIf { it.isNotEmpty() }?.let { deltas += ChatDelta.Text(it) }
      part["functionCall"]?.let { call ->
        deltas += ChatDelta.ToolCallPart(
          index = state.callIndex++,
          id = call["id"].string(),
          name = call["name"].string(),
          argumentsFragment = (call["args"] as? JsonObject)?.toString() ?: "{}",
          thoughtSignature = part["thoughtSignature"].string(),
        )
      }
    }
    candidate["finishReason"].string()?.let { reason ->
      state.finish = if (reason == "MALFORMED_FUNCTION_CALL") FinishReason.OTHER else finishReason(reason, state.callIndex > 0)
    }
    usage(chunk["usageMetadata"])?.let { state.usage = it }
    return deltas
  }
}

/**
 * Gemini accetta un sottoinsieme di JSON Schema e risponde 400 a `additionalProperties`, `$schema`,
 * `default`, `examples`: si tengono solo le chiavi che capisce, ricorsivamente.
 */
object GeminiSchema {
  private val allowed = setOf("type", "description", "enum", "properties", "required", "items", "nullable", "format", "minimum", "maximum", "anyOf", "title")

  fun sanitize(schema: JsonObject): JsonObject = buildJsonObject {
    schema.forEach { (key, value) ->
      if (key !in allowed) return@forEach
      when {
        key == "properties" && value is JsonObject -> put(
          key,
          buildJsonObject { value.forEach { (name, sub) -> put(name, if (sub is JsonObject) sanitize(sub) else sub) } },
        )
        key == "items" && value is JsonObject -> put(key, sanitize(value))
        key == "anyOf" && value is JsonArray -> put(key, buildJsonArray { value.forEach { add(if (it is JsonObject) sanitize(it) else it) } })
        else -> put(key, value)
      }
    }
  }
}
