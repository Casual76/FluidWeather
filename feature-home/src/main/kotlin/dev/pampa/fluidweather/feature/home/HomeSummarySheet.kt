package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.*
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.LocalUnits
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.UnitFormatter
import dev.pampa.fluidweather.strings.WeatherBriefFormatter
import kotlinx.coroutines.delay

private class SummarySheetOwner { var controller: HomeSummaryController? = null }

/** Requires home's existing FluidGlassModalHost; borrows its shared chrome backdrop. */
@Composable
fun HomeSummarySheet(
  visible: Boolean,
  brief: WeatherBrief?,
  placeLabel: String,
  gateway: WeatherSummaryGateway,
  configured: Boolean,
  generation: () -> Long,
  onDismiss: () -> Unit,
  onOpenAiSettings: () -> Unit,
  active: Boolean = true,
) {
  // Keep the portal registered while closed so its engine-owned exit motion can finish.
  val title = stringResource(R.string.weather_summary_ui_title)
  val owner = remember { SummarySheetOwner() }
  FluidGlassModalPortal(
    visible = visible && active,
    onDismissRequest = { owner.controller?.pause(); onDismiss() },
    presentation = FluidGlassModalPresentation.Sheet,
    paneTitle = title,
  ) {
    HomeSummarySession(brief, placeLabel, gateway, configured, generation, onDismiss, onOpenAiSettings, visible && active, owner)
  }
}

@Composable
private fun HomeSummarySession(
  brief: WeatherBrief?, placeLabel: String, gateway: WeatherSummaryGateway, configured: Boolean,
  generation: () -> Long, onDismiss: () -> Unit, onOpenAiSettings: () -> Unit,
  owned: Boolean,
  owner: SummarySheetOwner,
) {
  val context = LocalContext.current
  val config = LocalConfiguration.current
  val locale = config.locales[0]
  val preferences = LocalUnits.current
  val resources = context.resources
  val units = remember(config, preferences) { UnitFormatter(resources, preferences, locale) }
  val latestBrief by rememberUpdatedState(brief)
  val latestPlace by rememberUpdatedState(placeLabel)
  val latestUnits by rememberUpdatedState(units)
  val latestLocale by rememberUpdatedState(locale)
  val latestResources by rememberUpdatedState(resources)
  val latestGeneration by rememberUpdatedState(generation)
  val scope = rememberCoroutineScope()
  val controller = remember(gateway) {
    HomeSummaryController(gateway, scope, System::currentTimeMillis, { latestGeneration() }) { now, wiped ->
      WeatherBriefFormatter.document(latestResources, latestBrief.takeUnless { wiped }, latestPlace, latestUnits, latestLocale, now)
    }
  }
  val state by controller.state.collectAsState()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
  DisposableEffect(controller, owner) {
    owner.controller = controller
    onDispose { controller.pause(); owner.controller = null }
  }
  LaunchedEffect(controller, lifecycle, owned) {
    if (!owned) { controller.pause(); return@LaunchedEffect }
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      resumed = true
      controller.resume()
      try {
        while (true) {
          controller.refresh()
          // Exact formatter deadline, with a bounded wipe-generation observation interval.
          delay((controller.state.value.document.validUntilMillis - System.currentTimeMillis()).coerceIn(1L, 1_000L))
        }
      } finally { resumed = false; controller.pause() }
    }
  }
  SideEffect { if (owned) controller.refresh() else controller.pause() }
  LaunchedEffect(controller, state.document.validUntilMillis, owned, resumed) {
    if (owned && resumed) {
      delay((state.document.validUntilMillis - System.currentTimeMillis()).coerceAtLeast(1L))
      controller.refresh()
    }
  }
  LaunchedEffect(configured) { if (!configured) controller.cancel(HomeSummaryPhase.NOT_CONFIGURED) }
  // Regenerate synchronously for input changes and consumption expiry: never draw an old DTO
  // while waiting for SideEffect, a periodic domain tick, or a resumed coroutine.
  val fresh = WeatherBriefFormatter.document(resources, brief.takeUnless { controller.wiped }, placeLabel, units, locale, System.currentTimeMillis())
  val current = state.document.contextKey == fresh.contextKey
  val text = if (current) state.render(System.currentTimeMillis()) ?: fresh.render() else fresh.render()
  val phase = if (current) state.phase else HomeSummaryPhase.DEFAULT
  FluidSheetScaffold(
    title = stringResource(R.string.weather_summary_ui_title),
    modifier = Modifier.testTag("weather_summary_sheet"),
    // All actions scroll too: at 2x text on a short display they must not consume the body budget.
  ) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("weather_summary_text"))
    Text(stringResource(when (phase) {
      HomeSummaryPhase.LOADING -> R.string.weather_summary_ui_loading
      HomeSummaryPhase.READY -> R.string.weather_summary_ui_ready
      HomeSummaryPhase.CANCELLED -> R.string.weather_summary_ui_cancelled
      HomeSummaryPhase.NOT_CONFIGURED -> R.string.weather_summary_ui_configure_note
      HomeSummaryPhase.FAILED -> R.string.weather_summary_ui_failed
      HomeSummaryPhase.INVALIDATED -> R.string.weather_summary_ui_invalidated
      HomeSummaryPhase.DEFAULT -> R.string.weather_summary_ui_optional
    }), style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.testTag("weather_summary_status").semantics { liveRegion = LiveRegionMode.Polite })
    if (phase == HomeSummaryPhase.LOADING) {
      FluidButton(stringResource(R.string.weather_summary_ui_cancel), { controller.cancel() },
        fillWidth = true, grip = FluidPressGrip.YieldsToScroll, modifier = Modifier.testTag("weather_summary_cancel"))
    } else if (!configured || phase == HomeSummaryPhase.NOT_CONFIGURED) {
      FluidButton(stringResource(R.string.weather_summary_ui_settings), { controller.pause(); onDismiss(); onOpenAiSettings() },
        fillWidth = true, grip = FluidPressGrip.YieldsToScroll, modifier = Modifier.testTag("weather_summary_settings"))
    } else if (!controller.wiped) {
      FluidButton(stringResource(if (phase in setOf(HomeSummaryPhase.FAILED, HomeSummaryPhase.CANCELLED, HomeSummaryPhase.INVALIDATED)) R.string.weather_summary_ui_retry else R.string.weather_summary_ui_request),
        { controller.request(configured) }, enabled = resumed && owned, fillWidth = true,
        grip = FluidPressGrip.YieldsToScroll, modifier = Modifier.testTag("weather_summary_request"))
    }
    FluidButton(stringResource(R.string.weather_summary_ui_close), { controller.pause(); onDismiss() },
      style = FluidButtonStyle.Plain, fillWidth = true, grip = FluidPressGrip.YieldsToScroll)
  }
}
