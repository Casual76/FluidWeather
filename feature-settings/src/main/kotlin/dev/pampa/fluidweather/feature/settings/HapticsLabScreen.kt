package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEngine
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.FluidHapticPatterns
import dev.antigravity.fluidengine.ui.haptics.FluidHapticsImpl
import dev.antigravity.fluidengine.ui.haptics.HapticPrimitive
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.launch

/** Quello che serve alla pagina: l'interruttore, cosi' si prova senza uscire e rientrare. */
class HapticsLabDependencies(
  val engineSettings: EngineSettingsStore,
)

/**
 * Come la pagina divide il vocabolario: per quando lo si sente, non per come e' fatto. Chi tara
 * i pattern prova prima i controlli (che devono restare secchi), poi i gesti (che si ripetono),
 * poi gli esiti, l'assistente e le allerte (che devono farsi notare).
 */
private enum class HapticGroup(val titleRes: Int, val events: List<FluidHapticEvent>) {
  CONTROLS(
    R.string.hapt_group_controls,
    listOf(
      FluidHapticEvent.Tap,
      FluidHapticEvent.Confirm,
      FluidHapticEvent.Reject,
      FluidHapticEvent.ToggleOn,
      FluidHapticEvent.ToggleOff,
      FluidHapticEvent.Open,
      FluidHapticEvent.Close,
    ),
  ),
  GESTURES(
    R.string.hapt_group_gestures,
    listOf(
      FluidHapticEvent.GestureStart,
      FluidHapticEvent.Tick,
      FluidHapticEvent.FrequentTick,
      FluidHapticEvent.Threshold,
      FluidHapticEvent.GestureEnd,
    ),
  ),
  OUTCOMES(
    R.string.hapt_group_outcomes,
    listOf(FluidHapticEvent.Success, FluidHapticEvent.Warning, FluidHapticEvent.Error),
  ),
  ASSISTANT(
    R.string.hapt_group_assistant,
    listOf(
      FluidHapticEvent.ListenStart,
      FluidHapticEvent.SpeechDetected,
      FluidHapticEvent.ListenEnd,
      FluidHapticEvent.ReplyReady,
      FluidHapticEvent.WaitTick,
      FluidHapticEvent.Stop,
      FluidHapticEvent.ActionConfirmed,
      FluidHapticEvent.ProviderSwitched,
    ),
  ),
  ALERTS(
    R.string.hapt_group_alerts,
    listOf(FluidHapticEvent.AlertWatch, FluidHapticEvent.AlertAlarm, FluidHapticEvent.AlertClear),
  ),
}

/** Il nome dell'evento come lo legge una persona. */
internal fun FluidHapticEvent.labelRes(): Int = when (this) {
  FluidHapticEvent.Tap -> R.string.hapt_ev_tap
  FluidHapticEvent.Confirm -> R.string.hapt_ev_confirm
  FluidHapticEvent.Reject -> R.string.hapt_ev_reject
  FluidHapticEvent.ToggleOn -> R.string.hapt_ev_toggle_on
  FluidHapticEvent.ToggleOff -> R.string.hapt_ev_toggle_off
  FluidHapticEvent.Threshold -> R.string.hapt_ev_threshold
  FluidHapticEvent.Tick -> R.string.hapt_ev_tick
  FluidHapticEvent.FrequentTick -> R.string.hapt_ev_frequent_tick
  FluidHapticEvent.GestureStart -> R.string.hapt_ev_gesture_start
  FluidHapticEvent.GestureEnd -> R.string.hapt_ev_gesture_end
  FluidHapticEvent.Open -> R.string.hapt_ev_open
  FluidHapticEvent.Close -> R.string.hapt_ev_close
  FluidHapticEvent.Success -> R.string.hapt_ev_success
  FluidHapticEvent.Warning -> R.string.hapt_ev_warning
  FluidHapticEvent.Error -> R.string.hapt_ev_error
  FluidHapticEvent.ListenStart -> R.string.hapt_ev_listen_start
  FluidHapticEvent.SpeechDetected -> R.string.hapt_ev_speech
  FluidHapticEvent.ListenEnd -> R.string.hapt_ev_listen_end
  FluidHapticEvent.ReplyReady -> R.string.hapt_ev_reply
  FluidHapticEvent.WaitTick -> R.string.hapt_ev_wait
  FluidHapticEvent.Stop -> R.string.hapt_ev_stop
  FluidHapticEvent.ActionConfirmed -> R.string.hapt_ev_action
  FluidHapticEvent.ProviderSwitched -> R.string.hapt_ev_switch
  FluidHapticEvent.AlertWatch -> R.string.hapt_ev_watch
  FluidHapticEvent.AlertAlarm -> R.string.hapt_ev_alarm
  FluidHapticEvent.AlertClear -> R.string.hapt_ev_clear
}

