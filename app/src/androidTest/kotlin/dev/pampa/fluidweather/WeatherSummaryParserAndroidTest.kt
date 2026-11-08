package dev.pampa.fluidweather

import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.fluidweather.core.ai.summary.AiWeatherSummaryService
import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Exercise Android's ICU regex implementation through the real isolated service, without IO. */
class WeatherSummaryParserAndroidTest {
  @Test fun androidParserAcceptsKnownIdsAndRejectsExtraProseOrDuplicateKeys() = runBlocking {
    val fake = object : ChatProvider {
      override val id = ProviderId.GROQ
      var payload = " \n{ \"factIds\" : [ \"rain\", \"source\" ] }\t"
      override suspend fun complete(request: ChatRequest) = ChatTurn(
        Message.Assistant(payload), FinishReason.STOP, null, RateLimitInfo())
      override fun stream(request: ChatRequest): Flow<ChatDelta> = error("Streaming forbidden")
      override suspend fun listModels(): ModelCatalogue = error("Catalogue forbidden")
      override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript = error("Speech forbidden")
    }
    val service = AiWeatherSummaryService(
      providers = { listOf(ReadyProvider(fake, "fake-model", "unused", null)) }, clock = { 0L })
    val doc = WeatherSummaryDocument("context", "revision", "en", UnitPreferences.METRIC,
      listOf(WeatherSummaryFact("rain", "Model rain"), WeatherSummaryFact("source", "Model", true)),
      listOf("rain"), 60_000L)
    val valid = service.summarize(doc)
    assertEquals(WeatherSummaryStatus.READY, valid.status)
    assertEquals(listOf("rain", "source"), valid.factIds)
    for (invalid in listOf(
      "{\"factIds\":[\"rain\"]} invented weather",
      "{\"factIds\":[\"rain\"],\"factIds\":[\"source\"]}",
      "{\"factIds\":[\"unknown\"]}",
      "{\"factIds\":[\"rain\",\"rain\"]}")) {
      service.clear()
      fake.payload = invalid
      assertEquals(invalid, WeatherSummaryStatus.FAILED, service.summarize(doc).status)
    }
  }
}
