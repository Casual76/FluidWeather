package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.keys.AiSettings
import dev.pampa.fluidweather.core.ai.net.AiError
import dev.pampa.fluidweather.core.ai.net.RateLimitInfo
import dev.pampa.fluidweather.core.ai.provider.ChatDelta
import dev.pampa.fluidweather.core.ai.provider.ChatProvider
import dev.pampa.fluidweather.core.ai.provider.ChatRequest
import dev.pampa.fluidweather.core.ai.provider.ChatTurn
import dev.pampa.fluidweather.core.ai.provider.FinishReason
import dev.pampa.fluidweather.core.ai.provider.Message
import dev.pampa.fluidweather.core.ai.provider.ModelCatalogue
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.provider.ReadyProvider
import dev.pampa.fluidweather.core.ai.provider.ToolChoice
import dev.pampa.fluidweather.core.ai.provider.TranscribeOptions
import dev.pampa.fluidweather.core.ai.provider.Transcript
import dev.pampa.fluidweather.core.ai.tools.ActionSink
import dev.pampa.fluidweather.core.ai.tools.AiTool
import dev.pampa.fluidweather.core.ai.tools.Schema
import dev.pampa.fluidweather.core.ai.tools.ToolContext
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.core.ai.tools.ToolRegistry
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un turno recitato: testo, tool call, o un errore da lanciare prima di rispondere. */
private sealed interface Scripted {
  data class Text(val text: String) : Scripted
  data class Calls(val calls: List<Pair<String, JsonObject>>, val preamble: String? = null) : Scripted
  data class Fail(val error: Throwable) : Scripted
}

private class FakeProvider(override val id: ProviderId, turns: List<Scripted>, private val classifier: String = """{"gruppi":["orario"]}""") : ChatProvider {
  private val queue = ArrayDeque(turns)
  val requests = mutableListOf<ChatRequest>()
  val streamed = mutableListOf<ChatRequest>()

  override suspend fun complete(request: ChatRequest): ChatTurn {
    requests += request
    if (request.jsonSchema != null) {
      return ChatTurn(Message.Assistant(classifier), FinishReason.STOP, null, RateLimitInfo.EMPTY)
    }
    error("complete() non previsto nel giro dei tool")
  }

  override fun stream(request: ChatRequest): Flow<ChatDelta> = flow {
    streamed += request
    when (val turn = queue.removeFirstOrNull() ?: Scripted.Text("(fine copione)")) {
      is Scripted.Fail -> throw turn.error
      is Scripted.Text -> {
        turn.text.chunked(7).forEach { emit(ChatDelta.Text(it)); delay(5) }
        emit(ChatDelta.Finish(FinishReason.STOP, null, RateLimitInfo.EMPTY))
      }
      is Scripted.Calls -> {
        turn.preamble?.let { emit(ChatDelta.Text(it)) }
        turn.calls.forEachIndexed { index, (name, args) ->
          emit(ChatDelta.ToolCallPart(index, "call_$index", name, null))
          emit(ChatDelta.ToolCallPart(index, null, null, args.toString()))
        }
        emit(ChatDelta.Finish(FinishReason.TOOL_CALLS, null, RateLimitInfo.EMPTY))
      }
    }
  }

  override suspend fun listModels(): ModelCatalogue = ModelCatalogue(emptyList(), emptyList())
  override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript = Transcript("", null)
}

private class EchoTool(override val name: String, override val group: ToolGroup, private val slowMillis: Long = 0) : AiTool {
  override val description = "eco"
  override val parameters = Schema.obj(mapOf("x" to Schema.str("x")))
  val calls = mutableListOf<JsonObject>()
  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    calls += args
    if (slowMillis > 0) delay(slowMillis)
    return "$name ha ricevuto ${args["x"]?.let { (it as JsonPrimitive).content } ?: "-"}"
  }
}

class AssistantOrchestratorTest {

  private val now = EchoTool("adesso", ToolGroup.HOURLY)
  private val sun = EchoTool("sole", ToolGroup.SKY)
  private val registry = ToolRegistry(listOf(now, sun))
  private val diagnostics = AiDiagnosticsLog()

