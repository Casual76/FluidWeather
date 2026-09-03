package dev.pampa.fluidweather.core.ai.orchestrator

import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupClassifierTest {

  private val classifier = GroupClassifier()

  @Test
  fun `il JSON dei gruppi si legge, anche dentro un blocco di codice`() {
    assertEquals(setOf(ToolGroup.PRECIP, ToolGroup.NOWCAST), classifier.parse("""{"gruppi":["precipitazioni","nowcast"]}""", actionsEnabled = false))
    assertEquals(setOf(ToolGroup.HOURLY), classifier.parse("```json\n{\"gruppi\":[\"orario\"]}\n```", actionsEnabled = false))
  }

  @Test
  fun `i gruppi ignoti si ignorano, le azioni cadono se spente, il massimo e' quattro`() {
    assertEquals(setOf(ToolGroup.DAILY), classifier.parse("""{"gruppi":["giornaliero","xyz","app"]}""", actionsEnabled = false))
    assertEquals(setOf(ToolGroup.DAILY, ToolGroup.APP), classifier.parse("""{"gruppi":["giornaliero","app"]}""", actionsEnabled = true))
    val five = classifier.parse("""{"gruppi":["luogo","nowcast","orario","giornaliero","aria"]}""", actionsEnabled = true)!!
    assertEquals(4, five.size)
  }

  @Test
  fun `JSON rotto o vuoto porta al ripiego`() {
    assertNull(classifier.parse("boh", actionsEnabled = false))
    assertNull(classifier.parse("""{"gruppi":[]}""", actionsEnabled = false))
    val fallback = classifier.fallback(setOf(ToolGroup.SKY))
    assertTrue(fallback.containsAll(setOf(ToolGroup.HOURLY, ToolGroup.NOWCAST, ToolGroup.PRECIP)))
    assertTrue(ToolGroup.SKY in fallback)
  }

  @Test
  fun `lo schema elenca i gruppi e il prompt esclude le azioni spente`() {
    assertTrue(classifier.schema.toString().contains("\"precipitazioni\""))
    assertTrue(!classifier.prompt("it", actionsEnabled = false).contains("- app:"))
    assertTrue(classifier.prompt("en", actionsEnabled = true).contains("- app:"))
  }
}
