package dev.pampa.fluidweather.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.tutorial.FluidGestureHint
import dev.antigravity.fluidengine.ui.tutorial.FluidTutorial
import dev.antigravity.fluidengine.ui.tutorial.FluidTutorialHost
import dev.antigravity.fluidengine.ui.tutorial.FluidTutorialLabels
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialTouches
import dev.antigravity.fluidengine.ui.tutorial.LocalFluidTutorialHostState
import dev.antigravity.fluidengine.ui.tutorial.rememberFluidTutorialHostState
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Le schermate che hanno qualcosa da spiegare. La chiave e' quella che il padrone di casa usa. */
enum class TutorialScreen(val key: String) {
  HOME("home"),
  RADAR("radar"),
  WIDGET_NOWCAST("widget/nowcast"),
  WIDGET_PRESSURE("widget/pressure"),
  BENCHMARK("benchmark"),
  REPORT("report"),
  PROVIDERS("settings/providers"),
  AI("settings/ai"),
  DIAGNOSTICS("settings/diagnostics"),
}

/**
 * Un suggerimento del catalogo (fase 21): l'elemento a cui si aggancia, quanto vuole precedenza
 * sugli altri della stessa schermata, e da quale versione esiste.
 *
 * [introducedIn] e' il versionCode in cui la funzione e' comparsa: chi aggiorna non si rivede
 * spiegare l'app che usa da mesi, e vede solo i suggerimenti delle novita'.
 */
data class TutorialEntry(
  val id: String,
  val screen: TutorialScreen,
  val anchorId: String,
  val priority: Int,
  val introducedIn: Int,
  val titleRes: Int,
  val textRes: Int,
  val hint: FluidGestureHint? = null,
)

/**
 * Cosa l'app spiega, e quando. Uno per elemento, una frase l'uno: la regola e' che un
 * suggerimento deve poter essere letto senza smettere di fare quello che si stava facendo.
 *
 * Le priorita' dicono chi parla per primo in una schermata affollata: il riordino della griglia
 * viene prima di tutto (e' il gesto che nessuno indovina), il menu per ultimo (si trova da solo).
 */
object TutorialCatalog {

  /** La versione in cui l'app e' nata: tutto quello che c'era nella 1.0.0. */
  const val INITIAL = 1

  /** La versione dell'assistente e dei suggerimenti (fasi 19-21). */
  const val WITH_ASSISTANT = 2

