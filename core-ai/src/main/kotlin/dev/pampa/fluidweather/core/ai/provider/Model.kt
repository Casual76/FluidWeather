package dev.pampa.fluidweather.core.ai.provider

import dev.pampa.fluidweather.core.ai.net.RateLimitInfo
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Il modello neutro della conversazione: quello che l'orchestratore vede, qualunque provider ci
 * sia sotto. Gli adapter lo traducono nel dialetto di ciascuno (OpenAI per Groq e OpenRouter,
 * `contents/parts` per Gemini) e ne conservano in [Message.Assistant.raw] cio' che il provider
 * pretende di rivedere identico al turno dopo (le thought signature di Gemini, i
 * `reasoning_details` di OpenRouter).
 */
sealed interface Message {
  data class System(val text: String) : Message
  data class User(val text: String) : Message
  data class Assistant(
    val text: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Le parti grezze del provider che le ha prodotte, da rimandare intatte; null se non servono. */
    val raw: JsonElement? = null,
    val rawProvider: ProviderId? = null,
  ) : Message

  data class ToolResult(val callId: String, val name: String, val content: String) : Message
}

data class ToolCall(
  val id: String,
  val name: String,
  val arguments: JsonObject,
  /** Gemini 3: la firma del pensiero che ha prodotto la chiamata; va rimandata verbatim. */
  val thoughtSignature: String? = null,
)

/** Una funzione offerta al modello: nome, cosa fa, e lo schema JSON dei parametri. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

sealed interface ToolChoice {
  data object Auto : ToolChoice
  data object None : ToolChoice
  data object Required : ToolChoice
  data class Named(val name: String) : ToolChoice
}

/** Quanto pensare in questa chiamata; ogni adapter lo mappa sui suoi parametri. */
enum class ReasoningLevel { NONE, LOW, MEDIUM, HIGH }

data class ChatRequest(
  val model: String,
  val messages: List<Message>,
  val tools: List<ToolSpec> = emptyList(),
  val toolChoice: ToolChoice = ToolChoice.Auto,
  val parallelToolCalls: Boolean = true,
  val reasoning: ReasoningLevel = ReasoningLevel.MEDIUM,
  /** Uscita strutturata (stadio 1): lo schema JSON della risposta attesa. */
  val jsonSchema: JsonObject? = null,
  val maxOutputTokens: Int? = null,
  val temperature: Double? = null,
)

enum class FinishReason { STOP, TOOL_CALLS, LENGTH, BLOCKED, OTHER }

data class Usage(
  val promptTokens: Int,
  val completionTokens: Int,
  val totalTokens: Int,
  /** Solo OpenRouter lo dice (in dollari); gli altri sono gratuiti o non lo espongono. */
  val costUsd: Double? = null,
) {
  operator fun plus(other: Usage): Usage = Usage(
    promptTokens = promptTokens + other.promptTokens,
    completionTokens = completionTokens + other.completionTokens,
    totalTokens = totalTokens + other.totalTokens,
    costUsd = if (costUsd == null && other.costUsd == null) null else (costUsd ?: 0.0) + (other.costUsd ?: 0.0),
  )
}

data class ChatTurn(
  val message: Message.Assistant,
  val finishReason: FinishReason,
  val usage: Usage?,
  val rateLimit: RateLimitInfo,
)

/** I pezzi di uno stream, nell'ordine in cui arrivano. */
sealed interface ChatDelta {
  data class Text(val text: String) : ChatDelta

  /** Un frammento di tool call; l'indice tiene insieme i pezzi della stessa chiamata. */
  data class ToolCallPart(
    val index: Int,
    val id: String?,
    val name: String?,
    val argumentsFragment: String?,
    val thoughtSignature: String? = null,
  ) : ChatDelta

  /** Parti grezze del provider da conservare (thought signature, reasoning_details). */
  data class Raw(val raw: JsonElement) : ChatDelta

  data class Finish(val reason: FinishReason, val usage: Usage?, val rateLimit: RateLimitInfo) : ChatDelta
}

data class Transcript(val text: String, val language: String?)

data class TranscribeOptions(val model: String, val language: String?, val prompt: String?)

enum class ModelKind { CHAT, STT, OTHER }

/** Quello che serve per scegliere un modello in un elenco; i prezzi solo dove esistono. */
data class ModelInfo(
  val id: String,
  val displayName: String,
  val kind: ModelKind,
  val contextWindow: Int? = null,
  val maxOutputTokens: Int? = null,
  val supportsTools: Boolean = true,
  val supportsReasoning: Boolean = false,
  val audioInput: Boolean = false,
  val free: Boolean = false,
  /** Dollari per milione di token, in ingresso e in uscita; null se il provider non prezza. */
  val pricePromptPerM: Double? = null,
  val priceCompletionPerM: Double? = null,
)

data class ModelCatalogue(val chat: List<ModelInfo>, val stt: List<ModelInfo>)

interface ChatProvider {
  val id: ProviderId

  suspend fun complete(request: ChatRequest): ChatTurn

  fun stream(request: ChatRequest): Flow<ChatDelta>

  suspend fun listModels(): ModelCatalogue

  suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript
}
