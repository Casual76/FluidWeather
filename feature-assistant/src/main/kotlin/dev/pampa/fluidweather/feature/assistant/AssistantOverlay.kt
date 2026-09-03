package dev.pampa.fluidweather.feature.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ai.orchestrator.AnswerChip
import dev.pampa.fluidweather.core.ai.orchestrator.AskMode
import dev.pampa.fluidweather.core.ai.orchestrator.AssistantState
import dev.pampa.fluidweather.core.ai.orchestrator.UiCommand
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.launch

/** Cosa l'overlay chiede alla home: aprire una pagina, selezionare un posto per nome. */
interface AssistantNavigator {
  fun open(target: OpenTarget)
  fun selectPlace(name: String)
}

/**
 * Come l'assistente e' in scena: nascosto, in ascolto (aureola grande), in modalita' testo
 * (barra + card), con la card ridotta a pillola perche' l'utente ha ripreso a usare la home.
 */
enum class OverlayMode { HIDDEN, VOICE, TEXT }

/**
 * Lo stato dell'overlay tenuto fuori dalla composizione della home, cosi' sopravvive a ogni
 * ricomposizione e la home lo tocca solo con due callback (tocco e pressione lunga sul tasto).
 */
class AssistantOverlayState {
  var mode by mutableStateOf(OverlayMode.HIDDEN)
  var collapsed by mutableStateOf(false)

  /** Cresce a ogni tocco del tasto: e' il segnale che fa partire l'ascolto, anche a card gia' aperta. */
  var voiceRequest by mutableStateOf(0)
    private set

  fun openVoice() {
    mode = OverlayMode.VOICE
    collapsed = false
    voiceRequest++
  }

  fun openText() {
    mode = OverlayMode.TEXT
    collapsed = false
  }

  fun hide() {
    mode = OverlayMode.HIDDEN
    collapsed = false
  }

  /** La home e' stata toccata o scorsa: la card si fa da parte senza fermare niente. */
  fun onHomeInteraction() {
    if (mode != OverlayMode.HIDDEN) collapsed = true
  }
}

