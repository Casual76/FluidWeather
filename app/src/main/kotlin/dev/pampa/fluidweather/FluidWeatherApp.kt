package dev.pampa.fluidweather

import android.app.Application
import android.content.ComponentName
import dev.pampa.fluidweather.core.cycle.BackgroundCycle
import dev.pampa.fluidweather.core.cycle.CycleRuntime
import dev.pampa.fluidweather.core.cycle.NotificationChannels
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.SamplingEngine
import dev.pampa.fluidweather.core.sensor.SamplingHealth
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.sensor.SensorRuntime
import dev.pampa.fluidweather.core.ui.TutorialCatalog
import dev.pampa.fluidweather.feature.appwidget.AppWidgetRuntime
import dev.pampa.fluidweather.feature.appwidget.installAppWidgetUpdates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Il punto in cui il campionamento parte e resta in piedi: il grafo si costruisce qui, la
 * modalita' corrente si riapplica a ogni avvio del processo (worker e allarmi arrivano anche a
 * processo appena nato, e trovano tutto pronto attraverso [SensorRuntime]).
 */
class FluidWeatherApp : Application(), SensorRuntime, CycleRuntime, AppWidgetRuntime {

  lateinit var graph: AppGraph
    private set

  override val samplingEngine: SamplingEngine get() = graph.samplingEngine
  override val samplingScheduler: SamplingScheduler get() = graph.samplingScheduler
  override val samplingHealth: SamplingHealth get() = graph.samplingHealth
  override val latestActivityStore: LatestActivityStore get() = graph.latestActivityStore
  override val calibrationController: CalibrationController get() = graph.calibrationController
  override val backgroundCycle: BackgroundCycle get() = graph.backgroundCycle

  override suspend fun rescheduleDailySummary() = graph.rescheduleDailySummary()

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    // Lingua o formato dell'ora cambiati: i formattatori in cache sono di ieri.
    dev.pampa.fluidweather.strings.TimeFormats.invalidate()
  }

  // --- AppWidgetRuntime: quel poco del grafo che serve a disegnare il widget di sistema. ---

  override val snapshotStore get() = graph.weatherSnapshotStore
  override val savedLocations get() = graph.savedLocationsRepository
  override val nowcastHistory get() = graph.nowcastHistoryStore
  override val unitPreferences get() = graph.notificationUnits
  override val engineSettings get() = graph.engineSettingsStore.settings
  override val mainActivity get() = ComponentName(this, MainActivity::class.java)

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    // Per primo, prima di tutto quello che potrebbe cadere: se cade il resto, la traccia resta.
    graph.crashLog.install(BuildConfig.VERSION_NAME)
    // I canali esistono prima della prima notifica: Android li vuole registrati, sempre.
    NotificationChannels.ensure(this)
    graph.activityRecognizer.start()
    graph.applicationScope.launch {
      // Ognuno nel suo `runCatching`: senza, bastava che `applyCurrentMode` lanciasse (un file di
      // preferenze corrotto, e prima non c'era un gestore della corruzione) perche' il
      // campionamento non venisse mai schedulato, in silenzio, per sempre.
      runCatching { graph.samplingScheduler.applyCurrentMode() }
      // E subito dopo si verifica che sia davvero armato: applicarlo non basta a sapere che c'e'.
      runCatching { graph.samplingHealth.check() }
      runCatching { graph.rescheduleDailySummary() }
      // La chiave dell'onboarding esce dal file della taratura, dove "cancella tutti i dati" se la
      // portava via insieme al bias e faceva ripartire la presentazione (e quindi la taratura).
      runCatching { graph.onboardingStore.migrateFromCalibrationStore() }
      // Una raffica di taratura rimasta senza riferimento e' ancora tutta in archivio: si ritenta
      // la stima invece di chiedere all'utente altri dieci minuti.
      runCatching { graph.calibrationController.retryPendingEstimate() }
    }
    // La config remota (fase 18): si rinfresca solo se la copia in cache ha piu' di sei ore,
    // mai davanti al primo fotogramma; un download fallito lascia l'ultima risposta valida.
    graph.applicationScope.launch { runCatching { graph.remoteConfig.refreshIfStale() } }
    graph.applicationScope.launch { runCatching { markKnownTutorialsSeen() } }
    // Il widget di sistema si ridisegna quando cambiano i dati, il tema o le unita'. Senza questo
    // collettore cambiare accento non arriverebbe mai alla schermata Home.
    installAppWidgetUpdates(
      context = this,
      scope = graph.applicationScope,
      snapshotUpdates = graph.weatherSnapshotStore.updates,
      engineSettings = graph.engineSettingsStore.settings.map {
        listOf(it.themeMode, it.accentMode, it.customAccentName, it.dynamicColorEnabled, it.amoledEnabled)
      },
      unitPreferences = graph.notificationUnits,
    )
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