/** Dove si sente quell'evento nell'app: e' la parte che serve davvero mentre si tara. */
internal fun FluidHapticEvent.descriptionRes(): Int = when (this) {
  FluidHapticEvent.Tap -> R.string.hapt_ev_tap_desc
  FluidHapticEvent.Confirm -> R.string.hapt_ev_confirm_desc
  FluidHapticEvent.Reject -> R.string.hapt_ev_reject_desc
  FluidHapticEvent.ToggleOn -> R.string.hapt_ev_toggle_on_desc
  FluidHapticEvent.ToggleOff -> R.string.hapt_ev_toggle_off_desc
  FluidHapticEvent.Threshold -> R.string.hapt_ev_threshold_desc
  FluidHapticEvent.Tick -> R.string.hapt_ev_tick_desc
  FluidHapticEvent.FrequentTick -> R.string.hapt_ev_frequent_tick_desc
  FluidHapticEvent.GestureStart -> R.string.hapt_ev_gesture_start_desc
  FluidHapticEvent.GestureEnd -> R.string.hapt_ev_gesture_end_desc
  FluidHapticEvent.Open -> R.string.hapt_ev_open_desc
  FluidHapticEvent.Close -> R.string.hapt_ev_close_desc
  FluidHapticEvent.Success -> R.string.hapt_ev_success_desc
  FluidHapticEvent.Warning -> R.string.hapt_ev_warning_desc
  FluidHapticEvent.Error -> R.string.hapt_ev_error_desc
  FluidHapticEvent.ListenStart -> R.string.hapt_ev_listen_start_desc
  FluidHapticEvent.SpeechDetected -> R.string.hapt_ev_speech_desc
  FluidHapticEvent.ListenEnd -> R.string.hapt_ev_listen_end_desc
  FluidHapticEvent.ReplyReady -> R.string.hapt_ev_reply_desc
  FluidHapticEvent.WaitTick -> R.string.hapt_ev_wait_desc
  FluidHapticEvent.Stop -> R.string.hapt_ev_stop_desc
  FluidHapticEvent.ActionConfirmed -> R.string.hapt_ev_action_desc
  FluidHapticEvent.ProviderSwitched -> R.string.hapt_ev_switch_desc
  FluidHapticEvent.AlertWatch -> R.string.hapt_ev_watch_desc
  FluidHapticEvent.AlertAlarm -> R.string.hapt_ev_alarm_desc
  FluidHapticEvent.AlertClear -> R.string.hapt_ev_clear_desc
}

/**
 * "Prova i feedback" (fase 20): l'unico modo di tarare l'aptica e' sentirla, e sentirla di
 * seguito. La pagina dice prima cosa sa fare questo telefono (composizioni di primitive o
 * costanti di sistema, quali primitive, i cancelli che potrebbero zittire tutto) e poi elenca
 * il vocabolario intero, un evento per riga, con il tasto che lo fa partire.
 */
