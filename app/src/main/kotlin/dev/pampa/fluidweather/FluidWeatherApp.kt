package dev.pampa.fluidweather

import android.app.Application
import dev.pampa.fluidweather.core.cycle.BackgroundCycle
import dev.pampa.fluidweather.core.cycle.CycleRuntime
import dev.pampa.fluidweather.core.cycle.NotificationChannels
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.SamplingEngine
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.sensor.SensorRuntime
import dev.pampa.fluidweather.core.ui.TutorialCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Il punto in cui il campionamento parte e resta in piedi: il grafo si costruisce qui, la
 * modalita' corrente si riapplica a ogni avvio del processo (worker e allarmi arrivano anche a
 * processo appena nato, e trovano tutto pronto attraverso [SensorRuntime]).
 */
class FluidWeatherApp : Application(), SensorRuntime, CycleRuntime {

  lateinit var graph: AppGraph
    private set

  override val samplingEngine: SamplingEngine get() = graph.samplingEngine
  override val samplingScheduler: SamplingScheduler get() = graph.samplingScheduler
  override val latestActivityStore: LatestActivityStore get() = graph.latestActivityStore
  override val calibrationController: CalibrationController get() = graph.calibrationController
  override val backgroundCycle: BackgroundCycle get() = graph.backgroundCycle

  override suspend fun rescheduleDailySummary() = graph.rescheduleDailySummary()

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    // I canali esistono prima della prima notifica: Android li vuole registrati, sempre.
    NotificationChannels.ensure(this)
    graph.activityRecognizer.start()
    graph.applicationScope.launch {
      graph.samplingScheduler.applyCurrentMode()
      graph.rescheduleDailySummary()
    }
    // La config remota (fase 18): si rinfresca solo se la copia in cache ha piu' di sei ore,
    // mai davanti al primo fotogramma; un download fallito lascia l'ultima risposta valida.
    graph.applicationScope.launch { runCatching { graph.remoteConfig.refreshIfStale() } }
    graph.applicationScope.launch { runCatching { markKnownTutorialsSeen() } }
  }

  /**
   * Chi aggiorna non si rivede spiegare l'app che usa da mesi (fase 21): al primo avvio dopo un
   * aggiornamento, se l'onboarding e' gia' fatto, si segnano visti i suggerimenti delle funzioni
   * che c'erano gia', e restano solo quelli delle novita'. A un'installazione nuova non si tocca
   * niente: li vedra' tutti, uno per volta, mentre incontra le funzioni.
   */
  private suspend fun markKnownTutorialsSeen() {
    val store = graph.tutorialStore
    if (store.currentBaseline() != null) return
    if (graph.onboardingStore.done.first()) {
      // Non `VERSION_CODE`: chi arriva da una versione senza suggerimenti non ha una baseline, e
      // col versionCode di oggi si segnerebbero visti anche quelli usciti nel frattempo. Il
      // confine giusto e' fisso: tutto quello che c'era prima che i suggerimenti esistessero.
      store.markSeen(TutorialCatalog.introducedBefore(TutorialCatalog.WITH_ASSISTANT).map { it.id })
    }
    store.setBaselineVersionCode(BuildConfig.VERSION_CODE)
  }
}
