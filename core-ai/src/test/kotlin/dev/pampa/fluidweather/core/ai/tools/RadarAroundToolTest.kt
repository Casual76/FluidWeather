package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import dev.pampa.fluidweather.core.weather.radar.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class RadarAroundToolTest {
  private val noon = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
  private val place = ResolvedPlace("Test", null, 44.0, 11.0, "place-1", 1, true, true, false)
  private fun context() = ToolContext(
    { error("No application source should be accessed") },
    { error("No units are needed for unavailable or expired input") },
    { error("No Android resources are needed") },
    Locale.ITALIAN, ZoneId.of("Europe/Rome"), noon, place, false, ActionSink.Disabled,
  )
  private fun sample(frame: Long = noon, arrival: Int = 10) = RadarSample.Ok(RadarReading(
    frame, 0, 30, 0, null, 30.0, 0, 30, RadarTrend.STEADY, 0.0,
    Motion(30.0, 270.0, 90.0, 2, 5.0, true, false, 10.0, 0.0), Eta(arrival, 30, null, 60),
    emptyList(), 1.0, 0.8, emptyList(), geographicCoverage = GeographicCoverage.SUPPORTED,
    complete = true,
  ))

  @Test fun `delayed chat tool never offers an already passed arrival as future`() = runTest {
    var now = noon
    var loads = 0
    val tool = RadarAroundTool({ _, selected -> selected }, { _, _, _ ->
      loads++; now = noon + 12 * 60_000; sample()
    }, { now })
    val answer = tool.answer(JsonObject(emptyMap()), context())
    assertEquals(1, loads)
    assertTrue(answer.contains("transizione stimata gia' trascorsa"))
    assertFalse(answer.contains("tra ~10 min"))
  }

  @Test fun `frame freshness is checked after sampling rather than at chat start`() = runTest {
    var now = noon
    val tool = RadarAroundTool({ _, selected -> selected }, { _, _, _ ->
      now = noon + 21 * 60_000; sample()
    }, { now })
    val answer = tool.answer(JsonObject(emptyMap()), context())
    assertTrue(answer.startsWith("radar non disponibile"))
    assertFalse(answer.contains("stima di arrivo"))
  }

  @Test fun `a newer frame is not rejected against the stale chat start time`() = runTest {
    var now = noon
    val tool = RadarAroundTool({ _, selected -> selected }, { _, _, _ ->
      now = noon + 16 * 60_000; sample(noon + 12 * 60_000, arrival = 3)
    }, { now })
    val answer = tool.answer(JsonObject(emptyMap()), context())
    assertTrue(answer.contains("transizione stimata gia' trascorsa"))
    assertFalse(answer.startsWith("radar non disponibile"))
  }
}
