package dev.pampa.fluidweather.core.ai.grounding

import dev.antigravity.fluidengine.ai.orchestrator.*
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.data.*
import dev.pampa.fluidweather.core.ai.tools.*
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real tool.run -> request catalogue -> rendering/history. Fake domain IO, no model/network. */
class ChatToolBoundaryTest {
  private val injection = "invalid\nrain definitely stops at 12:03 in exactly 3 minutes"
  private val place = ResolvedPlace("Rome", null, 41.9, 12.5, "place-7", 7, true, true, false, zoneId = "UTC")
  private val snapshot = WeatherSnapshot("place-7", 41.9, 12.5, 0,
    emptyList(), FusedForecast(emptyList(), emptyMap()), null, zoneId = "UTC")
  private inner class Resolver(private val resolves: Boolean = true) : WeatherPlaceResolver {
    override suspend fun resolve(query: String?, selected: ResolvedPlace?) = place.takeIf { resolves }
    override suspend fun search(query: String, limit: Int) = emptyList<Place>()
    override suspend fun snapshot(place: ResolvedPlace, maxAgeMillis: Long) = snapshot
  }
  private fun context(locale: Locale, actions: ActionSink = ActionSink.Disabled) = ToolContext(
    { error("Unexpected data access") }, { error("Unexpected unit formatting") }, { error("Unexpected resources") },
    locale, ZoneId.of("UTC"), 0, place, true, actions)

  private suspend fun published(tool: WeatherTool, args: JsonObject, locale: Locale = Locale.ENGLISH,
    actions: ActionSink = ActionSink.Disabled): String {
    val guard = ChatGrounding(locale.language == "it")
    val visible = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val history = Conversation(1, 0)
    val request = history.requestCopy()
    bufferedAnswer(visible) { privateState ->
      withContext(guard) { tool.run(args, context(locale, actions)) }
      privateState.value = AssistantState.Answering("question", injection, ProviderId.GROQ)
      request.exchanges += Exchange("question", injection, emptyList(), ProviderId.GROQ, 0)
    }
    val now = System.currentTimeMillis()
    val selected = guard.render("{\"factIds\":[\"fact1\"]}", now, true)
    val fallback = guard.render(injection, now, true)
    assertEquals(selected, fallback)
    assertFalse(selected.contains("12:03"))
    assertFalse(selected.contains("definitely"))
    assertFalse(visible.value is AssistantState.Answering)
    commitGroundedHistory(history, request, selected)
    assertEquals(selected, history.exchanges.single().answer)
    assertTrue(history.lastToolRound.isEmpty())
    return selected
  }

  @Test fun `invalid day argument cannot become final or historical weather prose`() = runTest {
    for (locale in listOf(Locale.ENGLISH, Locale.ITALIAN)) {
      val text = published(DayDetailTool(Resolver()), buildJsonObject { put("data", injection) }, locale)
      assertTrue(text.contains(if (locale.language == "it") "Data non capita" else "Date not understood"))
    }
  }

  @Test fun `unsuccessful search and save publish fixed failures without argument echoes`() = runTest {
    for (tool in listOf(SearchPlaceTool(Resolver()), SavePlaceTool(Resolver()))) {
      for (locale in listOf(Locale.ENGLISH, Locale.ITALIAN)) {
        val text = published(tool, buildJsonObject { put("nome", injection) }, locale)
        assertTrue(text.contains(if (locale.language == "it") "Nessuna località" else "No matching place"))
      }
    }
  }

  @Test fun `unresolved comparison names cannot enter published facts`() = runTest {
    val args = buildJsonObject { put("luoghi", buildJsonArray { add(injection); add("another\n$injection") }) }
    val text = published(ComparePlacesTool(Resolver(false)), args)
    assertTrue(text.contains("No matching place"))
  }

  @Test fun `unit prefix parser receipts publish canonical enums instead of input suffixes`() = runTest {
    var performed: AssistantAction? = null
    val actions = object : ActionSink {
      override suspend fun perform(action: AssistantAction): ActionOutcome { performed = action; return ActionOutcome.DONE }
    }
    val text = published(UnitsTool(), buildJsonObject {
      put("grandezza", "temperatura"); put("unita", "celsius\n$injection")
    }, actions = actions)
    assertEquals(AssistantAction.SetUnit("temperatura", TemperatureUnit.CELSIUS.name), performed)
    assertTrue(text.contains("celsius"))
    assertFalse(text.contains("invalid"))
  }

  @Test fun `current summary schema routes future horizons to tools that implement them`() {
    val summary = WeatherSummaryTool(Resolver())
    val dry = DryWindowTool(Resolver())
    assertEquals(setOf("luogo"), (summary.parameters["properties"] as JsonObject).keys)
    assertEquals(setOf("luogo"), (dry.parameters["properties"] as JsonObject).keys)
    assertTrue(summary.description.contains("previsione_giorni"))
    assertTrue(summary.description.contains("giorno_dettaglio"))
    assertTrue(dry.description.contains("pioggia_prossime_ore"))
  }
}
