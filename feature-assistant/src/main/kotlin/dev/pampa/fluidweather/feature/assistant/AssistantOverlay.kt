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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
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

  /**
   * Vero mentre la barra di scrittura e' aperta. Dopo l'invio si chiude e resta il pensiero
   * dell'assistente: chi ha appena scritto la domanda non ha piu' niente da scrivere, e la
   * tastiera aperta sopra la risposta era solo un ingombro.
   */
  var composing by mutableStateOf(false)
    private set

  /**
   * Cresce a ogni tocco del tasto ed e' un **gettone**: chi lo consuma fa partire l'ascolto, e
   * nessun altro lo rifa'. Prima era un contatore letto da un effetto, e bastava una ricomposizione
   * per far partire un secondo `AudioRecord` sullo stesso microfono.
   */
  var voiceRequest by mutableStateOf(0)
    private set

  fun openVoice() {
    mode = OverlayMode.VOICE
    collapsed = false
    composing = false
    voiceRequest++
  }

  /** Consuma il gettone: vero solo per il primo che chiama, e una volta sola per tocco. */
  fun consumeVoiceRequest(): Boolean {
    if (voiceRequest == 0 || voiceRequest == consumedRequest) return false
    consumedRequest = voiceRequest
    return true
  }

  private var consumedRequest by mutableStateOf(0)

  fun openText() {
    mode = OverlayMode.TEXT
    collapsed = false
    composing = true
    autoFocus = true
  }

  /** La domanda e' partita: via la barra, resta la risposta che si sta formando. */
  fun sent() {
    composing = false
  }

  /**
   * La risposta e' arrivata: la barra torna, per la domanda dopo. Senza tastiera in faccia, pero':
   * la si apre solo se la si tocca. Riaprire con il fuoco significava coprire con la tastiera la
   * risposta che si e' appena aspettata.
   */
  fun resumeComposing() {
    if (mode != OverlayMode.TEXT) return
    composing = true
    autoFocus = false
  }

  /** Vero se aprendo la barra si vuole anche la tastiera: si' quando l'ha chiesta l'utente. */
  var autoFocus by mutableStateOf(true)
    private set

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
  // Il livello del microfono viaggia per conto suo (cinquanta volte al secondo): lo leggono
  // l'aureola e il feedback tattile del primo parlato, e nessun altro.
  val mic by session.micLevel.collectAsState()
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
  // Il primo parlato riconosciuto si sente sotto il dito, e viene dal flow del livello: nello
  // stato non c'e' piu', perche' li' cambiava a ogni frame audio.
  LaunchedEffect(mic.speaking) {
    if (listening && mic.speaking && !spoke) {
      spoke = true
      haptics.play(FluidHapticEvent.SpeechDetected)
    }
  }
  LaunchedEffect(state) {
    val current = state
    if (current is AssistantState.Listening) {
      if (!listening) {
        listening = true
        spoke = false
        haptics.play(FluidHapticEvent.ListenStart)
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
  // La voce di sistema si azzera all'inizio di una domanda, non a ogni aggiornamento dello stato:
  // con la chiave su `state` questo effetto ripartiva a ogni frame audio e chiamava `restart()`
  // (cioe' `TextToSpeech.stop()`) cinquanta volte al secondo mentre il microfono registrava.
  val speakerReset = state is AssistantState.Listening || state is AssistantState.Classifying ||
    state == AssistantState.Transcribing
  LaunchedEffect(speakerReset) {
    if (speakerReset) speaker.restart()
  }
  LaunchedEffect(state, settings?.speakReplies) {
    val speak = settings?.speakReplies == true && session.lastMode.value == AskMode.VOICE
    when (val s = state) {
      is AssistantState.Answering -> if (speak) speaker.speakNewSentences(s.partial, final = false)
      is AssistantState.Done -> if (speak) speaker.speakNewSentences(s.answer, final = true)
      is AssistantState.Cancelled, is AssistantState.Failed, AssistantState.Idle -> speaker.stop()
      else -> Unit
    }
  }

  // A risposta finita la barra torna: la conversazione continua, e chi ha scritto una volta
  // scrivera' ancora. Non durante il lavoro, che e' il momento in cui doveva sparire.
  LaunchedEffect(state is AssistantState.Done) {
    if (state is AssistantState.Done && !overlay.collapsed) overlay.resumeComposing()
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

  // Il permesso si rilegge quando l'app torna davanti: concesso dalle impostazioni di sistema,
  // prima restava "negato" finche' la schermata non veniva ricreata.
  var permissionEpoch by remember { mutableIntStateOf(0) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) permissionEpoch++
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val micGranted = remember(permissionEpoch) {
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    permissionEpoch++
    if (granted) overlay.openVoice()
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
  val level = if (state is AssistantState.Listening) mic.level else 0f

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
    // In dp, non in pixel grezzi: su uno schermo denso 160 px erano mezzo centimetro, e la card
    // si chiudeva quasi per sbaglio.
    val dismissDragPx = with(LocalDensity.current) { DismissDrag.toPx() }
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
              if (drag.value < -dismissDragPx) {
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
      if (overlay.mode == OverlayMode.TEXT && overlay.composing && !overlay.collapsed) {
        AssistantTextBar(
          backdrop = backdrop,
          autoFocus = overlay.autoFocus,
          busy = state.isBusy,
          micAvailable = micGranted,
          onSend = {
            session.askText(it)
            overlay.sent()
          },
          // Solo `openVoice`: a far partire l'ascolto e' l'effetto che consuma il gettone, e
          // chiamarlo anche qui apriva due catture sullo stesso microfono.
          onVoice = { overlay.openVoice() },
          onNewConversation = {
            session.reset()
          },
        )
        Spacer(Modifier.padding(4.dp))
      }
      if (overlay.mode == OverlayMode.VOICE && !overlay.collapsed && state is AssistantState.Listening) {
        ListeningHint(
          backdrop = backdrop,
          elapsedMillis = (state as AssistantState.Listening).elapsedMillis,
          onStop = { session.stopListening() },
        )
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

  // Ogni tocco del tasto lascia un gettone, e questo effetto e' l'unico che lo consuma: cosi' una
  // ricomposizione non fa ripartire un ascolto che nessuno ha chiesto, e due catture non si
  // contendono il microfono.
  LaunchedEffect(overlay.voiceRequest) {
    if (overlay.mode != OverlayMode.VOICE) return@LaunchedEffect
    if (session.isBusy) return@LaunchedEffect
    if (!overlay.consumeVoiceRequest()) return@LaunchedEffect
    if (micGranted) {
      session.askVoice()
    } else {
      overlay.openText()
      micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
}

/**
 * Il tasto per fermare l'ascolto: una capsula di vetro sotto l'aureola, alta abbastanza da essere
 * un bersaglio vero (48 dp). Prima era una riga di testo nuda spinta gia' di 88 dp con un numero
 * scritto a mano, che finiva staccata in mezzo allo schermo e non sembrava un tasto.
 */
@Composable
private fun ListeningHint(backdrop: GlassBackdropState, elapsedMillis: Long, onStop: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(top = 12.dp, bottom = 8.dp),
    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
  ) {
    Row(
      Modifier
        .heightIn(min = 48.dp)
        .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
        .fluidPressable(onClick = onStop, pressedScale = 1f, role = Role.Button)
        .padding(horizontal = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier
          .size(10.dp)
          .background(MaterialTheme.colorScheme.error, FluidCapsuleShape),
      )
      Spacer(Modifier.width(10.dp))
      Text(
        stringResource(R.string.ai_listening_stop),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (elapsedMillis >= 1_000) {
        Spacer(Modifier.width(8.dp))
        Text(
          "${elapsedMillis / 1000}s",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
    }
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

/** Quanto va trascinata in alto la card per chiuderla: una misura, non un numero di pixel. */
private val DismissDrag = 56.dp
