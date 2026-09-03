package dev.pampa.fluidweather.core.ai.keys

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il Keystore non c'e' sul computer: qui la cifratura e' un involucro trasparente. */
private class PlainCipher : SecretCipher {
  var decryptFails = false
  override fun encrypt(plain: String): String = "plain:$plain"
  override fun decrypt(blob: String): String? =
    if (decryptFails) null else blob.removePrefix("plain:").takeIf { blob.startsWith("plain:") }
}

class AiKeyStoreTest {

  private fun store(): Pair<AiKeyStore, DataStore<Preferences>> {
    val file = File(Files.createTempDirectory("ai-keys").toFile(), "keys.preferences_pb")
    val data = PreferenceDataStoreFactory.create(
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
      produceFile = { file },
    )
    return AiKeyStore(data, PlainCipher()) to data
  }

  @Test
  fun `salvare la prima chiave di un provider non esplode`() = runBlocking {
    // Il crash del 2026-09-03: `MutablePreferences.remove` restituisce il valore tolto, e su una
    // chiave di tipo primitivo mai scritta prima Kotlin lo spacchetta da null. Salvare la PRIMA
    // chiave passava proprio di li', quindi non c'era modo di configurare l'assistente.
    val (keys, _) = store()
    keys.set(ProviderId.GROQ, "gsk_finta")
    assertEquals("gsk_finta", keys.key(ProviderId.GROQ))
    val states = keys.states.first()
    assertTrue(states.getValue(ProviderId.GROQ).present)
    assertFalse("una chiave nuova parte non verificata", states.getValue(ProviderId.GROQ).verified)
  }

  @Test
  fun `una chiave nuova cancella la verifica di quella vecchia`() = runBlocking {
    val (keys, _) = store()
    keys.set(ProviderId.GEMINI, "prima")
    keys.markVerified(ProviderId.GEMINI, atMillis = 1_000)
    assertTrue(keys.states.first().getValue(ProviderId.GEMINI).verified)

    keys.set(ProviderId.GEMINI, "seconda")
    val states = keys.states.first()
    assertTrue(states.getValue(ProviderId.GEMINI).present)
    assertFalse(states.getValue(ProviderId.GEMINI).verified)
    assertEquals("seconda", keys.key(ProviderId.GEMINI))
  }

  @Test
  fun `togliere una chiave che non c'e' e' un'operazione tranquilla`() = runBlocking {
    val (keys, _) = store()
    keys.set(ProviderId.OPENROUTER, null)
    keys.set(ProviderId.OPENROUTER, null)
    assertNull(keys.key(ProviderId.OPENROUTER))
    assertFalse(keys.states.first().getValue(ProviderId.OPENROUTER).present)
  }

  @Test
  fun `una chiave che il Keystore non legge piu' si toglie da sola`() = runBlocking {
    val file = File(Files.createTempDirectory("ai-keys").toFile(), "keys.preferences_pb")
    val data = PreferenceDataStoreFactory.create(
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
      produceFile = { file },
    )
    val cipher = PlainCipher()
    val keys = AiKeyStore(data, cipher)
    keys.set(ProviderId.GROQ, "gsk_finta")
    keys.markVerified(ProviderId.GROQ, atMillis = 1_000)

    cipher.decryptFails = true
    assertNull(keys.key(ProviderId.GROQ))
    val states = keys.states.first()
    assertFalse("la voce illeggibile sparisce, cosi' l'utente la reinserisce", states.getValue(ProviderId.GROQ).present)
  }
}
