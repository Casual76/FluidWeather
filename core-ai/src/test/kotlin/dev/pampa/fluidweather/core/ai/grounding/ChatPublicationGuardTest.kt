package dev.pampa.fluidweather.core.ai.grounding

import dev.antigravity.fluidengine.ai.orchestrator.*
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPublicationGuardTest {
  private val provider = ProviderId.GROQ
  private fun document(label: String = "A", expires: Long = 5000) = WeatherSummaryDocument(
    "context-$label", "revision-$label", "en", UnitPreferences.METRIC,
    listOf(WeatherSummaryFact("rain", "$label: rain uncertain; no valid ETA.", true),
      WeatherSummaryFact("source", "Radar image estimate; ground uncertainty.", true)),
    listOf("rain", "source"), expires)
  private fun snapshot(time: Long) = WeatherSnapshot("place-7", 44.0, 11.0, time,
    emptyList(), FusedForecast(emptyList(), emptyMap()), null)

  @Test fun `hallucinated precise certain prose is replaced by app facts`() {
    val guard = ChatGrounding(false); guard.document(document())
    val safe = guard.render("Rain definitely stops at 12:03 in exactly 3 minutes", 1000, true)
    assertEquals(document().render(), safe)
    assertFalse(safe.contains("12:03")); assertFalse(safe.contains("definitely"))
  }

  @Test fun `unknown duplicate extra fields and embedded prose cannot publish`() {
    val guard = ChatGrounding(false); guard.document(document())
    for (raw in listOf("{\"factIds\":[\"missing\"]}", "{\"factIds\":[\"fact1\",\"fact1\"]}",
      "{\"factIds\":[\"fact1\"],\"text\":\"certain rain at 12:03\"}", "fact1: rain at 12:03")) {
      assertEquals(document().render(), guard.render(raw, 1000, true))
    }
  }

  @Test fun `known tool detail ids retain required weather source and uncertainty`() {
    val guard = ChatGrounding(false); guard.document(document())
    guard.returned("MODEL: available sample at 18:00; missing samples unknown", 1000)
    val safe = guard.render("{\"factIds\":[\"fact2\"]}", 1100, true)
    assertTrue(safe.contains("rain uncertain")); assertTrue(safe.contains("ground uncertainty"))
    assertTrue(safe.contains("MODEL:")); assertFalse(safe.contains("fact2"))
  }

  @Test fun `expiry and changed request context discard old times`() {
    val guard = ChatGrounding(false); guard.document(document())
    assertEquals(guard.changed(), guard.render("{\"factIds\":[\"fact1\"]}", 5000, true))
    assertEquals(guard.changed(), guard.render("{\"factIds\":[\"fact1\"]}", 1000, false))
  }

  @Test fun `snapshot changed with identical fetch timestamp invalidates mixed facts`() {
    val guard = ChatGrounding(false); val before = snapshot(100)
    guard.observe("place-7", before)
    guard.document(document())
    guard.observe("place-7", before.copy(zoneId = "Europe/Rome"))
    assertEquals(guard.changed(), guard.render("{\"factIds\":[\"fact1\"]}", 1000, true))
  }

  @Test fun `different consulted places retain separate mandatory caveats`() {
    val guard = ChatGrounding(false); guard.document(document("A")); guard.document(document("B"))
    val safe = guard.render("{\"factIds\":[\"fact2\"]}", 1000, true)
    assertTrue(safe.contains("A: rain uncertain")); assertTrue(safe.contains("B: rain uncertain"))
  }

  @Test fun `no facts does not silently become selected place or dry weather`() {
    val guard = ChatGrounding(true)
    assertEquals(guard.unavailable(), guard.render("No rain for 6 hours", 1000, true))
  }

  @Test fun `engine streaming cannot flash and history stores only rendered text`() = runTest {
    val visible = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val seen = mutableListOf<AssistantState>()
    val collect = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { visible.toList(seen) }
    val target = Conversation(1, 0); val request = target.requestCopy()
    val guard = ChatGrounding(false); guard.document(document())
    val raw = bufferedAnswer(visible) { engine ->
      engine.value = AssistantState.Classifying("rain?", provider); yield()
      repeat(3) { engine.value = AssistantState.Answering("rain?", "Certain rain at 12:03 $it", provider); yield() }
      request.exchanges += Exchange("rain?", "Certain rain at 12:03", emptyList(), provider, 1000)
      assertTrue(target.exchanges.isEmpty())
      "Certain rain at 12:03"
    }
    val safe = guard.render(raw, 1000, true)
    commitGroundedHistory(target, request, safe)
    assertTrue(seen.any { it is AssistantState.Classifying })
    assertFalse(seen.any { it is AssistantState.Answering || it is AssistantState.Done })
    assertEquals(safe, target.exchanges.single().answer)
    assertFalse(target.exchanges.single().answer.contains("12:03"))
    assertTrue(target.lastToolRound.isEmpty()); collect.cancel()
  }

  @Test fun `cancelled engine never commits raw exchange or partial`() = runTest {
    val visible = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val target = Conversation(1, 0); val request = target.requestCopy()
    try {
      bufferedAnswer(visible) { engine ->
        engine.value = AssistantState.Answering("rain?", "Rain certain at 12:03", provider)
        request.exchanges += Exchange("rain?", "Rain certain at 12:03", emptyList(), provider, 1000)
        yield(); throw CancellationException("cancelled")
      }
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
    assertTrue(target.exchanges.isEmpty()); assertFalse(visible.value is AssistantState.Answering)
  }

  @Test fun `engine errors leave no raw text in visible state or public history`() = runTest {
    val visible = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val target = Conversation(1, 0)
    try {
      bufferedAnswer(visible) { engine ->
        engine.value = AssistantState.Answering("rain?", "certain rain", provider)
        yield(); throw IllegalStateException("fake provider failure")
      }
      fail("Failure must propagate")
    } catch (_: IllegalStateException) { }
    assertTrue(target.exchanges.isEmpty()); assertFalse(visible.value is AssistantState.Answering)
  }

  @Test fun `completed selection outcome remains visible after its context change`() {
    val guard = ChatGrounding(false); guard.document(document())
    guard.returned("Selected saved place B", 1000, actionResult = true)
    val safe = guard.render("invented old weather", 1100, false)
    assertTrue(safe.contains("Selected saved place B")); assertFalse(safe.contains("rain uncertain"))
  }

  @Test fun `chip prose cannot bypass rendering boundary`() {
    val guard = ChatGrounding(false); guard.observe("place-7", null, "Rome")
    val safe = guard.chips(listOf(AnswerChip("place", "Certain rain at 12:03"), AnswerChip("place", "Rome"), AnswerChip("radar")))
    assertFalse(safe.any { it.value?.contains("12:03") == true })
    assertTrue(safe.any { it.value == "Rome" })
  }

  @Test fun `ad hoc coordinates establish normal identity before any fetch`() {
    val a = PlaceResolver.adHocKey(44.0001, 11.0001)
    val b = PlaceResolver.adHocKey(44.0002, 11.0002)
    assertEquals(WeatherSnapshot.keyFor(PlaceResolver.adHocId(44.0001, 11.0001)), a)
    assertNotEquals(a, b); assertNotEquals(Place.GPS_ID, PlaceResolver.adHocId(44.0001, 11.0001))
    assertFalse(a.startsWith("ai-"))
  }
  @Test fun `real tool registration and fake provider use same publication path`() = runTest {
    val guard = ChatGrounding(false)
    val ctx = dev.pampa.fluidweather.core.ai.tools.ToolContext(
      { error("No data access for an explanation") }, { error("No units needed") }, { error("No resources needed") },
      java.util.Locale.ENGLISH, java.time.ZoneId.of("UTC"), 0, null, false,
      dev.pampa.fluidweather.core.ai.tools.ActionSink.Disabled)
    withContext(guard) {
      dev.pampa.fluidweather.core.ai.tools.WeatherExplanationTool().run(
        kotlinx.serialization.json.buildJsonObject { put("tema", kotlinx.serialization.json.JsonPrimitive("radar")) }, ctx)
    }
    val safe = guard.render("Rain certainly begins in 2 minutes", System.currentTimeMillis(), true)
    assertTrue(safe.startsWith("General explanation"))
    assertFalse(safe.contains("certainly")); assertFalse(safe.contains("2 minutes"))
  }

}
