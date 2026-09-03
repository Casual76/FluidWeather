package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.provider.ChatProvider
import dev.pampa.fluidweather.core.ai.provider.ChatRequest
import dev.pampa.fluidweather.core.ai.provider.Message
import dev.pampa.fluidweather.core.ai.provider.ReasoningLevel
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.core.weather.asArray
import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lo stadio 1: una chiamata piccola, senza ragionamento, con uscita strutturata, che sceglie
 * fino a quattro gruppi di tool per la domanda. Non fa mai fallire una domanda: qualsiasi
 * problema porta a un insieme di ripiego ragionevole. Su OpenRouter non si usa.
 */
class GroupClassifier {

  val schema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "gruppi",
          buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { ToolGroup.entries.forEach { add(JsonPrimitive(it.id)) } }) })
            put("maxItems", MAX_GROUPS)
          },
        )
      },
    )
    put("required", buildJsonArray { add(JsonPrimitive("gruppi")) })
    put("additionalProperties", false)
  }

  fun prompt(language: String, actionsEnabled: Boolean): String {
    val groups = ToolGroup.entries
      .filter { actionsEnabled || it != ToolGroup.APP }
      .joinToString("\n") { "- ${it.id}: ${it.hint}" }
    return if (language == "it") {
      "Sei il selettore di strumenti di un assistente meteo. Data la domanda dell'utente, scegli i gruppi di strumenti " +
        "strettamente necessari per rispondere (da 1 a $MAX_GROUPS, i minimi indispensabili). Rispondi solo con il JSON {\"gruppi\": [...]}.\n" +
        "Gruppi:\n$groups"
    } else {
      "You select tools for a weather assistant. Given the user's question, pick the tool groups strictly needed to answer " +
        "(1 to $MAX_GROUPS, as few as possible). Reply only with the JSON {\"gruppi\": [...]}.\n" +
        "Groups (ids in Italian):\n$groups"
    }
  }

  suspend fun classify(
    provider: ChatProvider,
    model: String,
    question: String,
    previousQuestion: String?,
    previousGroups: Set<ToolGroup>,
    language: String,
    actionsEnabled: Boolean,
  ): Set<ToolGroup> {
    val user = buildString {
      previousQuestion?.let { append("Domanda precedente: ").append(it.take(300)).append('\n') }
      append("Domanda: ").append(question.take(600))
    }
    val turn = provider.complete(
      ChatRequest(
        model = model,
        messages = listOf(Message.System(prompt(language, actionsEnabled)), Message.User(user)),
        reasoning = ReasoningLevel.NONE,
        jsonSchema = schema,
        maxOutputTokens = 120,
        temperature = 0.0,
      ),
    )
    return parse(turn.message.text, actionsEnabled) ?: fallback(previousGroups)
  }

  fun parse(text: String?, actionsEnabled: Boolean): Set<ToolGroup>? {
    val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val json = runCatching { Json.parseToJsonElement(raw.substringAfter("```json", raw).substringBefore("```").trim()) }.getOrNull() ?: return null
    val groups = json["gruppi"].asArray().mapNotNull { ToolGroup.fromId(it.string()) }
      .filter { actionsEnabled || it != ToolGroup.APP }
      .distinct()
      .take(MAX_GROUPS)
    return groups.toSet().takeIf { it.isNotEmpty() }
  }

  fun fallback(previousGroups: Set<ToolGroup>): Set<ToolGroup> =
    (setOf(ToolGroup.HOURLY, ToolGroup.NOWCAST, ToolGroup.PRECIP) + previousGroups).take(MAX_GROUPS + 1).toSet()

  companion object {
    const val MAX_GROUPS = 4
  }
}
