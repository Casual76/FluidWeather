package dev.pampa.fluidweather.core.ai.provider

import dev.pampa.fluidweather.core.ai.keys.AiDefaults
import dev.pampa.fluidweather.core.weather.asArray
import dev.pampa.fluidweather.core.weather.double
import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import kotlinx.serialization.json.JsonElement

/**
 * Dai cataloghi grezzi dei tre provider agli elenchi che l'utente sfoglia: chat da una parte,
 * trascrizione dall'altra, tutto il resto (embedding, immagini, voci, guardie) fuori. Le regole
 * sono per sottostringa perche' e' l'unica cosa che i cataloghi promettono; i test le fissano su
 * elenchi veri salvati.
 */
object ModelCatalog {

  private val groqNotChat = listOf("tts", "orpheus", "playai", "guard", "safeguard", "embed", "compound", "moderation")
  private val geminiNotChat = listOf("embedding", "imagen", "image", "tts", "veo", "robotics", "computer-use", "gemma", "transcribe", "live", "native-audio", "aqa")
  private val openRouterNotChat = listOf("embedding", "embed", "imagen", "image", "tts", "veo", "guard", "moderation", "rerank", "whisper", "transcribe", "-stt")

  fun groq(body: JsonElement?): ModelCatalogue {
    val entries = body["data"].asArray()
    val chat = mutableListOf<ModelInfo>()
    val stt = mutableListOf<ModelInfo>()
    entries.forEach { entry ->
      val id = entry["id"].string()?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
      if (entry["active"].string()?.toBooleanStrictOrNull() == false) return@forEach
      val lower = id.lowercase()
      when {
        "whisper" in lower -> stt += ModelInfo(id, id, ModelKind.STT)
        groqNotChat.any { it in lower } -> Unit
        else -> chat += ModelInfo(
          id = id,
          displayName = id,
          kind = ModelKind.CHAT,
          contextWindow = entry["context_window"].double()?.toInt(),
          maxOutputTokens = entry["max_completion_tokens"].double()?.toInt(),
          supportsTools = true,
          supportsReasoning = "qwen" in lower || "gpt-oss" in lower || "deepseek" in lower || "r1" in lower,
        )
      }
    }
    return ModelCatalogue(
      chat = preferredFirst(chat, AiDefaults.GROQ_CHAT),
      stt = preferredFirst(stt, AiDefaults.GROQ_STT),
    )
  }

  fun gemini(models: JsonElement?): ModelCatalogue {
    val chat = mutableListOf<ModelInfo>()
    val stt = mutableListOf<ModelInfo>()
    models.asArray().forEach { entry ->
      val id = entry["name"].string()?.removePrefix("models/")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
      val lower = id.lowercase()
      val methods = entry["supportedGenerationMethods"].asArray().mapNotNull { it.string() }
      val info = ModelInfo(
        id = id,
        displayName = entry["displayName"].string() ?: id,
        kind = ModelKind.OTHER,
        contextWindow = entry["inputTokenLimit"].double()?.toInt(),
        maxOutputTokens = entry["outputTokenLimit"].double()?.toInt(),
        supportsReasoning = entry["thinking"].string()?.toBooleanStrictOrNull() ?: ("gemini-3" in lower || "2.5" in lower),
        audioInput = true,
      )
      when {
        "transcribe" in lower && "live" !in lower -> stt += info.copy(kind = ModelKind.STT)
        "generateContent" !in methods -> Unit
        geminiNotChat.any { it in lower } -> Unit
        else -> chat += info.copy(kind = ModelKind.CHAT)
      }
    }
    // I flash sanno trascrivere anche loro, con l'audio inline: in coda, dichiarati come ripiego.
    val flashFallbacks = chat.filter { "flash" in it.id.lowercase() && "lite" !in it.id.lowercase() }
      .map { it.copy(kind = ModelKind.STT, displayName = "${it.displayName} (audio inline)") }
    return ModelCatalogue(
      chat = preferredFirst(chat, AiDefaults.GEMINI_CHAT),
      stt = preferredFirst(stt, AiDefaults.GEMINI_STT) + flashFallbacks,
    )
  }

  fun openRouter(body: JsonElement?): ModelCatalogue {
    val chat = mutableListOf<ModelInfo>()
    val stt = mutableListOf<ModelInfo>()
    body["data"].asArray().forEach { entry ->
      val id = entry["id"].string()?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
      val lower = id.lowercase()
      val parameters = entry["supported_parameters"].asArray().mapNotNull { it.string() }
      val inputs = entry["architecture"]["input_modalities"].asArray().mapNotNull { it.string() }
      val outputs = entry["architecture"]["output_modalities"].asArray().mapNotNull { it.string() }
      val pricing = entry["pricing"]
      val prompt = pricing["prompt"].string()?.toDoubleOrNull() ?: pricing["prompt"].double()
      val completion = pricing["completion"].string()?.toDoubleOrNull() ?: pricing["completion"].double()
      val free = lower.endsWith(":free") || (prompt == 0.0 && completion == 0.0)
      val info = ModelInfo(
        id = id,
        displayName = entry["name"].string() ?: id,
        kind = ModelKind.OTHER,
        contextWindow = entry["context_length"].double()?.toInt() ?: entry["top_provider"]["context_length"].double()?.toInt(),
        maxOutputTokens = entry["top_provider"]["max_completion_tokens"].double()?.toInt(),
        supportsTools = "tools" in parameters,
        supportsReasoning = "reasoning" in parameters || "include_reasoning" in parameters,
        audioInput = "audio" in inputs,
        free = free,
        pricePromptPerM = prompt?.let { it * 1_000_000 },
        priceCompletionPerM = completion?.let { it * 1_000_000 },
      )
      val transcription = "transcription" in outputs || "whisper" in lower || lower.endsWith("-stt") || lower.contains("transcribe")
      when {
        transcription && "audio" in inputs -> stt += info.copy(kind = ModelKind.STT)
        transcription -> stt += info.copy(kind = ModelKind.STT)
        "text" !in outputs && outputs.isNotEmpty() -> Unit
        openRouterNotChat.any { it in lower } -> Unit
        !info.supportsTools -> Unit
        else -> chat += info.copy(kind = ModelKind.CHAT)
      }
    }
    return ModelCatalogue(
      chat = chat.sortedWith(compareBy<ModelInfo> { !it.free }.thenBy { it.displayName.lowercase() }),
      stt = preferredFirst(stt, AiDefaults.OPENROUTER_STT),
    )
  }

  /** Il preferito in testa, poi per contesto decrescente e nome: l'ordine di un elenco da scegliere. */
  fun preferredFirst(models: List<ModelInfo>, preferred: String): List<ModelInfo> =
    models.distinctBy { it.id }.sortedWith(
      compareBy<ModelInfo> { it.id != preferred }
        .thenByDescending { it.contextWindow ?: 0 }
        .thenBy { it.id.lowercase() },
    )
}