  val all: List<TutorialEntry> = listOf(
    TutorialEntry(
      id = "home_reorder",
      screen = TutorialScreen.HOME,
      anchorId = "home_first_tile",
      priority = 10,
      introducedIn = INITIAL,
      titleRes = R.string.tut_home_reorder_title,
      textRes = R.string.tut_home_reorder_text,
      hint = FluidGestureHint.DragReorder,
    ),
    TutorialEntry(
      id = "ai_button",
      screen = TutorialScreen.HOME,
      anchorId = "home_ai",
      priority = 9,
      introducedIn = WITH_ASSISTANT,
      titleRes = R.string.tut_ai_button_title,
      textRes = R.string.tut_ai_button_text,
      hint = FluidGestureHint.LongPressAndTap,
    ),
    TutorialEntry(
      id = "pill_tap_swipe",
      screen = TutorialScreen.HOME,
      anchorId = "home_pill",
      priority = 8,
      introducedIn = INITIAL,
      titleRes = R.string.tut_pill_title,
      textRes = R.string.tut_pill_text,
      hint = FluidGestureHint.SwipeHorizontal,
    ),
    TutorialEntry(
      id = "home_alert_row",
      screen = TutorialScreen.HOME,
      anchorId = "home_alert",
      priority = 7,
      introducedIn = INITIAL,
      titleRes = R.string.tut_home_alert_title,
      textRes = R.string.tut_home_alert_text,
    ),
    TutorialEntry(
      id = "home_widget_open",
      screen = TutorialScreen.HOME,
      anchorId = "home_first_tile",
      priority = 6,
      introducedIn = INITIAL,
      titleRes = R.string.tut_home_widget_title,
      textRes = R.string.tut_home_widget_text,
      hint = FluidGestureHint.Tap,
    ),
    TutorialEntry(
      id = "radar_button",
      screen = TutorialScreen.HOME,
      anchorId = "home_radar",
      priority = 4,
      introducedIn = INITIAL,
      titleRes = R.string.tut_radar_button_title,
      textRes = R.string.tut_radar_button_text,
    ),
    TutorialEntry(
      id = "more_menu",
      screen = TutorialScreen.HOME,
      anchorId = "home_menu",
      priority = 3,
      introducedIn = INITIAL,
      titleRes = R.string.tut_menu_title,
      textRes = R.string.tut_menu_text,
      hint = FluidGestureHint.LongPressAndTap,
    ),

    TutorialEntry(
      id = "radar_timeline",
      screen = TutorialScreen.RADAR,
      anchorId = "radar_timeline_bar",
      priority = 8,
      introducedIn = INITIAL,
      titleRes = R.string.tut_radar_timeline_title,
      textRes = R.string.tut_radar_timeline_text,
      hint = FluidGestureHint.Scrub,
    ),
    TutorialEntry(
      id = "radar_layers",
      screen = TutorialScreen.RADAR,
      anchorId = "radar_layers_button",
      priority = 5,
      introducedIn = INITIAL,
      titleRes = R.string.tut_radar_layers_title,
      textRes = R.string.tut_radar_layers_text,
    ),
    TutorialEntry(
      id = "radar_locate",
      screen = TutorialScreen.RADAR,
      anchorId = "radar_locate_button",
      priority = 3,
      introducedIn = INITIAL,
      titleRes = R.string.tut_radar_locate_title,
      textRes = R.string.tut_radar_locate_text,
    ),

    TutorialEntry(
      id = "nowcast_readiness",
      screen = TutorialScreen.WIDGET_NOWCAST,
      anchorId = "nowcast_readiness_card",
      priority = 7,
      introducedIn = INITIAL,
      titleRes = R.string.tut_nowcast_readiness_title,
      textRes = R.string.tut_nowcast_readiness_text,
    ),
    TutorialEntry(
      id = "nowcast_factors",
      screen = TutorialScreen.WIDGET_NOWCAST,
      anchorId = "nowcast_factors_card",
      priority = 5,
      introducedIn = INITIAL,
      titleRes = R.string.tut_nowcast_factors_title,
      textRes = R.string.tut_nowcast_factors_text,
    ),
    TutorialEntry(
      id = "pressure_raw_vs_msl",
      screen = TutorialScreen.WIDGET_PRESSURE,
      anchorId = "pressure_values",
      priority = 5,
      introducedIn = INITIAL,
      titleRes = R.string.tut_pressure_title,
      textRes = R.string.tut_pressure_text,
    ),
    TutorialEntry(
      id = "benchmark_ranking",
      screen = TutorialScreen.BENCHMARK,
      anchorId = "benchmark_ranking_list",
      priority = 5,
      introducedIn = INITIAL,
      titleRes = R.string.tut_benchmark_title,
      textRes = R.string.tut_benchmark_text,
    ),
    TutorialEntry(
      id = "report_why",
      screen = TutorialScreen.REPORT,
      anchorId = "report_conditions",
      priority = 5,
      introducedIn = INITIAL,
      titleRes = R.string.tut_report_title,
      textRes = R.string.tut_report_text,
    ),

    TutorialEntry(
      id = "settings_providers",
      screen = TutorialScreen.PROVIDERS,
      anchorId = "providers_keys",
      priority = 5,
      introducedIn = INITIAL,
      titleRes = R.string.tut_providers_title,
      textRes = R.string.tut_providers_text,
    ),
    TutorialEntry(
      id = "settings_ai",
      screen = TutorialScreen.AI,
      anchorId = "ai_state_group",
      priority = 5,
      introducedIn = WITH_ASSISTANT,
      titleRes = R.string.tut_settings_ai_title,
      textRes = R.string.tut_settings_ai_text,
    ),
    TutorialEntry(
      id = "settings_burst",
      screen = TutorialScreen.DIAGNOSTICS,
      anchorId = "diagnostics_tools",
      priority = 5,
      introducedIn = INITIAL,
      titleRes = R.string.tut_burst_title,
      textRes = R.string.tut_burst_text,
    ),
  )

  fun forScreen(screen: TutorialScreen): List<TutorialEntry> = all.filter { it.screen == screen }

  /** Quelli gia' esistenti prima di questa versione: chi aggiorna non se li rivede spiegare. */
  fun introducedBefore(versionCode: Int): List<TutorialEntry> = all.filter { it.introducedIn < versionCode }
}

/**
 * La memoria dei suggerimenti vista dalla UI: cosa e' gia' stato visto, se sono spenti, e i due
 * modi di cambiarli. La costruisce il grafo dell'app sopra il `TutorialStore`; qui non si sa
 * niente di DataStore, perche' `core-ui` non conosce la persistenza.
 */
