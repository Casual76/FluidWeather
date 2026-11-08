package dev.pampa.fluidweather.core.ai

import android.content.Context
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiKeyVerifier
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.KeystoreCipher
import dev.antigravity.fluidengine.ai.keys.ModelCatalogStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.orchestrator.AiConfirmationGate
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.provider.ProviderFactory
import dev.antigravity.fluidengine.ai.speech.AndroidPcmSource
import dev.antigravity.fluidengine.ai.speech.SpeechCapture
import dev.antigravity.fluidengine.ai.speech.Transcriber
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineFlag
import dev.pampa.fluidweather.core.ai.data.AiDataSources
import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.keys.AiPreferences
import dev.pampa.fluidweather.core.ai.orchestrator.AssistantSession
import dev.pampa.fluidweather.core.ai.orchestrator.WeatherOrchestrator
import dev.pampa.fluidweather.core.weather.radar.RadarSampler
import dev.pampa.fluidweather.core.ai.tools.AllTools
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** I flag remoti dell'assistente: il primo dell'app. Default acceso: senza rete si comporta come alla release. */
object AiFlags {
  val Assistant = EngineFlag(key = "ai_assistant", default = true)
}

/**
 * La facciata dell'assistente (fase 19), costruita una volta in `AppGraph`: mette insieme rete,
 * chiavi, impostazioni, provider, tool, radar numerico, voce, orchestratore e sessione. La UI
 * parla con [session]; le impostazioni con [keys], [settings], [providers]; la diagnostica con
 * [diagnostics].
 *
 * Il trasporto e l'orchestrazione sono quelli di `engine-ai` (prima erano una copia in casa, il
 * vecchio `core-ai`, da cui `engine-ai` era nato): qui resta il dominio, cioe' i tool del meteo, il
 * prompt, le azioni nell'app, il radar letto come numeri, e le poche cose che l'engine non sa di
 * FluidWeather (l'alias delle chiavi, la chiave scritta dal vecchio core-ai).
 */
class AiAssistant(
  context: Context,
  scope: CoroutineScope,
  userAgent: String,
  referer: String,
  appTitle: String,
  /** Il campionatore condiviso del grafo, usato anche dal nowcast senza dipendere dall'IA. */
  val radarSampler: RadarSampler,
  sources: (RadarSampler) -> AiDataSources,
  remoteConfig: EngineRemoteConfig,
  /**
   * Dove finiscono gli errori che l'assistente cattura invece di far cadere l'app: il quaderno
   * della Diagnostica. Un "verifica non riuscita" sullo schermo non dice niente a nessuno; la
   * traccia, si'.
   */
  val reportError: (Throwable, String) -> Unit = { _, _ -> },
) {
  private val appContext = context.applicationContext

  val http = AiHttp(userAgent)

  /**
   * Le chiavi, nello stesso file `ai_keys` e sotto lo stesso alias del Keystore di prima
   * (`fluidweather.ai`): con l'alias dell'engine le chiavi gia' salvate non si decifrerebbero piu'
   * e l'utente se le vedrebbe chiedere di nuovo. Un file corrotto riparte da vuoto, e' dell'engine.
   */
  val keys = AiKeyStore(appContext, KeystoreCipher(AiPreferences.KEYSTORE_ALIAS))

  /** Il file `ai` come handle, perche' la migrazione di una chiave di prima lo legge dallo stesso. */
  private val settingsData = AiPreferences.openSettings(appContext)
  val settings = AiSettingsStore(settingsData)

  val catalogs = ModelCatalogStore(File(appContext.cacheDir, "ai"))
  val providers = ProviderFactory(http, keys, settings, referer, appTitle, catalogs)
  val diagnostics = AiDiagnosticsLog()
  val verifier = AiKeyVerifier(keys, settings, providers, catalogs)

  val dataSources: AiDataSources = sources(radarSampler)
  val resolver = PlaceResolver(dataSources)
  val registry = AllTools.registry(resolver)
  val gate = AiConfirmationGate(WeatherOrchestrator.config)
  val orchestrator = WeatherOrchestrator.create(registry, diagnostics, gate)
  val transcriber = Transcriber { providers.ordered(ProviderFactory.Kind.STT) }

  val session = AssistantSession(
    scope = scope,
    sources = dataSources,
    resolver = resolver,
    settingsStore = settings,
    providers = providers,
    orchestrator = orchestrator,
    transcriber = transcriber,
    speechFactory = { SpeechCapture(AndroidPcmSource(appContext)) },
    cacheDir = appContext.cacheDir,
    resources = { appContext.resources },
    gate = gate,
  )

  /** Il flag remoto: spento dal manifest, l'assistente sparisce dalla barra. */
  val remoteEnabled: Flow<Boolean> = remoteConfig.flag(AiFlags.Assistant)

  /** Acceso = impostazione attiva, almeno una chiave verificata, flag remoto acceso. */
  val enabled: Flow<Boolean> = combine(settings.settings, keys.anyVerified, remoteEnabled) { s, anyKey, remote ->
    s.enabled && anyKey && remote
  }

  init {
    // Le preferenze scritte dal vecchio core-ai che l'engine legge con un altro nome.
    scope.launch { runCatching { AiPreferences.migrateLegacy(settingsData) } }
  }
}
