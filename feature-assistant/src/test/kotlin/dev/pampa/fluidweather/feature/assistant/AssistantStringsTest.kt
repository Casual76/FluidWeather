package dev.pampa.fluidweather.feature.assistant

import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.strings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStringsTest {

  @Test
  fun `ogni gruppo di tool ha il suo testo di stato, e nessuno ricade su penso`() {
    val seen = mutableSetOf<Int>()
    ToolGroup.entries.forEach { group ->
      val res = AssistantStrings.statusRes(group.statusKey)
      assertNotEquals("gruppo senza stato: ${group.id}", R.string.ai_status_thinking, res)
      assertTrue("stato duplicato per ${group.id}", seen.add(res))
    }
    assertEquals(R.string.ai_status_thinking, AssistantStrings.statusRes("chiave-ignota"))
    assertEquals(R.string.ai_status_more_tools, AssistantStrings.statusRes("more_tools"))
  }

  @Test
  fun `ogni pagina ha una frase`() {
    val targets = OpenTarget.entries.map { AssistantStrings.targetRes(it) }
    assertEquals(OpenTarget.entries.size, targets.toSet().size)
  }
}
