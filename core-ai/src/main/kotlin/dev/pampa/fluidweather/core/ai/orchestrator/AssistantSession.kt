package dev.pampa.fluidweather.core.ai.orchestrator

import android.content.res.Resources
import dev.pampa.fluidweather.core.ai.data.AiDataSources
import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import dev.pampa.fluidweather.core.ai.keys.AiSettingsStore
import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.provider.ProviderFactory
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.speech.SpeechCapture
import dev.pampa.fluidweather.core.ai.speech.Transcriber
import dev.pampa.fluidweather.core.ai.tools.ActionOutcome
import dev.pampa.fluidweather.core.ai.tools.ActionSink
import dev.pampa.fluidweather.core.ai.tools.AssistantAction
import dev.pampa.fluidweather.core.ai.tools.ToolContext
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.strings.UnitFormatter
import java.io.File
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Un'azione che la UI deve eseguire lei (navigare): l'orchestratore la chiede, la home la fa. */
sealed interface UiCommand {
  data class Open(val target: dev.pampa.fluidweather.core.ai.tools.OpenTarget) : UiCommand
}

/**
 * La sessione dell'assistente, una per processo, nello scope dell'Application: la domanda gira
 * qui, non nel composable, e sopravvive a uno scroll, a un cambio di pagina e a un'uscita
 * dall'app (fino a due minuti). La UI osserva [state], [conversation], [pendingAction] e
 * [commands]; chiama [askText], [askVoice], [stopListening], [cancel], [resolveAction], [reset].
 */
