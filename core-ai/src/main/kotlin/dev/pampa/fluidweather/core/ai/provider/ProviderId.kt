package dev.pampa.fluidweather.core.ai.provider

/**
 * I tre servizi che l'assistente sa usare, ognuno con la chiave dell'utente. L'ordine di
 * dichiarazione e' anche l'ordine di default con cui rispondono (Groq -> Gemini -> OpenRouter):
 * Groq perche' e' il piu' veloce e gratuito, Gemini perche' e' gratuito e largo, OpenRouter per
 * ultimo perche' i suoi modelli migliori costano.
 */
enum class ProviderId(val id: String, val label: String) {
  GROQ("groq", "Groq"),
  GEMINI("gemini", "Gemini"),
  OPENROUTER("openrouter", "OpenRouter");

  companion object {
    fun fromId(id: String?): ProviderId? = entries.firstOrNull { it.id == id }

    /** L'ordine con cui l'app parte, prima che l'utente lo tocchi. */
    val defaultOrder: List<ProviderId> = entries.toList()
  }
}
