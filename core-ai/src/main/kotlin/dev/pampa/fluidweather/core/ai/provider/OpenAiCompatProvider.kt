package dev.pampa.fluidweather.core.ai.provider

import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.net.AiErrorMapper
import dev.pampa.fluidweather.core.ai.net.AiHttp
import dev.pampa.fluidweather.core.ai.net.RateLimitInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Quello che Groq e OpenRouter hanno in comune oltre al dialetto: la costruzione della richiesta,
 * il giro di auto-riparazione (un 400 che nomina un parametro opzionale -> si ritenta senza), lo
 * stream con il suo stato. Le differenze (base URL, header, campi extra) stanno nelle due
 * sottoclassi, in un metodo ciascuna.
 */
abstract class OpenAiCompatProvider(
  protected val http: AiHttp,
  protected val baseUrl: String,
  private val apiKey: String,
) : ChatProvider {

  protected open val extraHeaders: Map<String, String> = emptyMap()

  protected fun headers(): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders

  /** I campi che solo questo provider aggiunge (reasoning nel suo formato, routing, uso). */
  protected abstract fun JsonObjectBuilder.providerFields(request: ChatRequest, stream: Boolean, dropped: Set<String>)

  /** I nomi dei campi opzionali che un 400 puo' farci togliere, in ordine di sospetto. */
  protected open val optionalFields: List<String> = listOf("reasoning_effort", "reasoning_format", "reasoning", "response_format", "parallel_tool_calls", "stream_options")

  protected fun body(request: ChatRequest, stream: Boolean, dropped: Set<String>): JsonObject = buildJsonObject {
    put("model", request.model)
    put("messages", OpenAiCompatCodec.messages(request.messages))
    if (request.tools.isNotEmpty()) {
      put("tools", OpenAiCompatCodec.tools(request.tools))
      put("tool_choice", OpenAiCompatCodec.toolChoice(request.toolChoice))
      if ("parallel_tool_calls" !in dropped) put("parallel_tool_calls", request.parallelToolCalls)
    }
    request.jsonSchema?.let { if ("response_format" !in dropped) put("response_format", OpenAiCompatCodec.responseFormat(it)) }
    request.maxOutputTokens?.let { put("max_completion_tokens", it) }
    request.temperature?.let { put("temperature", it) }
    if (stream) {
      put("stream", true)
      if ("stream_options" !in dropped) put("stream_options", buildJsonObject { put("include_usage", true) })
    }
    providerFields(request, stream, dropped)
  }

  override suspend fun complete(request: ChatRequest): ChatTurn {
    var dropped = emptySet<String>()
    while (true) {
      try {
        val response = http.postJson("$baseUrl/chat/completions", headers(), body(request, stream = false, dropped))
        return OpenAiCompatCodec.parseCompletion(response.body, response.rateLimit, id)
      } catch (e: AiError.BadRequest) {
        val culprit = optionalFields.firstOrNull { it !in dropped && it in e.message.orEmpty().lowercase() }
          ?: throw e
        dropped = dropped + culprit
      }
    }
  }

  override fun stream(request: ChatRequest): Flow<ChatDelta> = flow {
    var dropped = emptySet<String>()
    while (true) {
      val state = OpenAiCompatCodec.StreamState()
      var rateLimit = RateLimitInfo.EMPTY
      var started = false
      try {
        http.postJsonStream(
          url = "$baseUrl/chat/completions",
          headers = headers(),
          body = body(request, stream = true, dropped),
          onHeaders = { rateLimit = AiErrorMapper.parseRateLimit(it) },
        ).collect { payload ->
          started = true
          OpenAiCompatCodec.parseStreamChunk(payload, state).forEach { emit(it) }
        }
        OpenAiCompatCodec.rawFromStream(state)?.let { emit(ChatDelta.Raw(it)) }
        emit(ChatDelta.Finish(OpenAiCompatCodec.finishReason(state.finish, hasToolCalls = false), state.usage, rateLimit))
        return@flow
      } catch (e: AiError.BadRequest) {
        if (started) throw e
        val culprit = optionalFields.firstOrNull { it !in dropped && it in e.message.orEmpty().lowercase() }
          ?: throw e
        dropped = dropped + culprit
      }
    }
  }
}
