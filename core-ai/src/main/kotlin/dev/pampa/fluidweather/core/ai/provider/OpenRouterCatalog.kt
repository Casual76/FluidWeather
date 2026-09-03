package dev.pampa.fluidweather.core.ai.provider

/**
 * Le euristiche sul catalogo di OpenRouter: quale modello gratuito con tool diventa il
 * predefinito alla verifica della chiave, e quali otto vanno sotto "Consigliati". Sono
 * euristiche, non liste: i modelli `:free` cambiano ogni settimana e una lista nel codice
 * sarebbe vecchia alla prima release. I pesi premiano cio' che conta per un assistente con tool
 * su dati meteo: ragionamento, contesto per i risultati dei tool, famiglie che sanno chiamare
 * funzioni davvero.
 */
object OpenRouterCatalog {

  /** Famiglie con function calling affidabile; Gemma resta fuori perche' non ha tool. */
  private val knownFamilies = listOf("qwen", "nemotron", "glm", "deepseek", "llama", "mistral", "kimi", "minimax", "gemini", "claude", "gpt", "grok", "hermes", "inkling")

  fun family(id: String): String? = knownFamilies.firstOrNull { it in id.lowercase() }

  fun score(model: ModelInfo): Int {
    if (!model.supportsTools) return Int.MIN_VALUE
    var score = 0
    if (model.supportsReasoning) score += 3
    if ((model.contextWindow ?: 0) >= 64_000) score += 2
    if ((model.contextWindow ?: 0) >= 128_000) score += 1
    if (family(model.id) != null) score += 2
    if ("preview" in model.id.lowercase() || "exp" in model.id.lowercase()) score -= 1
    return score
  }

  /** Il gratuito migliore secondo [score]; null se nessun `:free` sa usare i tool. */
  fun pickDefaultFree(catalogue: ModelCatalogue): ModelInfo? =
    catalogue.chat.filter { it.free && it.supportsTools }
      .maxWithOrNull(compareBy<ModelInfo> { score(it) }.thenByDescending { it.contextWindow ?: 0 })

  /** Fino a otto: i gratuiti idonei in testa, poi per punteggio e prezzo crescente. */
  fun recommended(catalogue: ModelCatalogue, limit: Int = 8): List<ModelInfo> {
    val eligible = catalogue.chat.filter { it.supportsTools && score(it) >= 2 }
    return eligible.sortedWith(
      compareByDescending<ModelInfo> { it.free }
        .thenByDescending { score(it) }
        .thenBy { it.pricePromptPerM ?: Double.MAX_VALUE }
        .thenByDescending { it.contextWindow ?: 0 },
    ).take(limit)
  }
}
