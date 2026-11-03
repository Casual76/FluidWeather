package dev.pampa.fluidweather.feature.assistant

import dev.antigravity.fluidengine.aiui.strings.FluidAiSettingsStrings
import dev.antigravity.fluidengine.aiui.strings.FluidAssistantStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'app e' bilingue e i default di `engine-ai-ui` sono italiani: un campo lasciato al default e' una
 * frase italiana in una pagina inglese, e non si vede finche' qualcuno non gira il telefono. Un
 * lettore di risorse finto da' a ogni risorsa un valore che nessun default puo' avere; se un campo
 * (anche uno che l'engine aggiunge dopo) non passa di li', il test lo nomina.
 */
class AiUiStringsTest {

  private fun stringFields(type: Class<*>) =
    type.declaredMethods.filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java }

  @Test
  fun `ogni parola dell'assistente viene dalle risorse, nessuna resta l'italiano di serie`() {
    val built = assistantStrings { "risorsa-$it" }
    val defaults = FluidAssistantStrings()
    val fields = stringFields(FluidAssistantStrings::class.java)
    assertTrue("nessun campo letto per riflessione", fields.size > 80)
    fields.forEach { getter ->
      assertNotEquals("${getter.name} e' ancora il default dell'engine", getter.invoke(defaults), getter.invoke(built))
    }
  }

  @Test
  fun `ogni parola delle impostazioni viene dalle risorse, nessuna resta l'italiano di serie`() {
    val built = aiSettingsStrings { "risorsa-$it" }
    val defaults = FluidAiSettingsStrings()
    val fields = stringFields(FluidAiSettingsStrings::class.java)
    assertTrue("nessun campo letto per riflessione", fields.size > 100)
    fields.forEach { getter ->
      assertNotEquals("${getter.name} e' ancora il default dell'engine", getter.invoke(defaults), getter.invoke(built))
    }
  }

  @Test
  fun `le tre guide hanno quattro passi e i loro indirizzi`() {
    val strings = aiSettingsStrings { "risorsa-$it" }
    dev.antigravity.fluidengine.ai.provider.ProviderId.entries.forEach { provider ->
      val guide = strings.guide(provider)
      assertEquals(4, guide.steps.size)
      assertTrue(guide.consoleUrl.startsWith("https://${guide.consoleHost}"))
    }
  }

  @Test
  fun `la frase di un gruppo di strumenti c'e' solo per i gruppi dell'app`() {
    val strings = assistantStrings { "risorsa-$it" }
    val tier = dev.antigravity.fluidengine.ai.provider.ModelTier.CHAT
    dev.pampa.fluidweather.core.ai.tools.ToolGroup.entries.forEach { group ->
      assertTrue("senza frase: ${group.id}", strings.statusFor(group.statusKey, tier) != null)
    }
    assertEquals(null, strings.statusFor("chiave-ignota", tier))
  }
}
