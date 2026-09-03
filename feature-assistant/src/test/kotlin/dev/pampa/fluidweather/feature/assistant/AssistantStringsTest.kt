package dev.pampa.fluidweather.feature.assistant

import dev.pampa.fluidweather.core.ai.keys.ThinkingLevel
import dev.pampa.fluidweather.core.ai.orchestrator.FailureKind
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
  fun `ogni errore e ogni pagina hanno una frase`() {
    val failures = FailureKind.entries.map { AssistantStrings.failureRes(it) }
    assertEquals(FailureKind.entries.size, failures.toSet().size)
    val targets = OpenTarget.entries.map { AssistantStrings.targetRes(it) }
    assertEquals(OpenTarget.entries.size, targets.toSet().size)
    assertEquals(3, ThinkingLevel.entries.map { AssistantStrings.thinkingRes(it) }.toSet().size)
  }

  @Test
  fun `il markdown leggero legge grassetto, elenchi e paragrafi anche a meta' stream`() {
    val blocks = MarkdownLite.blocks("Pioggia **alle 18**, dice il radar.\n\n- prima\n- seconda *voce*\n1. terza\n\nFine **aperto")
    assertEquals(5, blocks.size)
    assertTrue(blocks[0] is MarkdownLite.Block.Paragraph)
    assertEquals("Pioggia alle 18, dice il radar.", (blocks[0] as MarkdownLite.Block.Paragraph).text.text)
    assertEquals("prima", (blocks[1] as MarkdownLite.Block.Bullet).text.text)
    assertEquals("seconda voce", (blocks[2] as MarkdownLite.Block.Bullet).text.text)
    assertEquals("1", (blocks[3] as MarkdownLite.Block.Bullet).ordinal)
    assertEquals("Fine **aperto", (blocks[4] as MarkdownLite.Block.Paragraph).text.text)
    assertEquals("Pioggia alle 18, dice il radar.\nprima\nseconda voce\nterza\nFine **aperto", MarkdownLite.plainText("Pioggia **alle 18**, dice il radar.\n\n- prima\n- seconda *voce*\n1. terza\n\nFine **aperto"))
  }
}