  private fun context() = ToolContext(
    sourcesProvider = { error("i dati dell'app non servono a questo test") },
    unitsProvider = { error("le unita' non servono a questo test") },
    resourcesProvider = { error("le risorse non servono a questo test") },
    locale = Locale.ITALIAN,
    zone = ZoneId.of("Europe/Rome"),
    nowMillis = 1_700_000_000_000L,
    selected = null,
    actionsEnabled = false,
    actions = ActionSink.Disabled,
  )

  private fun input(question: String, vararg providers: FakeProvider, conversation: Conversation = Conversation(1L, 0L)) = AskInput(
    question = question,
    mode = AskMode.TEXT,
    language = "it",
    settings = AiSettings(),
    providers = providers.map { ReadyProvider(it, "modello-${it.id.id}", "stt", "piccolo") },
    toolContext = context(),
    systemPrompt = "sei un assistente",
    conversation = conversation,
  )

  private fun args(x: String) = buildJsonObject { put("x", JsonPrimitive(x)) }

  @Test
  fun `due giri con chiamate parallele, poi la risposta in streaming, con gli stati in ordine`() = runBlocking {
    val groq = FakeProvider(
      ProviderId.GROQ,
      listOf(
        Scripted.Calls(listOf("adesso" to args("a"), "sole" to args("b")), preamble = "Controllo"),
        Scripted.Text("Piove alle 18. [[radar]]"),
      ),
      classifier = """{"gruppi":["orario","cielo"]}""",
    )
    val states = mutableListOf<AssistantState>()
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val collector = launch(Dispatchers.Unconfined) { state.collect { states += it } }
    val orchestrator = AssistantOrchestrator(registry, diagnostics = diagnostics)
    val result = orchestrator.ask(input("piove?", groq), state)
    collector.cancel()

    assertEquals("Piove alle 18.", result.answer)
    assertEquals(1, result.chips.size)
    assertEquals(listOf("adesso", "sole"), result.toolsUsed)
    assertEquals(listOf(args("a")), now.calls)
    assertEquals(listOf(args("b")), sun.calls)
    // Lo stadio 1 e' andato sul modello piccolo con lo schema; il giro dei tool aveva i tool dei due gruppi.
    assertEquals("piccolo", groq.requests.single().model)
    assertTrue(groq.streamed.first().tools.map { it.name }.containsAll(listOf("adesso", "sole", ToolRegistry.MORE_TOOLS)))
    // Il secondo giro porta la risposta dei tool nell'ordine delle chiamate.
    val second = groq.streamed[1].messages
    val results = second.filterIsInstance<Message.ToolResult>()
    assertEquals(listOf("call_0", "call_1"), results.map { it.callId })
    assertTrue(results[0].content.startsWith("adesso ha ricevuto a"))
    // Il preambolo prima delle tool call non e' mai diventato una risposta.
    assertTrue(states.none { it is AssistantState.Answering && it.partial == "Controllo" })
    assertTrue(states.any { it is AssistantState.Classifying })
    assertTrue(states.any { it is AssistantState.Working && it.statusKey == ToolGroup.HOURLY.statusKey && it.statusExtra == 1 })
    assertTrue(states.any { it is AssistantState.Answering })
    assertEquals(1, diagnostics.entries.value.size)
    assertEquals(listOf("orario", "cielo"), diagnostics.entries.value.first().groups)
  }