/**
 * L'overlay dell'assistente sopra la home: aureola in cima, barra di scrittura (in modalita'
 * testo), card della risposta. Non e' modale: nessuno scrim, i tocchi fuori passano alla home,
 * e la card si riduce a una pillola quando l'utente torna a usare l'app; trascinarla in alto la
 * chiude (e interrompe, se sta lavorando), il tasto stop interrompe e basta.
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
  val pending by session.pendingAction.collectAsState()
  val settings by assistant.settings.settings.collectAsState(initial = null)
  val scope = rememberCoroutineScope()
  val speaker = remember { TtsSpeaker(context) }
  DisposableEffect(speaker) { onDispose { speaker.release() } }

  // L'assistente e' la parte dell'app che si usa senza guardarla: l'ascolto che parte, il
  // parlato riconosciuto, l'ascolto che finisce e la risposta pronta si sentono sotto il dito.
  val haptics = rememberFluidHaptics()
  var listening by remember { mutableStateOf(false) }
  var spoke by remember { mutableStateOf(false) }
  var answered by remember { mutableStateOf(false) }
  var waitSecond by remember { mutableIntStateOf(-1) }
  LaunchedEffect(state) {
    val current = state
    if (current is AssistantState.Listening) {
      if (!listening) {
        listening = true
        spoke = false
        haptics.play(FluidHapticEvent.ListenStart)
      }
      if (current.speaking && !spoke) {
        spoke = true
        haptics.play(FluidHapticEvent.SpeechDetected)
      }
    } else if (listening) {
      listening = false
      haptics.play(FluidHapticEvent.ListenEnd)
    }
    when (current) {
      is AssistantState.Done -> if (!answered) {
        answered = true
        haptics.play(FluidHapticEvent.ReplyReady)
      }
      is AssistantState.Failed -> haptics.play(FluidHapticEvent.Error)
      is AssistantState.Cancelled -> haptics.play(FluidHapticEvent.Stop)
      is AssistantState.SwitchingProvider -> haptics.play(FluidHapticEvent.ProviderSwitched)
      // Un secondo di attesa in piu' e' un tick: si sente che il conto scende anche a schermo spento.
      is AssistantState.WaitingRateLimit -> if (current.secondsLeft != waitSecond) {
        waitSecond = current.secondsLeft
        haptics.play(FluidHapticEvent.WaitTick)
      }
      else -> Unit
    }
    if (current !is AssistantState.Done) answered = false
  }

  // Le azioni di navigazione decise dall'orchestratore.
  LaunchedEffect(session) {
    session.commands.collect { command ->
      when (command) {
        is UiCommand.Open -> {
          overlay.collapsed = true
          navigator.open(command.target)
        }
      }
    }
  }

  // Lettura ad alta voce: solo in modalita' vocale, solo se l'utente l'ha accesa.
  LaunchedEffect(state, settings?.speakReplies) {
    val speak = settings?.speakReplies == true && session.lastMode.value == AskMode.VOICE
    when (val s = state) {
      is AssistantState.Answering -> if (speak) speaker.speakNewSentences(s.partial, final = false)
      is AssistantState.Done -> if (speak) speaker.speakNewSentences(s.answer, final = true)
      is AssistantState.Listening, is AssistantState.Classifying, AssistantState.Transcribing -> speaker.restart()
      is AssistantState.Cancelled, is AssistantState.Failed, AssistantState.Idle -> speaker.stop()
      else -> Unit
    }
  }

  // Lo stato che la sessione produce da solo (una domanda lanciata dal tasto) apre l'overlay.
  LaunchedEffect(state) {
    if (state is AssistantState.Listening && overlay.mode == OverlayMode.HIDDEN) overlay.openVoice()
    if (AssistantStrings.wantsExpanded(state) && overlay.mode != OverlayMode.HIDDEN) overlay.collapsed = false
    if (state == AssistantState.HeardNothing) {
      kotlinx.coroutines.delay(1_600)
      if (session.state.value == AssistantState.HeardNothing) {
        session.dismiss()
        if (overlay.mode == OverlayMode.VOICE) overlay.hide()
      }
    }
  }

  var micGranted by remember {
    mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
  }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    micGranted = granted
    if (granted) {
      overlay.openVoice()
    }
  }

  val visible = overlay.mode != OverlayMode.HIDDEN
  val mood = when {
    !visible -> HaloMood.HIDDEN
    state is AssistantState.Listening -> HaloMood.LISTENING
    state is AssistantState.Answering -> HaloMood.WRITING
    state is AssistantState.Failed -> HaloMood.ERROR
    state is AssistantState.Done -> HaloMood.DONE
    state.isBusy -> HaloMood.WORKING
    else -> HaloMood.DONE
  }
  val level = (state as? AssistantState.Listening)?.level ?: 0f

  AssistantHalo(
    mood = if (overlay.collapsed) HaloMood.HIDDEN else mood,
    level = level,
    accent = MaterialTheme.colorScheme.primary,
    modifier = Modifier.align(Alignment.TopCenter),
    height = if (overlay.mode == OverlayMode.VOICE) 150.dp else 96.dp,
  )

  AnimatedVisibility(
    visible = visible,
    enter = slideInVertically(FluidMotion.intOffset(FluidMotion.DampingStandard, FluidMotion.ResponseSnappy)) { -it / 2 } + fadeIn(FluidMotion.fadeIn(160)),
    exit = slideOutVertically(FluidMotion.intOffset(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy)) { -it / 2 } + fadeOut(FluidMotion.fadeOut(140)),
    modifier = Modifier
      .align(Alignment.TopCenter)
      .statusBarsPadding()
      .imePadding()
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    val drag = remember { Animatable(0f) }
    Column(
      Modifier
        .fillMaxWidth()
        .graphicsLayer { translationY = drag.value }
        .pointerInput(overlay.mode, state.isBusy) {
          detectVerticalDragGestures(
            onVerticalDrag = { change, delta ->
              change.consume()
              scope.launch { drag.snapTo((drag.value + delta).coerceAtMost(0f)) }
            },
            onDragEnd = {
              if (drag.value < -DISMISS_DRAG_PX) {
                if (state.isBusy) session.cancel()
                session.dismiss()
                overlay.hide()
              }
              scope.launch { drag.animateTo(0f, spring(FluidMotion.DampingStandard, FluidMotion.ResponseSnappy)) }
            },
            onDragCancel = { scope.launch { drag.animateTo(0f) } },
          )
        },
    ) {
      if (overlay.mode == OverlayMode.TEXT && !overlay.collapsed) {
        AssistantTextBar(
          backdrop = backdrop,
          busy = state.isBusy,
          micAvailable = micGranted,
          onSend = { session.askText(it) },
          onVoice = {
            overlay.openVoice()
            session.askVoice()
          },
          onNewConversation = {
            session.reset()
          },
        )
        Spacer(Modifier.padding(4.dp))
      }
      if (overlay.mode == OverlayMode.VOICE && !overlay.collapsed && state is AssistantState.Listening) {
        ListeningHint(onStop = { session.stopListening() })
      }
      val showCard = state != AssistantState.Idle && !(overlay.mode == OverlayMode.VOICE && state is AssistantState.Listening)
      if (showCard) {
        if (overlay.collapsed) {
          CollapsedPill(state = state, backdrop = backdrop, onExpand = { overlay.collapsed = false })
        } else {
          AssistantCard(
            state = state,
            pending = pending,
            backdrop = backdrop,
            onStop = { session.cancel() },
            onChip = { chip ->
              when (chip) {
                is AnswerChip.Open -> {
                  overlay.collapsed = true
                  navigator.open(chip.target)
                }
                is AnswerChip.Place -> navigator.selectPlace(chip.name)
              }
            },
            onConfirm = { id, yes ->
              haptics.play(if (yes) FluidHapticEvent.ActionConfirmed else FluidHapticEvent.Reject)
              session.resolveAction(id, yes)
            },
            onTapBody = { speaker.stop() },
          )
        }
      }
    }
  }

  // Ogni tocco del tasto (voiceRequest) fa partire un ascolto, se non c'e' gia' una domanda in corso;
  // senza permesso si chiede il microfono e intanto si apre la barra di testo.
  LaunchedEffect(overlay.voiceRequest) {
    if (overlay.voiceRequest == 0 || overlay.mode != OverlayMode.VOICE) return@LaunchedEffect
    if (session.isBusy) return@LaunchedEffect
    if (micGranted) {
      session.askVoice()
    } else {
      overlay.openText()
      micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
}

@Composable
private fun ListeningHint(onStop: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(top = 88.dp, bottom = 8.dp)
      .fluidPressable(onClick = onStop, pressedScale = 1f, role = Role.Button),
    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
  ) {
    Text(
      stringResource(R.string.ai_tap_to_stop),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
  }
}

/** La card ridotta: un puntino e una riga, perche' l'utente sta guardando altro. */
@Composable
private fun CollapsedPill(state: AssistantState, backdrop: GlassBackdropState, onExpand: () -> Unit) {
  val text = when (state) {
    is AssistantState.Done -> stringResource(R.string.ai_answer_ready)
    is AssistantState.Failed -> stringResource(AssistantStrings.failureRes(state.kind))
    is AssistantState.Working -> stringResource(AssistantStrings.statusRes(state.statusKey))
    is AssistantState.Answering -> stringResource(R.string.ai_status_writing)
    is AssistantState.WaitingRateLimit -> stringResource(R.string.ai_status_waiting, state.provider.label, state.secondsLeft)
    is AssistantState.Classifying -> stringResource(R.string.ai_status_classifying)
    AssistantState.Transcribing -> stringResource(R.string.ai_status_transcribing)
    is AssistantState.Listening -> stringResource(R.string.ai_status_listening)
    is AssistantState.AwaitingConfirmation -> stringResource(R.string.ai_status_confirm)
    else -> stringResource(R.string.ai_title)
  }
  Row(
    Modifier
      .glassSurface(state = backdrop, tint = GlassDefaults.floatingTint(), shape = FluidCapsuleShape, role = GlassRole.Floating)
      .fluidPressable(onClick = onExpand, pressedScale = 1f, role = Role.Button)
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .size(8.dp)
        .background(if (state is AssistantState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, FluidCapsuleShape),
    )
    Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

private const val DISMISS_DRAG_PX = 160f