@Stable
class TutorialController(
  val seen: StateFlow<Set<String>>,
  val disabled: StateFlow<Boolean>,
  private val onSeen: (String) -> Unit,
  private val onDisabled: (Boolean) -> Unit,
  private val onReplay: () -> Unit,
) {
  fun markSeen(id: String) = onSeen(id)

  /** Il link dentro un callout: da qui in poi, silenzio. */
  fun disableAll() = onDisabled(true)

  /** L'interruttore in Aspetto: e' anche il modo di tornare indietro dal link. */
  fun setDisabled(disabled: Boolean) = onDisabled(disabled)

  /** "Rivedi i suggerimenti": si torna al primo giorno. */
  fun replayAll() = onReplay()
}

val LocalTutorialController: ProvidableCompositionLocal<TutorialController?> = staticCompositionLocalOf { null }

/**
 * Una finestra che puo' ospitare suggerimenti: la radice dell'app, e ogni foglio nero.
 *
 * Ce n'e' bisogno una per finestra e non una sola per l'app, perche' un `ModalBottomSheet` vive in
 * una finestra sua: un callout disegnato nella radice, sopra un foglio aperto, finirebbe dietro al
 * foglio e con le coordinate di un'altra finestra.
 */
@Composable
fun TutorialSurface(
  backdrop: GlassBackdropState? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val controller = LocalTutorialController.current
  if (controller == null) {
    Box(Modifier.fillMaxSize(), content = content)
    return
  }
  val host = rememberFluidTutorialHostState()
  LaunchedEffect(host, controller) {
    host.onShown = { id -> controller.markSeen(id) }
    host.onDismissed = { _, optOut -> if (optOut) controller.disableAll() }
  }
  val labels = FluidTutorialLabels(
    dismiss = if (host.pendingCount > 0) stringResource(R.string.tut_next) else stringResource(R.string.tut_ok),
    next = stringResource(R.string.tut_next),
    optOut = stringResource(R.string.tut_opt_out),
  )
  val modalHost = LocalFluidGlassModalHostState.current
  CompositionLocalProvider(LocalFluidTutorialHostState provides host) {
    // L'osservatore dei tocchi sta QUI, sul contenitore: e' il genitore del contenuto, quindi
    // vede il tocco per primo senza rubarlo. Sopra il contenuto, invece, se lo prenderebbe tutto.
    Box(
      Modifier
        .fillMaxSize()
        .fluidTutorialTouches(host),
    ) {
      content()
      FluidTutorialHost(
        state = host,
        labels = labels,
        backdrop = backdrop,
        modalPresenting = { modalHost?.isOnScreen == true },
      )
    }
  }
}

/**
 * Quello che una schermata dichiara: chi e', cosa ha da spiegare, e quando non e' il momento.
 *
 * [busy] copre quello che il padrone di casa non puo' vedere da solo — uno scorrimento in corso,
 * un caricamento — perche' un suggerimento che compare su una lista che sta ancora scorrendo si
 * legge come un errore. [hidden] toglie i suggerimenti di elementi che ora non ci sono (la riga
 * dell'allerta quando non c'e' allerta).
 */
@Composable
fun TutorialSlot(
  screen: TutorialScreen,
  busy: Boolean = false,
  loading: Boolean = false,
  hidden: Set<String> = emptySet(),
) {
  val host = LocalFluidTutorialHostState.current ?: return
  val controller = LocalTutorialController.current ?: return
  val seen by controller.seen.collectAsState()
  val disabled by controller.disabled.collectAsState()

  val entries = remember(screen) { TutorialCatalog.forScreen(screen) }
  val candidates = entries.map { entry ->
    entry to FluidTutorial(
      id = entry.id,
      priority = entry.priority,
      title = stringResource(entry.titleRes),
      text = stringResource(entry.textRes),
      hint = entry.hint,
      anchorId = entry.anchorId,
    )
  }

  LaunchedEffect(host, screen) { host.screenChanged(screen.key) }

  LaunchedEffect(host, screen, seen, disabled, hidden) {
    candidates.forEach { (entry, tutorial) ->
      val show = !disabled && entry.id !in seen && entry.id !in hidden
      if (show) host.offer(tutorial, screen.key) else host.withdraw(entry.id)
    }
  }

  // Mentre il dito e' su una lista che scorre la quiete non deve mai maturare: il conto si
  // rimette a zero finche' il movimento dura, non solo quando comincia.
  LaunchedEffect(host, busy) {
    while (busy) {
      host.interacted()
      delay(150)
    }
  }

  DisposableEffect(host, loading) {
    if (loading) host.loading(active = true)
    onDispose { if (loading) host.loading(active = false) }
  }
}
