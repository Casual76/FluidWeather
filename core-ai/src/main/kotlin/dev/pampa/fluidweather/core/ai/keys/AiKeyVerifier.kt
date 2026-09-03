package dev.pampa.fluidweather.core.ai.keys

import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.provider.ModelCatalogue
import dev.pampa.fluidweather.core.ai.provider.ModelInfo
import dev.pampa.fluidweather.core.ai.provider.ModelKind
import dev.pampa.fluidweather.core.ai.provider.OpenRouterCatalog
import dev.pampa.fluidweather.core.ai.provider.OpenRouterKeyInfo
import dev.pampa.fluidweather.core.ai.provider.OpenRouterProvider
import dev.pampa.fluidweather.core.ai.provider.ProviderFactory
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.weather.asArray
import dev.pampa.fluidweather.core.weather.at
import dev.pampa.fluidweather.core.weather.double
import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** L'esito di una verifica: quanti modelli, e per OpenRouter anche i crediti e il default scelto. */
sealed interface VerifyResult {
  data class Ok(val catalogue: ModelCatalogue, val keyInfo: OpenRouterKeyInfo?, val chosenDefault: String?, val chosenIsFree: Boolean) : VerifyResult
  data object Invalid : VerifyResult
  data class Failed(val error: AiError?) : VerifyResult
}

/**
 * I cataloghi dei modelli su disco (`cacheDir/ai/models-<provider>.json`) e in memoria: la
 * pagina delle impostazioni li mostra subito, e li rinfresca alla verifica, alla richiesta,
 * e al massimo una volta al giorno. Codifica a mano con JsonElement, come tutto il resto.
 */
class ModelCatalogStore(private val directory: File) {

  private val memory = MutableStateFlow<Map<ProviderId, ModelCatalogue>>(emptyMap())
  val catalogues: StateFlow<Map<ProviderId, ModelCatalogue>> = memory

  suspend fun load(provider: ProviderId): ModelCatalogue? {
    memory.value[provider]?.let { return it }
    return withContext(Dispatchers.IO) {
      val file = file(provider)
      if (!file.exists()) return@withContext null
      runCatching { decode(Json.parseToJsonElement(file.readText())) }.getOrNull()?.also { put(provider, it) }
    }
  }

  suspend fun save(provider: ProviderId, catalogue: ModelCatalogue) {
    put(provider, catalogue)
    withContext(Dispatchers.IO) {
      directory.mkdirs()
      runCatching { file(provider).writeText(encode(catalogue).toString()) }
    }
  }

  private fun put(provider: ProviderId, catalogue: ModelCatalogue) {
    memory.value = memory.value + (provider to catalogue)
  }

  private fun file(provider: ProviderId) = File(directory, "models-${provider.id}.json")

