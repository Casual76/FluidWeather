package dev.pampa.fluidweather.feature.assistant

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.aiui.halo.FluidHaloColours
import dev.antigravity.fluidengine.aiui.overlay.FluidAssistantActions
import dev.antigravity.fluidengine.aiui.overlay.FluidAssistantCardOptions
import dev.antigravity.fluidengine.aiui.overlay.FluidAssistantOverlay
import dev.antigravity.fluidengine.aiui.overlay.FluidAssistantOverlayState
import dev.antigravity.fluidengine.aiui.overlay.FluidOverlayMode
import dev.antigravity.fluidengine.aiui.overlay.rememberFluidTtsSpeaker
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import androidx.compose.material3.MaterialTheme
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ai.orchestrator.UiCommand
import dev.pampa.fluidweather.core.ai.orchestrator.WeatherChip
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.titleRes
import java.util.Locale

/** Cosa l'overlay chiede alla home: aprire una pagina, selezionare un posto per nome. */
interface AssistantNavigator {
  fun open(target: OpenTarget)
  fun selectPlace(name: String)
}

/**
 * Come l'assistente e' in scena: nascosto, in ascolto (aureola grande), in modalita' testo
 * (barra + card), con la card ridotta a pillola perche' l'utente ha ripreso a usare la home.
 * E' lo stato dell'overlay dell'engine: prima ne aveva una copia, identica a quella di ClasseViva.
 */
typealias AssistantOverlayState = FluidAssistantOverlayState
typealias OverlayMode = FluidOverlayMode

/**
 * L'overlay dell'assistente sopra la home: aureola in cima, barra di scrittura (in modalita'
 * testo), card della risposta che cresce dal tasto. Non e' modale: nessuno scrim, i tocchi fuori
 * passano alla home, e la card si riduce a una pillola quando l'utente torna a usare l'app;
 * trascinarla in alto la chiude (e interrompe, se sta lavorando), il tasto stop interrompe e basta.
 *
 * Il disegno, i suoni, la voce, la geometria e l'aptica sono di `FluidAssistantOverlay`
 * (`engine-ai-ui`). Qui resta il dominio dell'app: la sessione e i suoi stati, le parole, cosa
 * significa un chip (una pagina da aprire, un posto da mostrare), e i colori dell'aureola.
 */
@Composable
fun BoxScope.AssistantOverlay(
  assistant: AiAssistant,
  overlay: AssistantOverlayState,
  backdrop: GlassBackdropState,
  navigator: AssistantNavigator,
) {
  val context = LocalContext.current
  val session = assistant.session
  val state by session.state.collectAsState()
  // Il livello del microfono viaggia per conto suo (cinquanta volte al secondo): lo leggono
  // l'aureola e il feedback tattile del primo parlato, e nessun altro.
  val mic by session.micLevel.collectAsState()
  val settings by assistant.settings.settings.collectAsState(initial = null)
  val lastMode by session.lastMode.collectAsState()
  val strings = rememberAssistantStrings()
  // La voce di sistema: la lingua del telefono, poi l'inglese. Si crea solo se l'utente ha acceso la lettura.
  val speaker = rememberFluidTtsSpeaker(enabled = settings?.speakReplies == true, locales = listOf(Locale.getDefault(), Locale.ENGLISH))

  // Le azioni di navigazione decise dall'orchestratore: la card si fa da parte e si apre la pagina.
  LaunchedEffect(session) {
    session.commands.collect { command ->
      when (command) {
        is UiCommand.Open -> {
          overlay.collapseForNavigation()
          navigator.open(command.target)
        }
      }
    }
  }

  val configuration = context.resources.configuration
  val chipLabel: (AnswerChip) -> String = remember(configuration) {
    { chip ->
      when (val weather = WeatherChip.of(chip)) {
        is WeatherChip.Open -> context.resources.getString(weather.target.titleRes())
        is WeatherChip.Place -> weather.name
        null -> chip.id
      }
    }
  }
  val actions = remember(session, navigator) {
    FluidAssistantActions(
      onAskText = session::askText,
      onAskVoice = session::askVoice,
      onStopListening = session::stopListening,
      onCancel = session::cancel,
      onDismiss = session::dismiss,
      onNewConversation = session::reset,
      onConfirm = session::resolveAction,
      onChip = { chip ->
        when (val weather = WeatherChip.of(chip)) {
          is WeatherChip.Open -> navigator.open(weather.target)
          is WeatherChip.Place -> navigator.selectPlace(weather.name)
          null -> Unit
        }
      },
    )
  }
  val accent = MaterialTheme.colorScheme.primary
  // I colori dell'aureola sono quelli di sempre dell'app, non quelli del tema: il cielo e' sotto.
  val colours = remember(accent) { FluidHaloColours(accent, Color(0xFF7B61FF), Color(0xFF2EC5FF), Color(0xFFFF5A5F)) }

  FluidAssistantOverlay(
    state = state,
    micLevel = mic,
    pending = (state as? AssistantState.AwaitingConfirmation)?.confirmation,
    overlay = overlay,
    backdrop = backdrop,
    actions = actions,
    strings = strings,
    chipLabel = chipLabel,
    speaker = speaker,
    speakReplies = settings?.speakReplies == true,
    lastMode = lastMode,
    colours = colours,
    card = FluidAssistantCardOptions(maxBodyHeight = 360.dp),
    // Un chip che apre una pagina fa da parte la card; uno che sceglie un posto la lascia dov'e'.
    collapseOnChip = { chip -> WeatherChip.of(chip) is WeatherChip.Open },
  )
}
