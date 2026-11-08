package dev.pampa.fluidweather.core.ai.summary

import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.fluidweather.core.model.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiWeatherSummaryServiceTest {
  private class Fake : ChatProvider {
    override val id = ProviderId.GROQ
    var calls = 0
    var request: ChatRequest? = null
    var answer: suspend () -> ChatTurn = { turn() }
    override suspend fun complete(request: ChatRequest): ChatTurn { calls++; this.request = request; return answer() }
    override fun stream(request: ChatRequest): Flow<ChatDelta> = error("stream forbidden")
    override suspend fun listModels(): ModelCatalogue = error("catalogue forbidden")
    override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript = error("speech forbidden")
  }
  private fun ready(fake: Fake, model: String = "selected") = ReadyProvider(fake, model, "unused", null)
  private fun doc(key: String = "context", expiry: Long = 50_000) = WeatherSummaryDocument(key, "revision", "en",
    UnitPreferences.METRIC, listOf(WeatherSummaryFact("place", "Rome", true), WeatherSummaryFact("rain", "Model rain", true),
      WeatherSummaryFact("limits", "Not a measurement", true), WeatherSummaryFact("current", "Temperature")), listOf("rain"), expiry)

  @Test fun `isolated complete uses only first configured CHAT model and safe flags`() = runTest {
    val first = Fake(); val second = Fake()
    val service = AiWeatherSummaryService({ listOf(ready(first), ready(second)) }, clock = { 0 })
    val result = service.summarize(doc())
    assertEquals(WeatherSummaryStatus.READY, result.status)
    assertEquals(1, first.calls); assertEquals(0, second.calls)
    val request = first.request!!
    assertEquals("selected", request.model); assertTrue(request.tools.isEmpty())
    assertEquals(ToolChoice.None, request.toolChoice); assertFalse(request.webSearch)
    assertFalse(request.parallelToolCalls); assertEquals(256, request.maxOutputTokens)
    assertEquals(2, request.messages.size); assertNotNull(request.jsonSchema)
    assertTrue(doc().render(result.factIds).contains("Not a measurement"))
  }

  @Test fun `strict parser rejects malformed extra duplicate hallucinated and nonstring selections`() = runTest {
    val fake = Fake(); val service = AiWeatherSummaryService({ listOf(ready(fake)) }, clock = { 0 })
    val invalid = listOf("hello", "```json\n{\"factIds\":[\"rain\"]}\n```", "{\"factIds\":[]}",
      "{\"factIds\":[\"invented\"]}", "{\"factIds\":[\"rain\",\"rain\"]}",
      "{\"factIds\":[1]}", "{\"factIds\":[\"rain\"],\"prose\":\"dry\"}",
      "{\"factIds\":[\"rain\"],\"factIds\":[\"place\"]}", "{\"factIds\":[\"rain\"],}",
      "{\"factIds\":[\"rain\"]} trailing", "{\"refusal\":\"no\"}")
    invalid.forEach { payload ->
      fake.answer = { turn(payload) }
      assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc()).status)
    }
    assertEquals(invalid.size, fake.calls)
  }

  @Test fun `truncation blocked tool calls and citations never surface`() = runTest {
    val fake = Fake(); val service = AiWeatherSummaryService({ listOf(ready(fake)) }, clock = { 0 })
    for (reason in FinishReason.entries.filter { it != FinishReason.STOP }) {
      fake.answer = { turn().copy(finishReason = reason) }
      assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc()).status)
    }
    fake.answer = { turn().copy(citations = listOf(Citation("https://invalid.example"))) }
    assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc()).status)
    fake.answer = { turn().copy(message = Message.Assistant("{\"factIds\":[\"rain\"]}", listOf(ToolCall("a", "tool", buildJsonObject {})))) }
    assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc()).status)
  }

  @Test fun `cache uses context revision locale units catalogue and model and clears`() = runTest {
    val fake = Fake(); var model = "a"; var now = 0L
    val service = AiWeatherSummaryService({ listOf(ready(fake, model)) }, clock = { now }, maxEntries = 2)
    val original = doc()
    assertFalse(service.summarize(original).fromCache)
    assertTrue(service.summarize(original.copy(validUntilMillis = 60_000)).fromCache)
    val variants = listOf(original.copy(contextKey = "another-place"), original.copy(dataRevision = "revised-same-place"),
      original.copy(language = "it"), original.copy(units = UnitPreferences.METRIC.copy(temperature = TemperatureUnit.FAHRENHEIT)),
      original.copy(facts = original.facts + WeatherSummaryFact("routine", "New window")))
    variants.forEach { assertFalse(service.summarize(it).fromCache) }
    model = "b"; assertFalse(service.summarize(original).fromCache)
    service.clear(); assertFalse(service.summarize(original).fromCache)
    now = 50_000; assertEquals(WeatherSummaryStatus.INVALIDATED, service.summarize(original).status)
  }

  @Test fun `cached IDs render fresh document facts and expire at original cutoff`() = runTest {
    val fake = Fake(); var now = 0L
    val service = AiWeatherSummaryService({ listOf(ready(fake)) }, clock = { now })
    service.summarize(doc(expiry = 10))
    now = 11
    assertFalse(service.summarize(doc(expiry = 100)).fromCache)
    assertEquals(2, fake.calls)
  }

  @Test fun `disabled and unconfigured never invoke provider`() = runTest {
    val fake = Fake()
    assertEquals(WeatherSummaryStatus.NOT_CONFIGURED, AiWeatherSummaryService({ listOf(ready(fake)) }, { false }, { 0 }).summarize(doc()).status)
    assertEquals(WeatherSummaryStatus.NOT_CONFIGURED, AiWeatherSummaryService({ emptyList() }, clock = { 0 }).summarize(doc()).status)
    assertEquals(0, fake.calls)
  }

  @Test fun `timeout errors and cancellation preserve safe boundary without retry`() = runTest {
    val fake = Fake(); val service = AiWeatherSummaryService({ listOf(ready(fake)) }, clock = { 0 }, timeoutMillis = 10)
    fake.answer = { delay(100); turn() }
    assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc()).status)
    fake.answer = { throw IllegalStateException("private error must not escape") }
    assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc()).status)
    fake.answer = { delay(100); turn() }
    val request = async { service.summarize(doc()) }; runCurrent(); request.cancelAndJoin()
    assertTrue(request.isCancelled)
    fake.answer = { turn() }; assertFalse(service.summarize(doc()).fromCache)
  }

  @Test fun `wipe during in flight completion prevents result and cache write`() = runTest {
    val fake = Fake(); val gate = CompletableDeferred<Unit>()
    val service = AiWeatherSummaryService({ listOf(ready(fake)) }, clock = { 0 })
    fake.answer = { gate.await(); turn() }
    val request = async { service.summarize(doc()) }; runCurrent()
    service.clear(); gate.complete(Unit)
    assertEquals(WeatherSummaryStatus.INVALIDATED, request.await().status)
    fake.answer = { turn() }; assertFalse(service.summarize(doc()).fromCache)
  }

  @Test fun `noncooperative timeout completion cannot write and settings change invalidates`() = runTest {
    val fake = Fake(); var model = "a"; var now = 0L
    val service = AiWeatherSummaryService({ listOf(ready(fake, model)) }, clock = { now }, timeoutMillis = 10)
    fake.answer = { withContext(NonCancellable) { delay(100) }; turn() }
    assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc()).status)
    fake.answer = { model = "b"; turn() }
    assertEquals(WeatherSummaryStatus.INVALIDATED, service.summarize(doc()).status)
    fake.answer = { now = 50_000; turn() }
    assertEquals(WeatherSummaryStatus.INVALIDATED, service.summarize(doc()).status)
  }

  @Test fun `oversized catalogue never goes on network`() = runTest {
    val fake = Fake(); val service = AiWeatherSummaryService({ listOf(ready(fake)) }, clock = { 0 })
    assertEquals(WeatherSummaryStatus.FAILED, service.summarize(doc().copy(facts = listOf(WeatherSummaryFact("rain", "x".repeat(8_001))))).status)
    assertEquals(0, fake.calls)
  }

  companion object {
    private fun turn(text: String = "{\"factIds\":[\"current\"]}") = ChatTurn(Message.Assistant(text), FinishReason.STOP, null, RateLimitInfo())
  }
}