  companion object {
    fun encode(catalogue: ModelCatalogue): JsonElement = buildJsonObject {
      put("v", 1)
      put("chat", buildJsonArray { catalogue.chat.forEach { add(encode(it)) } })
      put("stt", buildJsonArray { catalogue.stt.forEach { add(encode(it)) } })
    }

    private fun encode(model: ModelInfo): JsonElement = buildJsonObject {
      put("id", model.id)
      put("name", model.displayName)
      put("kind", model.kind.name)
      model.contextWindow?.let { put("ctx", it) }
      model.maxOutputTokens?.let { put("out", it) }
      put("tools", model.supportsTools)
      put("reasoning", model.supportsReasoning)
      put("audio", model.audioInput)
      put("free", model.free)
      model.pricePromptPerM?.let { put("pin", it) }
      model.priceCompletionPerM?.let { put("pout", it) }
    }

    fun decode(json: JsonElement): ModelCatalogue? {
      if (json["v"].double()?.toInt() != 1) return null
      fun model(e: JsonElement): ModelInfo? {
        val id = e["id"].string() ?: return null
        return ModelInfo(
          id = id,
          displayName = e["name"].string() ?: id,
          kind = ModelKind.entries.firstOrNull { it.name == e["kind"].string() } ?: ModelKind.CHAT,
          contextWindow = e["ctx"].double()?.toInt(),
          maxOutputTokens = e["out"].double()?.toInt(),
          supportsTools = (e["tools"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
          supportsReasoning = (e["reasoning"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
          audioInput = (e["audio"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
          free = (e["free"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
          pricePromptPerM = e["pin"].double(),
          priceCompletionPerM = e["pout"].double(),
        )
      }
      return ModelCatalogue(
        chat = json["chat"].asArray().mapNotNull { model(it) },
        stt = json["stt"].asArray().mapNotNull { model(it) },
      )
    }
  }
}

/**
 * La verifica di una chiave e' una chiamata vera (`GET /models`), come in KeyVoice: se risponde,
 * la chiave si segna verificata e il catalogo si salva. Per OpenRouter si leggono anche i
 * crediti e, la prima volta, si sceglie il modello gratuito con tool secondo l'euristica.
 */
class AiKeyVerifier(
  private val keys: AiKeyStore,
  private val settings: AiSettingsStore,
  private val providers: ProviderFactory,
  private val catalogs: ModelCatalogStore,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private val info = MutableStateFlow<Map<ProviderId, OpenRouterKeyInfo>>(emptyMap())

  /** L'ultimo `GET /key` di OpenRouter, per la scheda delle impostazioni. */
  val keyInfo: StateFlow<Map<ProviderId, OpenRouterKeyInfo>> = info

  suspend fun verify(provider: ProviderId): VerifyResult {
    val client = providers.forVerification(provider) ?: return VerifyResult.Invalid
    return try {
      val catalogue = client.listModels()
      if (catalogue.chat.isEmpty() && catalogue.stt.isEmpty()) return VerifyResult.Failed(null)
      catalogs.save(provider, catalogue)
      settings.markModelsRefreshed(provider, clock())
      keys.markVerified(provider, clock())
      var keyInfo: OpenRouterKeyInfo? = null
      var chosen: String? = null
      var chosenIsFree = false
      if (provider == ProviderId.OPENROUTER) {
        keyInfo = runCatching { (client as? OpenRouterProvider)?.keyInfo() }.getOrNull()
        keyInfo?.let { info.value = info.value + (provider to it) }
        val current = settings.current().chatModels[ProviderId.OPENROUTER]
        if (current == null || catalogue.chat.none { it.id == current }) {
          val free = OpenRouterCatalog.pickDefaultFree(catalogue)
          chosen = free?.id ?: catalogue.chat.firstOrNull { it.id == AiDefaults.OPENROUTER_CHAT_FALLBACK }?.id ?: catalogue.chat.firstOrNull()?.id
          chosenIsFree = free != null
          settings.setChatModel(ProviderId.OPENROUTER, chosen)
        } else {
          chosen = current
          chosenIsFree = catalogue.chat.firstOrNull { it.id == current }?.free == true
        }
      }
      VerifyResult.Ok(catalogue, keyInfo, chosen, chosenIsFree)
    } catch (e: CancellationException) {
      throw e
    } catch (e: AiError.Unauthorized) {
      VerifyResult.Invalid
    } catch (e: AiError) {
      VerifyResult.Failed(e)
    } catch (e: Throwable) {
      VerifyResult.Failed(null)
    }
  }

  /** Rinfresca un catalogo se e' vecchio piu' di un giorno (o subito, se [force]). */
  suspend fun refreshIfStale(provider: ProviderId, force: Boolean = false): ModelCatalogue? {
    val current = settings.current()
    val refreshedAt = current.modelsRefreshedAt[provider] ?: 0L
    val cached = catalogs.load(provider)
    if (!force && cached != null && clock() - refreshedAt < DAY_MILLIS) return cached
    val client = providers.forVerification(provider) ?: return cached
    return try {
      val catalogue = client.listModels()
      catalogs.save(provider, catalogue)
      settings.markModelsRefreshed(provider, clock())
      if (provider == ProviderId.OPENROUTER) {
        runCatching { (client as? OpenRouterProvider)?.keyInfo() }.getOrNull()?.let { info.value = info.value + (provider to it) }
      }
      catalogue
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      cached
    }
  }

  companion object {
    const val DAY_MILLIS = 24 * 3_600_000L
  }
}
