package dev.pampa.fluidweather

import android.app.Application
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.sensor.SamplingEngine
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.sensor.SensorRuntime
import kotlinx.coroutines.launch

/**
 * Il punto in cui il campionamento parte e resta in piedi: il grafo si costruisce qui, la
 * modalita' corrente si riapplica a ogni avvio del processo (worker e allarmi arrivano anche a
 * processo appena nato, e trovano tutto pronto attraverso [SensorRuntime]).
 */
class FluidWeatherApp : Application(), SensorRuntime {

  lateinit var graph: AppGraph
    private set

  override val samplingEngine: SamplingEngine get() = graph.samplingEngine
  override val samplingScheduler: SamplingScheduler get() = graph.samplingScheduler
  override val latestActivityStore: LatestActivityStore get() = graph.latestActivityStore

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    graph.activityRecognizer.start()
    graph.applicationScope.launch {
      graph.samplingScheduler.applyCurrentMode()
      seedDebugProviderKeys()
    }
  }

  /**
   * Solo per il collaudo in debug: le chiavi personali da local.properties entrano nel
   * DataStore la prima volta, come le inserirebbe l'utente dalle impostazioni (fase 15).
   * In release i campi sono vuoti per costruzione: nessun segreto nell'APK.
   */
  private suspend fun seedDebugProviderKeys() {
    val existing = graph.providerKeysStore.current()
    if (BuildConfig.SEED_OWM_KEY.isNotBlank() && "openweathermap" !in existing) {
      graph.providerKeysStore.set("openweathermap", BuildConfig.SEED_OWM_KEY)
    }
    if (BuildConfig.SEED_METEOSOURCE_KEY.isNotBlank() && "meteosource" !in existing) {
      graph.providerKeysStore.set("meteosource", BuildConfig.SEED_METEOSOURCE_KEY)
    }
  }
}