class AssistantSession(
  private val scope: CoroutineScope,
  private val sources: AiDataSources,
  private val resolver: PlaceResolver,
  private val settingsStore: AiSettingsStore,
  private val providers: ProviderFactory,
  private val orchestrator: AssistantOrchestrator,
  private val transcriber: Transcriber,
  private val speechFactory: () -> SpeechCapture,
  private val cacheDir: File,
  private val resources: () -> Resources,
  private val clock: () -> Long = System::currentTimeMillis,
) : ActionSink {

  private val stateFlow = MutableStateFlow<AssistantState>(AssistantState.Idle)
  val state: StateFlow<AssistantState> = stateFlow

  private val conversationFlow = MutableStateFlow(Conversation(0L, clock()))
  val conversation: StateFlow<Conversation> = conversationFlow

  private val pending = MutableStateFlow<PendingAction?>(null)
  val pendingAction: StateFlow<PendingAction?> = pending

  private val commandFlow = MutableSharedFlow<UiCommand>(extraBufferCapacity = 8)
  val commands: SharedFlow<UiCommand> = commandFlow

  /** L'ultima modalita' usata: la card sa se leggere ad alta voce. */
  val lastMode = MutableStateFlow(AskMode.TEXT)

  private var job: Job? = null

  /** Scritto dal worker e letto dal thread della UI quando si tocca "ferma": deve attraversare. */
  @Volatile
  private var speech: SpeechCapture? = null
  private val ids = AtomicLong(1)

  /**
   * Il livello del microfono, 0..1, e se in questo istante e' parlato. Fuori dallo stato apposta:
   * lo legge solo l'aureola, cinquanta volte al secondo, senza ricomporre nient'altro.
   */
  private val micLevelFlow = MutableStateFlow(MicLevel())
  val micLevel: StateFlow<MicLevel> = micLevelFlow

  class PendingAction(val id: Long, val action: AssistantAction, internal val answer: CompletableDeferred<Boolean>)

  val isBusy: Boolean get() = stateFlow.value.isBusy

  fun askText(question: String) {
    val text = question.trim()
    if (text.isEmpty()) return
    lastMode.value = AskMode.TEXT
    start { run(text, AskMode.TEXT) }
  }

  fun askVoice() {
    // Un ascolto per volta. Senza questa riga, due chiamate ravvicinate (il tasto e l'effetto che
    // lo segue) aprivano due `AudioRecord` sullo stesso microfono: il secondo consegnava silenzio.
    if (stateFlow.value is AssistantState.Listening) return
    lastMode.value = AskMode.VOICE
    start {
      val question = listen() ?: return@start
      run(question, AskMode.VOICE)
    }
  }

  fun stopListening() {
    speech?.stopNow()
  }

  fun cancel() {
    val current = stateFlow.value
    job?.cancel(CancellationException("annullato dall'utente"))
    job = null
    speech = null
    pending.value?.answer?.complete(false)
    pending.value = null
    stateFlow.value = AssistantState.Cancelled(
      question = current.questionOrNull(),
      partial = (current as? AssistantState.Answering)?.partial,
    )
  }

  /** La card dopo la lettura: torna a riposo senza perdere la conversazione. */
  fun dismiss() {
    if (!stateFlow.value.isBusy) stateFlow.value = AssistantState.Idle
  }

  fun reset() {
    cancel()
    conversationFlow.value = Conversation(ids.getAndIncrement(), clock())
    stateFlow.value = AssistantState.Idle
  }

  fun resolveAction(id: Long, confirmed: Boolean) {
    val current = pending.value ?: return
    if (current.id != id) return
    current.answer.complete(confirmed)
  }

  private fun start(block: suspend () -> Unit) {
    job?.cancel()
    pending.value = null
    job = scope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        // Lo stato Cancelled lo scrive cancel(); qui non c'e' altro da dire.
      } catch (e: AssistantFailure) {
        stateFlow.value = AssistantState.Failed(stateFlow.value.questionOrNull(), e.kind, e.error, e.retryAfterSec, (stateFlow.value as? AssistantState.Answering)?.partial)
      } catch (e: Throwable) {
        stateFlow.value = AssistantState.Failed(stateFlow.value.questionOrNull(), FailureKind.UNKNOWN, e as? AiError, null, null)
      }
    }
  }

  private suspend fun listen(): String? {
    val capture = speechFactory()
    speech = capture
    // Un file per ascolto: col nome fisso, due catture sovrapposte si sovrascrivevano e la seconda
    // cancellava il WAV della prima mentre lo si stava trascrivendo.
    val file = File(cacheDir, "ai/ask-${ids.get()}-${clock()}.wav")
    var result: String? = null
    try {
      stateFlow.value = AssistantState.Listening(0L)
      micLevelFlow.value = MicLevel()
      capture.record(file).collect { event ->
        when (event) {
          is SpeechCapture.Event.Level -> {
            micLevelFlow.value = MicLevel(event.level, event.speaking)
            val elapsed = event.elapsedMillis / 1000 * 1000
            val shown = stateFlow.value
            // Lo stato cambia una volta al secondo, non cinquanta: il livello viaggia per conto suo.
            if (shown !is AssistantState.Listening || shown.elapsedMillis != elapsed) {
              stateFlow.value = AssistantState.Listening(elapsed)
            }
          }
          SpeechCapture.Event.SpeechStarted -> Unit
          is SpeechCapture.Event.Empty -> {
            stateFlow.value = AssistantState.HeardNothing
          }
          // Il microfono che non parte non e' una trascrizione fallita: e' un'altra cosa, con un
          // altro rimedio (chiudere l'app che lo tiene), e va detta con parole sue.
          is SpeechCapture.Event.Failed -> throw AssistantFailure(FailureKind.MICROPHONE, null)
          is SpeechCapture.Event.Finished -> {
            stateFlow.value = AssistantState.Transcribing
            val language = language()
            val places = sources.savedLocations.places.first().filter { !it.isGps }.map { it.name }
            val transcription = try {
              transcriber.transcribe(event.file, language, Transcriber.hint(language, places))
            } catch (e: CancellationException) {
              throw e
            } catch (e: AiError.Unauthorized) {
              throw AssistantFailure(FailureKind.UNAUTHORIZED, e)
            } catch (e: Throwable) {
              throw AssistantFailure(FailureKind.TRANSCRIPTION, e as? AiError)
            }
            result = transcription.text.takeIf { it.isNotBlank() }
            if (result == null) stateFlow.value = AssistantState.HeardNothing
          }
        }
      }
    } finally {
      speech = null
      micLevelFlow.value = MicLevel()
      runCatching { file.delete() }
    }
    return result
  }

  private suspend fun run(question: String, mode: AskMode) {
    val now = clock()
    var conversation = conversationFlow.value
    if (conversation.isExpired(now)) {
      conversation = Conversation(ids.getAndIncrement(), now)
      conversationFlow.value = conversation
    }
    conversation.lastActivityMillis = now
    val settings = settingsStore.current()
    val ordered = providers.ordered(ProviderFactory.Kind.CHAT)
    if (ordered.isEmpty()) throw AssistantFailure(FailureKind.NO_KEYS, null)
    val language = language()
    val selected = runCatching { resolver.selected() }.getOrNull()
    val res = resources()
    val units = UnitFormatter(res, sources.unitsStore.current(), Locale.getDefault())
    val toolContext = ToolContext(
      sourcesProvider = { sources },
      unitsProvider = { units },
      resourcesProvider = { res },
      locale = Locale.getDefault(),
      zone = ZoneId.systemDefault(),
      nowMillis = now,
      selected = selected,
      actionsEnabled = settings.actionsEnabled,
      actions = if (settings.actionsEnabled) this else ActionSink.Disabled,
    )
    val prompt = promptContext(language, settings.actionsEnabled, mode, selected, toolContext, units)
    val input = AskInput(
      question = question,
      mode = mode,
      language = language,
      settings = settings,
      providers = ordered,
      toolContext = toolContext,
      systemPrompt = PromptBuilder.build(toolContext, prompt),
      conversation = conversation,
    )
    val result = orchestrator.ask(input, stateFlow)
    // La conversazione e' mutata dentro: la UI la rilegge dallo stesso oggetto.
    conversationFlow.value = conversation
    stateFlow.value = AssistantState.Done(
      question = question,
      answer = result.answer,
      chips = result.chips,
      provider = result.provider,
      mode = mode,
      usage = result.usage,
      toolsUsed = result.toolsUsed,
      durationMillis = result.log.durationMillis,
    )
  }

  private suspend fun promptContext(
    language: String,
    actionsEnabled: Boolean,
    mode: AskMode,
    selected: ResolvedPlace?,
    ctx: ToolContext,
    units: UnitFormatter,
  ): PromptContext {
    val sampling = sources.samplingSettings.current()
    val snapshot = selected?.let { runCatching { sources.snapshotRefresher.fresh(it.snapshotKey, it.latitude, it.longitude, 12 * 3_600_000L) }.getOrNull() }
    val nowcast = if (selected == null || selected.isGps || selected.isSelected) {
      runCatching { sources.nowcast.evaluate(snapshot, ctx.nowMillis, null, record = false) }.getOrNull()
    } else {
      null
    }
    val saved = sources.savedLocations.places.first().filter { !it.isGps }.map { it.name }
    return PromptContext(
      language = language,
      placeLabel = selected?.label,
      roughCoordinates = selected?.roughCoordinates,
      savedPlaces = saved,
      samplingMode = sampling.mode,
      barometerReady = nowcast?.readiness?.ready == true,
      barometerCalibrated = nowcast?.readiness?.calibrated == true,
      historyHours = nowcast?.historyHours ?: 0.0,
      snapshotAgeMinutes = snapshot?.let { ((ctx.nowMillis - it.fetchedAtMillis) / 60_000.0).roundToInt() },
      actionsEnabled = actionsEnabled,
      mode = mode,
      temperatureSymbol = units.temperatureSymbol(),
      windSymbol = units.windSymbol(),
      pressureSymbol = units.pressureSymbol(),
      precipitationSymbol = units.precipitationSymbol(),
      distanceSymbol = units.distanceSymbol(),
    )
  }

  private fun language(): String = Locale.getDefault().language.takeIf { it == "it" } ?: "en"

  // ------------------------------------------------------------------ ActionSink

  override suspend fun perform(action: AssistantAction): ActionOutcome {
    if (!action.needsConfirmation) return execute(action)
    val request = PendingAction(ids.getAndIncrement(), action, CompletableDeferred())
    val previous = stateFlow.value
    pending.value = request
    stateFlow.value = AssistantState.AwaitingConfirmation(previous.questionOrNull().orEmpty(), action, (previous as? AssistantState.Working)?.provider ?: ProviderId.GROQ)
    val confirmed = try {
      withTimeoutOrNull(CONFIRMATION_TIMEOUT_MILLIS) { request.answer.await() }
    } finally {
      if (pending.value?.id == request.id) pending.value = null
      if (stateFlow.value is AssistantState.AwaitingConfirmation) stateFlow.value = previous
    }
    return when (confirmed) {
      null -> ActionOutcome.TIMEOUT
      false -> ActionOutcome.REJECTED
      true -> execute(action)
    }
  }

  private suspend fun execute(action: AssistantAction): ActionOutcome = when (action) {
    is AssistantAction.Open -> {
      commandFlow.tryEmit(UiCommand.Open(action.target))
      ActionOutcome.DONE
    }
    is AssistantAction.SelectPlace -> {
      sources.selectedPlaceStore.select(action.place.id)
      ActionOutcome.DONE
    }
    AssistantAction.StartBurst -> {
      sources.manualBurst.start()
      ActionOutcome.DONE
    }
    is AssistantAction.RecordObservation -> {
      val here = runCatching { sources.locationProvider.snapshot(timeoutMillis = 5_000) }.getOrNull()
      val placeName = runCatching { resolver.selected()?.name }.getOrNull()
      sources.observations.record(action.condition, clock(), here?.latitude, here?.longitude, placeName)
      ActionOutcome.DONE
    }
    is AssistantAction.SavePlace -> {
      sources.savedLocations.save(action.place)
      ActionOutcome.DONE
    }
  }

  private fun AssistantState.questionOrNull(): String? = when (this) {
    is AssistantState.Classifying -> question
    is AssistantState.Working -> question
    is AssistantState.WaitingRateLimit -> question
    is AssistantState.SwitchingProvider -> question
    is AssistantState.Answering -> question
    is AssistantState.AwaitingConfirmation -> question
    is AssistantState.Done -> question
    is AssistantState.Failed -> question
    is AssistantState.Cancelled -> question
    else -> null
  }

  companion object {
    const val CONFIRMATION_TIMEOUT_MILLIS = 60_000L
  }
}

/** Solo per i test: un posto salvato qualunque. */
internal fun Place.Companion.dummy(): Place = Place(1L, "Test", null, 0.0, 0.0)
