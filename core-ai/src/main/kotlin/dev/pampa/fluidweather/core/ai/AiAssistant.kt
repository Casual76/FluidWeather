package dev.pampa.fluidweather.core.ai

import android.content.Context
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineFlag
import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.ai.data.AiDataSources
import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.keys.AiKeyStore
import dev.pampa.fluidweather.core.ai.keys.AiKeyVerifier
import dev.pampa.fluidweather.core.ai.keys.ModelCatalogStore
import dev.pampa.fluidweather.core.ai.keys.AiSettingsStore
import dev.pampa.fluidweather.core.ai.net.AiHttp
import dev.pampa.fluidweather.core.ai.orchestrator.AiDiagnosticsLog
import dev.pampa.fluidweather.core.ai.orchestrator.AssistantOrchestrator
import dev.pampa.fluidweather.core.ai.orchestrator.AssistantSession
import dev.pampa.fluidweather.core.ai.provider.ProviderFactory
import dev.pampa.fluidweather.core.ai.radar.RadarSampler
import dev.pampa.fluidweather.core.ai.radar.RadarTileStore
import dev.pampa.fluidweather.core.ai.speech.AndroidPcmSource
import dev.pampa.fluidweather.core.ai.speech.SpeechCapture
import dev.pampa.fluidweather.core.ai.speech.Transcriber
import dev.pampa.fluidweather.core.ai.tools.AllTools
import dev.pampa.fluidweather.core.weather.RainViewerClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** I flag remoti dell'assistente: il primo dell'app. Default acceso: senza rete si comporta come alla release. */
object AiFlags {
  val Assistant = EngineFlag(key = "ai_assistant", default = true)
}

/**
 * La facciata dell'assistente (fase 19), costruita una volta in `AppGraph`: mette insieme rete,
 * chiavi, impostazioni, provider, tool, radar numerico, voce, orchestratore e sessione. La UI
 * parla con [session]; le impostazioni con [keys], [settings], [providers]; la diagnostica con
 * [diagnostics].
 */
class AiAssistant(
  context: Context,
  scope: CoroutineScope,
  engineHttp: EngineHttp,
  userAgent: String,
  referer: String,
  appTitle: String,
  rainViewer: RainViewerClient,
  sources: (RadarSampler) -> AiDataSources,
  remoteConfig: EngineRemoteConfig,
) {
  private val appContext = context.applicationContext

  val http = AiHttp(userAgent)
  val keys = AiKeyStore(appContext)
  val settings = AiSettingsStore(appContext)
  val providers = ProviderFactory(http, keys, settings, referer, appTitle)
  val diagnostics = AiDiagnosticsLog()
  val catalogs = ModelCatalogStore(File(appContext.cacheDir, "ai"))
  val verifier = AiKeyVerifier(keys, settings, providers, catalogs)

  val radarTiles = RadarTileStore(File(appContext.cacheDir, "radar-tiles"), engineHttp)
  val radarSampler = RadarSampler(frames = { rainViewer.frames() }, tiles = radarTiles, prune = { radarTiles.prune() })
  val dataSources: AiDataSources = sources(radarSampler)
  val resolver = PlaceResolver(dataSources)
  val registry = AllTools.registry(resolver)
  val orchestrator = AssistantOrchestrator(registry = registry, diagnostics = diagnostics)
  val transcriber = Transcriber { providers.ordered(ProviderFactory.Kind.STT) }

  val session = AssistantSession(
    scope = scope,
    sources = dataSources,
    resolver = resolver,
    settingsStore = settings,
    providers = providers,
    orchestrator = orchestrator,
    transcriber = transcriber,
    speechFactory = { SpeechCapture(AndroidPcmSource()) },
    cacheDir = appContext.cacheDir,
    resources = { appContext.resources },
  )

  /** Il flag remoto: spento dal manifest, l'assistente sparisce dalla barra. */
  val remoteEnabled: Flow<Boolean> = remoteConfig.flag(AiFlags.Assistant)

  /** Acceso = impostazione attiva, almeno una chiave verificata, flag remoto acceso. */
  val enabled: Flow<Boolean> = combine(settings.settings, keys.anyVerified, remoteEnabled) { s, anyKey, remote ->
    s.enabled && anyKey && remote
  }
}
