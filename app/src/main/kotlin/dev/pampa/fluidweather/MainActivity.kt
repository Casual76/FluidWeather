package dev.pampa.fluidweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidNotification
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationHost
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationTone
import dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidNotificationHostState
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationUrgency
import dev.pampa.fluidweather.core.ui.LocalTutorialController
import dev.pampa.fluidweather.core.ui.TutorialSurface
import dev.pampa.fluidweather.feature.settings.AppUpdatePrompt
import dev.pampa.fluidweather.navigation.FluidWeatherNavHost
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.theme.FluidWeatherTheme
import androidx.compose.runtime.remember
import dev.antigravity.fluidengine.foundation.EngineConfig

class MainActivity : ComponentActivity() {

  private val graph: AppGraph get() = (application as FluidWeatherApp).graph

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = graph
    setContent {
      val accent by graph.weatherAccent.collectAsState()
      val engineSettings by graph.engineSettingsStore.settings.collectAsState(initial = null)
      val onboardingDone by graph.onboardingStore.done.collectAsState(initial = null)
      val remote by graph.remoteConfig.config.collectAsState(initial = EngineConfig.Fallback)
      val settings: EngineSettings? = engineSettings
      val done: Boolean? = onboardingDone
      val gate = remember(remote) { ReleaseGate.of(remote) }
      if (settings == null || done == null) {
        // Il primo fotogramma, prima che le preferenze rispondano: nero, non un lampo di tema.
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E)))
      } else if (gate != null) {
        // Il manifest ha detto di fermarsi (fase 18): niente home, solo la frase e l'aggiornamento.
        FluidWeatherTheme(settings = settings, brand = accent) {
          ReleaseGateScreen(gate = gate, updates = graph.updateDependencies())
        }
      } else {
        FluidWeatherTheme(settings = settings, brand = accent) {
          ContinuousSamplingEffect(graph)
          // Con l'app in primo piano un'allerta e' un banner di vetro in cima, non una notifica
          // di sistema (decisione 2026-09-02): l'host sta alla radice, sopra la navigazione.
          val notificationHost = rememberFluidNotificationHostState()
          InAppAlertsEffect(graph, notificationHost)
          CrashNoticeEffect(graph, notificationHost)
          CompositionLocalProvider(
            LocalFluidNotificationHostState provides notificationHost,
            LocalTutorialController provides graph.tutorialController,
          ) {
            // I suggerimenti stanno sopra la navigazione ma sotto i banner: un'allerta ha sempre
            // la precedenza su una spiegazione.
            TutorialSurface {
              FluidWeatherNavHost(graph, startAtOnboarding = !done)
              FluidNotificationHost(
                state = notificationHost,
                modifier = Modifier.align(Alignment.TopCenter),
              )
              // Il controllo degli aggiornamenti all'apertura: una versione nuova sul canale
              // scelto si fa avanti da sola, invece di aspettare che qualcuno la vada a cercare.
              // Non all'onboarding: chi installa adesso ha gia' l'ultima.
              val updates = remember(graph) { graph.updateDependencies() }
              AppUpdatePrompt(deps = updates, enabled = done)
            }
          }
        }
      }
    }
  }

  // Il ciclo in background legge questo per scegliere il mezzo: banner o tendina.
  override fun onStart() {
    super.onStart()
    graph.appVisibility.set(true)
  }

  override fun onStop() {
    graph.appVisibility.set(false)
    super.onStop()
  }
}

/**
 * Il toggle "continuo mentre l'app e' aperta", legato allo stato STARTED: in background il
 * monitor si sospende da solo, riaperta l'app riparte. Il monitor decide poi se fare qualcosa
 * guardando l'impostazione.
 */
@Composable
private fun ContinuousSamplingEffect(graph: AppGraph) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      graph.continuousMonitor.run()
    }
  }
}

/**
 * Se l'ultimo avvio si e' chiuso da solo, l'app lo dice una volta e indica dove guardare. Senza,
 * chi la sta provando puo' solo dire "e' crashata", che non basta a nessuno per capire.
 */
@Composable
private fun CrashNoticeEffect(graph: AppGraph, host: FluidNotificationHostState) {
  val crashes by graph.crashLog.records.collectAsState()
  val title = stringResource(R.string.crash_banner_title)
  val text = stringResource(R.string.crash_banner_text)
  LaunchedEffect(crashes.firstOrNull()?.atMillis) {
    val latest = crashes.firstOrNull() ?: return@LaunchedEffect
    // Due guardie, ognuna per la sua ragione: il flag in memoria perche' l'avviso e' una cosa
    // dell'apertura (un errore catturato mentre l'app e' aperta ha gia' il suo messaggio sullo
    // schermo), il segno su file perche' una caduta si annuncia UNA volta e non a ogni riapertura.
    if (graph.crashLog.unannounced()?.atMillis != latest.atMillis) return@LaunchedEffect
    if (!graph.crashNoticeShown.compareAndSet(false, true)) return@LaunchedEffect
    graph.crashLog.markAnnounced(latest.atMillis)
    host.show(
      FluidNotification(
        id = "crash-${latest.atMillis}",
        title = title,
        message = text,
        tone = FluidNotificationTone.Warning,
        durationMillis = 7_000L,
      ),
    )
  }
}

/** Le notifiche decise dal ciclo mentre l'app e' aperta, come banner dell'engine. */
@Composable
private fun InAppAlertsEffect(graph: AppGraph, host: FluidNotificationHostState) {
  // Il banner e' l'unico a vibrare: con l'app davanti la notifica di sistema non parte, quindi
  // questo e' il solo momento in cui l'allerta si sente sotto le dita (fase 20).
  val haptics = rememberFluidHaptics()
  LaunchedEffect(graph, host) {
    graph.inAppAlerts.events.collect { alert ->
      when (alert.urgency) {
        NotificationUrgency.WATCH -> haptics.play(FluidHapticEvent.AlertWatch)
        NotificationUrgency.ALARM -> haptics.play(FluidHapticEvent.AlertAlarm)
        NotificationUrgency.CLEAR -> haptics.play(FluidHapticEvent.AlertClear)
        NotificationUrgency.INFO -> Unit
      }
      host.show(
        FluidNotification(
          id = "${alert.channel.id}-${alert.id}-${System.currentTimeMillis()}",
          title = alert.title,
          message = alert.text,
          tone = when (alert.channel) {
            NotificationChannelKind.NOWCAST_ALERT, NotificationChannelKind.OFFICIAL_ALERTS ->
              FluidNotificationTone.Warning
            NotificationChannelKind.PRECIPITATION, NotificationChannelKind.DAILY_SUMMARY ->
              FluidNotificationTone.Info
          },
          durationMillis = 9_000L,
        ),
      )
    }
  }
}