@Composable
fun HapticsLabScreen(deps: HapticsLabDependencies, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  val settings by deps.engineSettings.settings.collectAsState(initial = EngineSettings())
  val haptics = rememberFluidHaptics()
  val info = (haptics as? FluidHapticsImpl)?.info()

  val engineLabel = when (info?.engine ?: haptics.engine) {
    FluidHapticEngine.Composition -> stringResource(R.string.hapt_engine_composition)
    FluidHapticEngine.Platform -> stringResource(R.string.hapt_engine_platform)
    FluidHapticEngine.Off -> stringResource(R.string.hapt_engine_off)
  }
  val engineDesc = when (info?.engine ?: haptics.engine) {
    FluidHapticEngine.Composition -> stringResource(R.string.hapt_engine_composition_desc)
    FluidHapticEngine.Platform -> stringResource(R.string.hapt_engine_platform_desc)
    FluidHapticEngine.Off -> stringResource(R.string.hapt_engine_off_desc)
  }
  val supported = info?.supportedPrimitives.orEmpty()
  val primitives = HapticPrimitive.all.filter { it in supported }.joinToString(", ") { HapticPrimitive.label(it) }
  val composition = stringResource(R.string.hapt_row_composition)
  val platform = stringResource(R.string.hapt_row_platform)

  FluidScreen(
    title = stringResource(R.string.hapt_title),
    subtitle = stringResource(R.string.hapt_subtitle),
    onBack = onBack,
  ) {
    item { FluidSectionHeader(title = stringResource(R.string.hapt_device)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.appear_haptics),
          subtitle = stringResource(R.string.appear_haptics_desc),
          badge = {
            FluidSwitch(
              checked = settings.hapticsEnabled,
              onCheckedChange = { on -> scope.launch { deps.engineSettings.setHapticsEnabled(on) } },
            )
          },
        )
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.hapt_engine), subtitle = engineDesc, meta = engineLabel)
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.hapt_primitives),
          subtitle = primitives.ifEmpty { stringResource(R.string.hapt_primitives_none) },
          meta = supported.size.toString(),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.hapt_system),
          subtitle = stringResource(R.string.hapt_system_desc),
          meta = stringResource(if (info?.systemHapticsEnabled != false) R.string.hapt_on else R.string.hapt_off),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.hapt_power_save),
          subtitle = stringResource(
            if (info?.powerSave == true) R.string.hapt_power_save_on else R.string.hapt_power_save_off,
          ),
          meta = stringResource(if (info?.powerSave == true) R.string.hapt_on else R.string.hapt_off),
        )
      }
    }

    HapticGroup.entries.forEach { group ->
      item { FluidSectionHeader(title = stringResource(group.titleRes)) }
      item {
        FluidListGroup {
          group.events.forEachIndexed { index, event ->
            if (index > 0) FluidListDivider()
            val impl = haptics as? FluidHapticsImpl
            val byComposition = impl?.usesComposition(event) == true
            FluidListRow(
              title = stringResource(event.labelRes()),
              subtitle = stringResource(event.descriptionRes()),
              meta = stringResource(
                R.string.hapt_row_meta,
                if (byComposition) composition else platform,
                FluidHapticPatterns.durationMillis(event),
              ),
              badge = { PlayPill { haptics.play(event) } },
            )
          }
        }
      }
    }
  }
}

/**
 * Il tasto che fa partire un evento. Non e' un [dev.antigravity.fluidengine.ui.fluid.FluidButton]
 * per una ragione sola: qui il tocco non deve vibrare per conto suo, o si sentirebbero due cose
 * (il tap del tasto e l'evento) proprio nella pagina dove si giudica come suona una sola.
 */
@Composable
private fun PlayPill(onPlay: () -> Unit) {
  Box(
    modifier = Modifier
      .height(32.dp)
      .background(MaterialTheme.colorScheme.primaryContainer, FluidCapsuleShape)
      .fluidPressable(onClick = onPlay, role = Role.Button, haptic = null)
      .padding(horizontal = 16.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = stringResource(R.string.hapt_play),
      style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}
