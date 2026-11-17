package dev.pampa.fluidweather.core.ai.orchestrator

import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AskInput
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantFailure
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.Conversation
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.provider.ChatDelta
import dev.antigravity.fluidengine.ai.provider.ChatProvider
import dev.antigravity.fluidengine.ai.provider.ChatRequest
import dev.antigravity.fluidengine.ai.provider.ChatTurn
import dev.antigravity.fluidengine.ai.provider.FinishReason
import dev.antigravity.fluidengine.ai.provider.Message
import dev.antigravity.fluidengine.ai.provider.ModelCatalogue
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.provider.ReadyProvider
import dev.antigravity.fluidengine.ai.provider.ToolChoice
import dev.antigravity.fluidengine.ai.provider.TranscribeOptions
import dev.antigravity.fluidengine.ai.provider.Transcript
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import dev.pampa.fluidweather.core.ai.data.WeatherPlaceResolver
import dev.pampa.fluidweather.core.ai.tools.ActionSink
import dev.pampa.fluidweather.core.ai.tools.DayDetailTool
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.ToolContext
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.core.ai.tools.WeatherTool
import dev.pampa.fluidweather.core.ai.tools.weatherRegistry
import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.weather.ProviderFetch
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.RoundSource
import dev.pampa.fluidweather.core.weather.WeatherRound
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Un turno recitato: testo, tool call, o un errore da lanciare prima di rispondere. */
private sealed interface Scripted {
  data class Text(val text: String) : Scripted
  data class Calls(val calls: List<Pair<String, JsonObject>>, val preamble: String? = null) : Scripted
  data class Fail(val error: Throwable) : Scripted
}

