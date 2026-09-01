package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.SampleSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

/**
 * Il toggle "monitoraggio continuo mentre l'app e' aperta": una lettura ogni dieci secondi,
 * legata al ciclo di vita di chi chiama [run] (la MainActivity, in stato STARTED). Non un
 * servizio: chiusa l'app, si ferma — quello persistente e' il giro periodico.
 */
class ContinuousMonitor(
  private val engine: SamplingEngine,
  private val settingsStore: SamplingSettingsStore,
) {

  suspend fun run() {
    settingsStore.settings
      .map { it.continuousWhileOpen }
      .distinctUntilChanged()
      .collectLatest { enabled ->
        if (!enabled) return@collectLatest
        while (currentCoroutineContext().isActive) {
          engine.collect(source = SampleSource.CONTINUOUS, durationSeconds = 0)
          delay(INTERVAL_MILLIS)
        }
      }
  }

  private companion object {
    const val INTERVAL_MILLIS = 10_000L
  }
}
