package dev.pampa.fluidweather.core.ai.keys

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.SecretCipher
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy
import dev.antigravity.fluidengine.ai.provider.ProviderId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il Keystore non c'e' sul computer: qui la cifratura e' un involucro trasparente. */
private class PlainCipher : SecretCipher {
  override fun encrypt(plain: String): String = "plain:$plain"
  override fun decrypt(blob: String): String? = blob.removePrefix("plain:").takeIf { blob.startsWith("plain:") }
}

/**
 * I file DataStore dell'assistente sono quelli scritti dal vecchio core-ai: questi test dicono che,
 * letti dall'engine, danno le stesse scelte, e che le poche cose che l'engine chiama in un altro
 * modo (la politica dei dati di OpenRouter, il contatore dei gratuiti) arrivano lo stesso.
 */
class AiPreferencesTest {

  private fun dataStore(name: String): DataStore<Preferences> {
    val file = File(Files.createTempDirectory("ai-prefs").toFile(), "$name.preferences_pb")
    return PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), produceFile = { file })
  }

  @Test
  fun `le preferenze scritte dal vecchio core-ai si leggono uguali dall'engine`() = runBlocking {
    val data = dataStore("ai")
    // Le chiavi esatte che scriveva il vecchio AiSettingsStore.
    data.edit {
      it[booleanPreferencesKey("enabled")] = true
      it[stringPreferencesKey("chat_order")] = "gemini,openrouter"
      it[stringPreferencesKey("stt_order")] = "groq"
      it[stringPreferencesKey("chat_model_openrouter")] = "x/y:free"
      it[stringPreferencesKey("classifier_model_gemini")] = "gemini-lite"
      it[stringPreferencesKey("openrouter_fallbacks")] = "a/b:free\nc/d:free"
      it[stringPreferencesKey("thinking")] = "HIGH"
      it[booleanPreferencesKey("speak_replies")] = true
      it[booleanPreferencesKey("actions_enabled")] = true
      it[longPreferencesKey("models_refreshed_groq")] = 42L
    }
    val settings = AiSettingsStore(data).current()
    assertTrue(settings.enabled)
    assertEquals(listOf(ProviderId.GEMINI, ProviderId.OPENROUTER, ProviderId.GROQ), settings.chatOrder)
    assertEquals(ProviderId.GROQ, settings.sttOrder.first())
    assertEquals("x/y:free", settings.chatModel(ProviderId.OPENROUTER))
    assertEquals("gemini-lite", settings.classifierModel(ProviderId.GEMINI))
    assertEquals(listOf("a/b:free", "c/d:free"), settings.openRouterFallbacks)
    assertEquals(ThinkingLevel.HIGH, settings.thinking)
    assertTrue(settings.speakReplies)
    assertTrue(settings.actionsEnabled)
    assertEquals(42L, settings.modelsRefreshedAt[ProviderId.GROQ])
    // Nessun profondo scelto: il livello resta la chat.
    assertEquals(null, settings.deepModel(ProviderId.GROQ))
  }

  @Test
  fun `chi aveva chiesto deny a OpenRouter lo ritrova, chi no resta sull'account`() = runBlocking {
    val asked = dataStore("ai")
    asked.edit { it[booleanPreferencesKey("openrouter_deny_data_collection")] = true }
    AiPreferences.migrateLegacy(asked)
    assertEquals(OpenRouterDataPolicy.DENY, AiSettingsStore(asked).current().openRouterDataPolicy)

    val never = dataStore("ai")
    AiPreferences.migrateLegacy(never)
    assertEquals(OpenRouterDataPolicy.ACCOUNT, AiSettingsStore(never).current().openRouterDataPolicy)

    // Una scelta gia' fatta con l'engine non si tocca, anche se la vecchia chiave dice altro.
    val chosen = dataStore("ai")
    chosen.edit { it[booleanPreferencesKey("openrouter_deny_data_collection")] = true }
    AiSettingsStore(chosen).setOpenRouterDataPolicy(OpenRouterDataPolicy.ACCOUNT)
    AiPreferences.migrateLegacy(chosen)
    assertEquals(OpenRouterDataPolicy.ACCOUNT, AiSettingsStore(chosen).current().openRouterDataPolicy)
  }

  @Test
  fun `il contatore dei gratuiti legge le chiavi di prima e riparte col giorno nuovo`() = runBlocking {
    val data = dataStore("ai")
    data.edit {
      it[intPreferencesKey("openrouter_free_count")] = 7
      it[longPreferencesKey("openrouter_free_day")] = 100L
    }
    val counter = OpenRouterFreeCounter(data)
    assertEquals(7, counter.usage.first().countOn(100L))
    assertEquals(0, counter.usage.first().countOn(101L))
    assertEquals(8, counter.count(100L))
    assertEquals(1, counter.count(101L))
  }

  @Test
  fun `le chiavi salvate prima si leggono con lo stesso file e lo stesso cifrario`() = runBlocking {
    val data = dataStore("ai_keys")
    // Il vecchio AiKeyStore scriveva `key_<id>` cifrata e `verified_<id>`.
    data.edit {
      it[stringPreferencesKey("key_groq")] = PlainCipher().encrypt("gsk_vecchia")
      it[longPreferencesKey("verified_groq")] = 5L
    }
    val keys = AiKeyStore(data, PlainCipher())
    assertEquals("gsk_vecchia", keys.key(ProviderId.GROQ))
    assertTrue(keys.currentStates().getValue(ProviderId.GROQ).verified)
    assertEquals("fluidweather.ai", AiPreferences.KEYSTORE_ALIAS)
  }
}
