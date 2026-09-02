package dev.pampa.fluidweather

import android.content.Context
import dev.antigravity.fluidengine.config.EngineConfigSource
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.storage.EngineConfigCache
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource

/**
 * Dove FluidWeather pubblica (fase 18). Lo stesso `manifest.json` che il Pampa Store legge per
 * mostrare l'app vive alla radice del repo dell'app: l'updater in-app ci legge la release, la
 * config remota ci legge la sezione `engine` (flag, versione minima, avviso, kill switch). Una
 * sola fonte, quindi non esiste il caso in cui lo store offre una versione e l'app un'altra.
 */
object Release {

  const val MANIFEST_URL: String = BuildConfig.MANIFEST_URL

  /**
   * L'identita' con cui l'app si presenta al manifest: quella di release anche in debug, cosi'
   * una build di lavoro vede gli stessi override della build dello store.
   */
  const val APPLICATION_ID: String = "dev.pampa.fluidweather"

  const val REPOSITORY_URL: String = "https://github.com/Casual76/FluidWeather"

  /** Lo User-Agent descrittivo che MET Norway pretende e gli altri servizi apprezzano. */
  val userAgent: String =
    "FluidWeather/${BuildConfig.VERSION_NAME} ($APPLICATION_ID; uso personale non commerciale)"
}

/** L'aggiornamento in-app, costruito sui moduli dell'engine, come Fluid Glass e KeyVoice. */
fun fluidWeatherUpdater(context: Context, http: EngineHttp): AppUpdater = EngineAppUpdater(
  http = http,
  source = UpdateSource(manifestUrl = Release.MANIFEST_URL, applicationId = Release.APPLICATION_ID),
  installer = AndroidAppUpdateInstaller(context.applicationContext, http),
)

/** La meta' remota dell'engine: cambia comportamento, mai codice. */
fun fluidWeatherRemoteConfig(context: Context, http: EngineHttp): EngineRemoteConfig = EngineRemoteConfig(
  http = http,
  cache = EngineConfigCache(context.applicationContext),
  source = EngineConfigSource(manifestUrl = Release.MANIFEST_URL, applicationId = Release.APPLICATION_ID),
)
