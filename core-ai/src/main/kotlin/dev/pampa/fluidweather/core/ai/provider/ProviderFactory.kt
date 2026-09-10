package dev.pampa.fluidweather.core.ai.provider

import dev.pampa.fluidweather.core.ai.keys.AiKeyStore
import dev.pampa.fluidweather.core.ai.keys.AiSettings
import dev.pampa.fluidweather.core.ai.keys.AiSettingsStore
import dev.pampa.fluidweather.core.ai.net.AiHttp

/** Un provider pronto a rispondere, col modello con cui lo si vuole usare adesso. */
data class ReadyProvider(val provider: ChatProvider, val chatModel: String, val sttModel: String, val classifierModel: String?)

/**
 * Costruisce i provider per ogni domanda leggendo le chiavi dal loro deposito: una chiave cambiata
 * nelle impostazioni vale alla domanda dopo, senza riavvii. L'ordine e' quello dell'utente,
 * filtrato sui provider che hanno una chiave verificata.
 */
class ProviderFactory(
  private val http: AiHttp,
  private val keys: AiKeyStore,
  private val settings: AiSettingsStore,
  private val referer: String,
  private val appTitle: String,
) {

  enum class Kind { CHAT, STT }

  suspend fun build(provider: ProviderId, settings: AiSettings): ReadyProvider? {
    val key = keys.key(provider) ?: return null
    val chat = settings.chatModel(provider) ?: if (provider == ProviderId.OPENROUTER) AiDefaultsFallback.openRouterChat else return null
    val client: ChatProvider = when (provider) {
      ProviderId.GROQ -> GroqProvider(http, key)
      ProviderId.GEMINI -> GeminiProvider(http, key)
      ProviderId.OPENROUTER -> OpenRouterProvider(
        http = http,
        apiKey = key,
        referer = referer,
        title = appTitle,
        fallbackModels = settings.openRouterFallbacks,
        denyDataCollection = settings.openRouterDenyDataCollection,
      )
    }
    return ReadyProvider(client, chat, settings.sttModel(provider), settings.classifierModel(provider))
  }

  /** I provider con chiave verificata, nell'ordine scelto per [kind]. */
  suspend fun ordered(kind: Kind): List<ReadyProvider> {
    val current = settings.current()
    val states = keys.currentStates()
    val order = if (kind == Kind.CHAT) current.chatOrder else current.sttOrder
    return order.filter { states[it]?.verified == true }.mapNotNull { build(it, current) }
  }

  /** Un provider con chiave anche se non ancora verificata: per verificarla. */
  suspend fun forVerification(provider: ProviderId): ChatProvider? = build(provider, settings.current())?.provider

  private object AiDefaultsFallback {
    val openRouterChat = dev.pampa.fluidweather.core.ai.keys.AiDefaults.OPENROUTER_CHAT_FALLBACK
  }
}
