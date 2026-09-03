package dev.pampa.fluidweather.core.ai.keys

import dev.pampa.fluidweather.core.ai.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiSettingsStoreTest {

  @Test
  fun `l'ordine salvato accoda i provider che non nomina`() {
    assertEquals(
      listOf(ProviderId.GEMINI, ProviderId.GROQ, ProviderId.OPENROUTER),
      AiSettingsStore.parseOrder("gemini,groq"),
    )
    assertEquals(ProviderId.defaultOrder, AiSettingsStore.parseOrder(null))
    assertEquals(
      listOf(ProviderId.OPENROUTER, ProviderId.GROQ, ProviderId.GEMINI),
      AiSettingsStore.parseOrder("openrouter, sconosciuto ,openrouter"),
    )
  }

  @Test
  fun `i default dei modelli seguono le decisioni di prodotto`() {
    val settings = AiSettings()
    assertEquals("qwen/qwen3.8-27b", settings.chatModel(ProviderId.GROQ))
    assertEquals("gemini-3.6-flash", settings.chatModel(ProviderId.GEMINI))
    assertNull(settings.chatModel(ProviderId.OPENROUTER))
    assertEquals("whisper-large-v3", settings.sttModel(ProviderId.GROQ))
    assertEquals("gemini-3.5-transcribe", settings.sttModel(ProviderId.GEMINI))
    assertEquals("openai/whisper-large-v3", settings.sttModel(ProviderId.OPENROUTER))
    assertEquals("llama-3.1-8b-instant", settings.classifierModel(ProviderId.GROQ))
    assertNull(settings.classifierModel(ProviderId.OPENROUTER))
    val chosen = settings.copy(chatModels = mapOf(ProviderId.OPENROUTER to "x/y:free"))
    assertEquals("x/y:free", chosen.chatModel(ProviderId.OPENROUTER))
  }
}