private class FakeProvider(
  override val id: ProviderId,
  turns: List<Scripted>,
  private val router: String = """{"gruppi":["orario"],"profondo":false}""",
  private val beforeToolCalls: suspend () -> Unit = {},
) : ChatProvider {
  private val queue = ArrayDeque(turns)
  val requests = mutableListOf<ChatRequest>()
  val streamed = mutableListOf<ChatRequest>()
  var beforeText: suspend () -> Unit = {}

  override suspend fun complete(request: ChatRequest): ChatTurn {
    requests += request
    if (request.jsonSchema != null) {
      return ChatTurn(Message.Assistant(router), FinishReason.STOP, null, RateLimitInfo.EMPTY)
    }
    error("complete() non previsto nel giro dei tool")
  }

  override fun stream(request: ChatRequest): Flow<ChatDelta> = flow {
    streamed += request
    when (val turn = queue.removeFirstOrNull() ?: Scripted.Text("(fine copione)")) {
      is Scripted.Fail -> throw turn.error
      is Scripted.Text -> {
        beforeText()
        turn.text.chunked(7).forEach { emit(ChatDelta.Text(it)); delay(5) }
        emit(ChatDelta.Finish(FinishReason.STOP, null, RateLimitInfo.EMPTY))
      }
      is Scripted.Calls -> {
        beforeToolCalls()
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

private class EchoTool(override val name: String, override val group: ToolGroup, private val slowMillis: Long = 0) : WeatherTool() {
  override val description = "eco"
  override val parameters = Schema.obj(mapOf("x" to Schema.str("x")))
  val calls = mutableListOf<JsonObject>()
  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    calls += args
    if (slowMillis > 0) delay(slowMillis)
    return "$name ha ricevuto ${args["x"]?.let { (it as JsonPrimitive).content } ?: "-"}"
  }
}

/**
 * Il vecchio orchestratore di casa aveva questi test; l'orchestratore ora e' quello di `engine-ai`,
 * e questi dicono che, configurato come lo configura l'app ([WeatherOrchestrator]), si comporta
 * come prima: stadio 1 sul modello piccolo, giri paralleli, 429 che passa al servizio dopo, sei
 * giri al massimo, `altri_tool`, OpenRouter senza stadio 1, i chip del meteo.
 */
class WeatherOrchestratorTest {

  private val now = EchoTool("adesso", ToolGroup.HOURLY)
  private val sun = EchoTool("sole", ToolGroup.SKY)
  private val registry = weatherRegistry(listOf(now, sun))
  private val diagnostics = AiDiagnosticsLog()
  private val orchestrator = WeatherOrchestrator.create(registry, diagnostics)

  private fun context(selected: ResolvedPlace? = null) = ToolContext(
    sourcesProvider = { error("i dati dell'app non servono a questo test") },
    unitsProvider = { error("le unita' non servono a questo test") },
    resourcesProvider = { error("le risorse non servono a questo test") },
    locale = Locale.ITALIAN,
    zone = ZoneId.of("Europe/Rome"),
    nowMillis = 1_700_000_000_000L,
    selected = selected,
    actionsEnabled = false,
    actions = ActionSink.Disabled,
  )

  private fun input(
    question: String,
    vararg providers: FakeProvider,
    conversation: Conversation = Conversation(1L, 0L),
    selected: ResolvedPlace? = null,
  ) = AskInput(
    question = question,
    mode = AskMode.TEXT,
    language = "it",
    settings = AiSettings(),
    // Con un modello profondo scritto: e' `deepTierEnabled = false` a tenerlo spento, non l'app che lo toglie.
    providers = providers.map { ReadyProvider(it, "modello-${it.id.id}", "stt", "piccolo", deepModel = "profondo-${it.id.id}") },
    toolContext = context(selected),
    systemPrompt = "sei un assistente",
    conversation = conversation,
    actionsEnabled = false,
    chipFilter = WeatherChip::accepts,
  )

  private fun args(x: String) = buildJsonObject { put("x", JsonPrimitive(x)) }

  @Test
  fun `due giri con chiamate parallele, poi la risposta in streaming, con gli stati in ordine`() = runBlocking {
    val groq = FakeProvider(
      ProviderId.GROQ,
      listOf(
        Scripted.Calls(listOf("adesso" to args("a"), "sole" to args("b")), preamble = "Controllo"),
        Scripted.Text("Piove alle 18. [[radar]] [[luogo:Bologna]] [[boh]]"),
      ),
      router = """{"gruppi":["orario","cielo"],"profondo":false}""",
    )
    val states = mutableListOf<AssistantState>()
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val collector = launch(Dispatchers.Unconfined) { state.collect { states += it } }
    val result = orchestrator.ask(input("piove?", groq), state)
    collector.cancel()

    assertEquals("Piove alle 18.", result.answer)
    // I chip che l'app conosce, tradotti nel suo vocabolario; quello sconosciuto sparisce.
    assertEquals(listOf(WeatherChip.Open(OpenTarget.RADAR), WeatherChip.Place("Bologna")), WeatherChip.of(result.chips))
    assertEquals(listOf("adesso", "sole"), result.toolsUsed)
    assertEquals(listOf(args("a")), now.calls)
    assertEquals(listOf(args("b")), sun.calls)
    // Lo stadio 1 e' andato sul modello piccolo con lo schema; il giro dei tool aveva i tool dei due gruppi.
    assertEquals("piccolo", groq.requests.single().model)
    val firstTools = groq.streamed.first().tools.map { it.name }
    assertTrue(firstTools.containsAll(listOf("adesso", "sole", ToolRegistry.MORE_TOOLS)))
    // Il livello profondo resta spento: nessun "modello_avanzato", e risponde sempre la chat.
    assertTrue(ToolRegistry.DEEP_MODEL !in firstTools)
    assertTrue(groq.streamed.all { it.model == "modello-groq" })
    // Il secondo giro porta la risposta dei tool nell'ordine delle chiamate.
    val results = groq.streamed[1].messages.filterIsInstance<Message.ToolResult>()
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
    val result = orchestrator.ask(input("che tempo fa?", groq, gemini), state)
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
    val result = orchestrator.ask(input("?", groq), state)
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
    val result = orchestrator.ask(input("?", groq), state)
    // 5 giri con tool + il sesto forzato senza tool = 6 stream; il settimo copione non parte.
    assertEquals(6, WeatherOrchestrator.config.maxRounds)
    assertEquals(6, groq.streamed.size)
    val last = groq.streamed.last()
    assertTrue(last.tools.isEmpty())
    assertEquals(ToolChoice.None, last.toolChoice)
    assertTrue(last.messages.last() is Message.System)
    assertEquals("Basta cosi'.", result.answer)
    assertEquals(5, now.calls.size)
    assertEquals(1_200, last.maxOutputTokens)
  }

  @Test
  fun `una tool call al giro forzato si ignora e vale il testo`() = runBlocking {
    val loops = (1..5).map { Scripted.Calls(listOf("adesso" to args("$it"))) } + Scripted.Calls(listOf("adesso" to args("6")), preamble = "Riassumo.")
    val groq = FakeProvider(ProviderId.GROQ, loops)
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val result = orchestrator.ask(input("?", groq), state)
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
    val result = orchestrator.ask(input("quando tramonta?", groq), state)
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
      runCatching { orchestrator.ask(input("?", groq, conversation = conversation), state) }
    }
    while (state.value !is AssistantState.Answering) delay(2)
    job.cancel(CancellationException("stop"))
    job.join()
    assertTrue(conversation.exchanges.isEmpty())
  }

  @Test
  fun `wipe during provider stream prevents old GPS from reaching the daily tool`() = runBlocking {
    val directory = Files.createTempDirectory("assistant-wipe").toFile()
    try {
      val guard = DataWipeGuard()
      val store = WeatherSnapshotStore(directory, wipeGuard = guard)
      val now = 1_700_000_000_000L
      val latitude = 43.8321
      val longitude = 11.1994
      val forecast = ForecastBundle("open-meteo", now, latitude, longitude,
        listOf(HourlyPoint(now + 3_600_000L, temperatureC = 12.0)))
      val fused = FusedForecast(emptyList(), emptyMap())
      val key = WeatherSnapshot.GPS_KEY
      store.write(WeatherSnapshot(key, latitude, longitude, now, emptyList(), fused, forecast))
      var toolResolutions = 0
      var refreshes = 0
      val source = object : RoundSource {
        override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
          refreshes++
          val provider = ProviderRegistry.all.first { it.id == ProviderRegistry.OPEN_METEO }
          return WeatherRound(listOf(ProviderFetch(provider, forecast, null)), fused)
        }
      }
      val refresher = WeatherSnapshotRefresher(source, store, clock = { now }, wipeGuard = guard)
      val resolver = object : WeatherPlaceResolver {
        override suspend fun resolve(query: String?, selected: ResolvedPlace?): ResolvedPlace? {
          toolResolutions++
          return selected
        }

        override suspend fun search(query: String, limit: Int): List<Place> = emptyList()

        override suspend fun snapshot(place: ResolvedPlace, maxAgeMillis: Long): WeatherSnapshot =
          refresher.fresh(place.snapshotKey, place.latitude, place.longitude, maxAgeMillis)
            ?: refresher.refresh(place.snapshotKey, place.latitude, place.longitude, registerPredictions = false)
      }
      val tool = DayDetailTool(resolver)
      val diagnostics = AiDiagnosticsLog()
      val daily = WeatherOrchestrator.create(weatherRegistry(listOf(tool)), diagnostics)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val calls = Scripted.Calls(listOf(tool.name to buildJsonObject { put("data", JsonPrimitive("oggi")) }))
      val pending = FakeProvider(ProviderId.OPENROUTER, listOf(calls, Scripted.Text("old reply"))) {
        entered.complete(Unit)
        release.await()
      }
      val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
      val history = Conversation(1L, 0L)
      val turn = launch {
        runAssistantTurn(guard, guard.generation(), state) {
          val cached = requireNotNull(store.read(key))
          val selected = ResolvedPlace("GPS", null, cached.latitude, cached.longitude, key,
            Place.GPS_ID, true, false, true, fromFreshFix = false)
          daily.ask(input("oggi?", pending, conversation = history, selected = selected), state)
        }
      }
      withTimeout(5_000L) { entered.await() }
      guard.wipe { store.clear() }
      release.complete(Unit)
      turn.join()

      assertTrue(!turn.isCancelled)
      assertEquals(AssistantState.Idle, state.value)
      assertTrue(history.exchanges.isEmpty())
      assertTrue(diagnostics.entries.value.isEmpty())
      assertEquals(1, pending.streamed.size)
      assertEquals(0, toolResolutions)
      assertEquals(0, refreshes)
      assertNull(store.read(key))
      assertNull(WeatherSnapshotStore(directory, wipeGuard = guard).read(key))

      val fresh = FakeProvider(ProviderId.OPENROUTER, listOf(calls, Scripted.Text("new reply")))
      runAssistantTurn(guard, guard.generation(), state) {
        val selected = ResolvedPlace("GPS", null, latitude, longitude, key, Place.GPS_ID, true, false, true)
        assertEquals("new reply", daily.ask(input("oggi?", fresh, conversation = history, selected = selected), state).answer)
      }
      assertEquals(1, toolResolutions)
      assertEquals(1, refreshes)
      assertEquals(now, WeatherSnapshotStore(directory, wipeGuard = guard).read(key)?.fetchedAtMillis)
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun `no-tool turn finishing after wipe cannot publish diagnostics`() = runBlocking {
    val guard = DataWipeGuard()
    val shared = AiDiagnosticsLog()
    val scratch = AiDiagnosticsLog()
    val engine = WeatherOrchestrator.create(registry, scratch)
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val entered = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    val old = FakeProvider(ProviderId.OPENROUTER, listOf(Scripted.Text("Old reply")))
    old.beforeText = {
      entered.complete(Unit)
      resume.await()
    }
    var published = false
    val turn = launch {
      runAssistantTurn(guard, guard.generation(), state) {
        engine.ask(input("Old question", old), state)
        publishAssistantResult(scratch, shared) { published = true }
      }
    }
    withTimeout(5_000L) { entered.await() }
    guard.wipe { shared.clear() }
    resume.complete(Unit)
    withTimeout(5_000L) { turn.join() }
    assertEquals("Old question", scratch.entries.value.single().question)
    assertTrue(shared.entries.value.isEmpty())
    assertTrue(!published)

    val fresh = AiDiagnosticsLog()
    runAssistantTurn(guard, guard.generation(), state) {
      val provider = FakeProvider(ProviderId.OPENROUTER, listOf(Scripted.Text("New reply")))
      WeatherOrchestrator.create(registry, fresh).ask(input("New question", provider), state)
      publishAssistantResult(fresh, shared) { published = true }
    }
    assertTrue(published)
    assertEquals("New question", shared.entries.value.single().question)
  }

  @Test
  fun `turn wrapper propagates ordinary cancellation`() = runBlocking {
    val guard = DataWipeGuard()
    val state = MutableStateFlow<AssistantState>(AssistantState.Transcribing)
    val cancelled = CancellationException("user cancelled")
    try {
      runAssistantTurn(guard, guard.generation(), state) { throw cancelled }
      fail("Cancellation was swallowed")
    } catch (e: CancellationException) {
      assertEquals(cancelled.message, e.message)
    }
    assertEquals(AssistantState.Transcribing, state.value)
  }

  @Test
  fun `senza provider la domanda fallisce con NO_KEYS`() = runBlocking {
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val error = runCatching { orchestrator.ask(input("?"), state) }.exceptionOrNull()
    assertTrue(error is AssistantFailure && error.kind == FailureKind.NO_KEYS)
  }

  @Test
  fun `su OpenRouter lo stadio 1 si salta e il catalogo parte intero`() = runBlocking {
    val openRouter = FakeProvider(ProviderId.OPENROUTER, listOf(Scripted.Text("Tutto sereno.")))
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    orchestrator.ask(input("?", openRouter), state)
    assertTrue(openRouter.requests.isEmpty())
    val tools = openRouter.streamed.single().tools.map { it.name }
    assertTrue(tools.containsAll(listOf("adesso", "sole")))
    assertTrue(tools.none { it == ToolRegistry.MORE_TOOLS })
  }

  @Test
  fun `il router ripiega sui tre gruppi di sempre e lascia fuori le azioni spente`() {
    val router = WeatherOrchestrator.router()
    assertNull(router.parse("boh", actionsEnabled = false))
    assertEquals(setOf(ToolGroup.DAILY), router.parse("""{"gruppi":["giornaliero","xyz","app"]}""", actionsEnabled = false)?.groups)
    assertEquals(setOf(ToolGroup.DAILY, ToolGroup.APP), router.parse("""{"gruppi":["giornaliero","app"]}""", actionsEnabled = true)?.groups)
    val fallback = router.fallback(setOf(ToolGroup.SKY))
    assertTrue(fallback.containsAll(setOf(ToolGroup.HOURLY, ToolGroup.NOWCAST, ToolGroup.PRECIP)))
    assertTrue(ToolGroup.SKY in fallback)
    assertTrue(!router.prompt("it", actionsEnabled = false).contains("- app:"))
  }

  @Test
  fun `i chip del meteo sono le pagine senza valore e i posti con un nome`() {
    assertEquals(WeatherChip.Open(OpenTarget.MOON), WeatherChip.of(AnswerChip("luna")))
    assertEquals(WeatherChip.Place("Siena"), WeatherChip.of(AnswerChip("place", "Siena")))
    assertNull(WeatherChip.of(AnswerChip("luogo")))
    assertNull(WeatherChip.of(AnswerChip("radar", "x")))
    assertNull(WeatherChip.of(AnswerChip("sconosciuto")))
    // Lo stesso posto scritto in due lingue e' un chip solo, come prima.
    assertEquals(1, WeatherChip.of(listOf(AnswerChip("luogo", "Siena"), AnswerChip("place", "Siena"))).size)
  }
}
