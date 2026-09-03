package dev.pampa.fluidweather.core.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Ricompone le tool call di uno stream. Groq e OpenRouter mandano il nome nel primo pezzo e gli
 * argomenti a frammenti di stringa JSON; Gemini ogni `functionCall` intera in una parte. Qui si
 * accumula per indice e alla fine si parsa una volta: argomenti malformati diventano
 * `{"_raw": "..."}` e il tool rispondera' con un errore leggibile, invece di far cadere il giro.
 */
class ToolCallAssembler(private val idFactory: () -> String = { localCallId() }) {

  private class Partial(var id: String?, var name: String?, val arguments: StringBuilder, var signature: String?)

  private val partials = linkedMapOf<Int, Partial>()

  fun add(part: ChatDelta.ToolCallPart) {
    val partial = partials.getOrPut(part.index) { Partial(null, null, StringBuilder(), null) }
    part.id?.let { partial.id = it }
    part.name?.let { partial.name = it }
    part.argumentsFragment?.let { partial.arguments.append(it) }
    part.thoughtSignature?.let { partial.signature = it }
  }

  val isEmpty: Boolean get() = partials.isEmpty()

  fun build(): List<ToolCall> = partials.values.mapNotNull { partial ->
    val name = partial.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    ToolCall(
      id = partial.id?.takeIf { it.isNotBlank() } ?: idFactory(),
      name = name,
      arguments = parseArguments(partial.arguments.toString()),
      thoughtSignature = partial.signature,
    )
  }

  companion object {
    fun parseArguments(raw: String): JsonObject {
      val text = raw.trim()
      if (text.isEmpty()) return JsonObject(emptyMap())
      return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: buildJsonObject { put("_raw", JsonPrimitive(text)) }
    }

    /** Un id locale per i provider che non ne danno (Gemini): stile OpenAI, cosi' Groq lo accetta. */
    fun localCallId(): String {
      val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
      return "call_" + (1..24).map { chars.random() }.joinToString("")
    }
  }
}