  @Test
  fun `un 429 su Groq passa a Gemini con la stessa conversazione`() = runBlocking {
    val groq = FakeProvider(ProviderId.GROQ, listOf(Scripted.Fail(AiError.RateLimited(3.0, RateLimitInfo.EMPTY, message = "429"))))
    val gemini = FakeProvider(ProviderId.GEMINI, listOf(Scripted.Text("Da Gemini: sereno.")))
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val result = AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("che tempo fa?", groq, gemini), state)
    assertEquals("Da Gemini: sereno.", result.answer)
    assertEquals(ProviderId.GEMINI, result.provider)
    assertEquals("modello-gemini", gemini.streamed.single().model)
    assertEquals(listOf(ProviderId.GEMINI), result.log.switchedTo)
    assertTrue(gemini.streamed.single().messages.any { it is Message.User && it.text == "che tempo fa?" })
  }

  @Test
  fun `un 429 senza riserve aspetta il retry-after con il conto alla rovescia`() = runBlocking {
    val groq = FakeProvider(
      ProviderId.GROQ,
      listOf(Scripted.Fail(AiError.RateLimited(1.0, RateLimitInfo.EMPTY, message = "429")), Scripted.Text("Ora va.")),
    )
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val seen = mutableListOf<AssistantState>()
    val collector = launch(Dispatchers.Unconfined) { state.collect { seen += it } }
    val result = AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("?", groq), state)
    collector.cancel()
    assertEquals("Ora va.", result.answer)
    assertTrue(seen.any { it is AssistantState.WaitingRateLimit && it.secondsLeft == 1 })
    assertEquals(1, result.log.waitedSeconds)
  }

  @Test
  fun `dopo sei giri di tool si forza la risposta senza strumenti`() = runBlocking {
    val loops = (1..5).map { Scripted.Calls(listOf("adesso" to args("$it"))) } + Scripted.Text("Basta cosi'.") + Scripted.Text("mai")
    val groq = FakeProvider(ProviderId.GROQ, loops)
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val result = AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("?", groq), state)
    // 5 giri con tool + il sesto forzato senza tool = 6 stream; il settimo copione non parte.
    assertEquals(AssistantOrchestrator.MAX_ROUNDS, groq.streamed.size)
    val last = groq.streamed.last()
    assertTrue(last.tools.isEmpty())
    assertEquals(ToolChoice.None, last.toolChoice)
    assertTrue(last.messages.last() is Message.System)
    assertEquals("Basta cosi'.", result.answer)
    assertEquals(5, now.calls.size)
  }

  @Test
  fun `una tool call al giro forzato si ignora e vale il testo`() = runBlocking {
    val loops = (1..5).map { Scripted.Calls(listOf("adesso" to args("$it"))) } + Scripted.Calls(listOf("adesso" to args("6")), preamble = "Riassumo.")
    val groq = FakeProvider(ProviderId.GROQ, loops)
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val result = AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("?", groq), state)
    assertEquals("Riassumo.", result.answer)
    assertEquals(5, now.calls.size)
  }

  @Test
  fun `altri_tool aggiunge un gruppo al giro dopo`() = runBlocking {
    val groq = FakeProvider(
      ProviderId.GROQ,
      listOf(
        Scripted.Calls(listOf(ToolRegistry.MORE_TOOLS to buildJsonObject { put("gruppo", JsonPrimitive("cielo")) })),
        Scripted.Calls(listOf("sole" to args("z"))),
        Scripted.Text("Tramonta alle 20."),
      ),
    )
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val result = AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("quando tramonta?", groq), state)
    assertEquals("Tramonta alle 20.", result.answer)
    assertTrue(groq.streamed[0].tools.none { it.name == "sole" })
    assertTrue(groq.streamed[1].tools.any { it.name == "sole" })
    assertEquals(listOf(args("z")), sun.calls)
  }

  @Test
  fun `la cancellazione a meta' stream non tocca la conversazione`() = runBlocking {
    val groq = FakeProvider(ProviderId.GROQ, listOf(Scripted.Text("Una risposta lunga che non finira' mai perche' verra' fermata prima.")))
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val conversation = Conversation(1L, 0L)
    val job = launch {
      runCatching { AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("?", groq, conversation = conversation), state) }
    }
    while (state.value !is AssistantState.Answering) delay(2)
    job.cancel(CancellationException("stop"))
    job.join()
    assertTrue(conversation.exchanges.isEmpty())
  }

  @Test
  fun `senza provider la domanda fallisce con NO_KEYS`() = runBlocking {
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val error = runCatching { AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("?"), state) }.exceptionOrNull()
    assertTrue(error is AssistantFailure && (error as AssistantFailure).kind == FailureKind.NO_KEYS)
  }

  @Test
  fun `su OpenRouter lo stadio 1 si salta e il catalogo parte intero`() = runBlocking {
    val openRouter = FakeProvider(ProviderId.OPENROUTER, listOf(Scripted.Text("Tutto sereno.")))
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    AssistantOrchestrator(registry, diagnostics = diagnostics).ask(input("?", openRouter), state)
    assertTrue(openRouter.requests.isEmpty())
    val tools = openRouter.streamed.single().tools.map { it.name }
    assertTrue(tools.containsAll(listOf("adesso", "sole")))
    assertTrue(tools.none { it == ToolRegistry.MORE_TOOLS })
  }
}
