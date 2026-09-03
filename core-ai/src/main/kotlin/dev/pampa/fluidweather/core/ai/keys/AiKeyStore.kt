package dev.pampa.fluidweather.core.ai.keys

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Cosa si sa di una chiave senza leggerla: c'e', e quando e' stata verificata l'ultima volta. */
data class KeyState(val present: Boolean, val verifiedAtMillis: Long?) {
  val verified: Boolean get() = present && verifiedAtMillis != null
}

private val Context.aiKeysStore: DataStore<Preferences> by preferencesDataStore(name = "ai_keys")

/**
 * Le chiavi dei provider IA, cifrate col Keystore e in un file DataStore tutto loro (come
 * `provider_keys`: l'export dei dati puo' escluderlo e basta). La chiave in chiaro non esce mai
 * da un `Flow`: si legge con [key] nel momento in cui serve, e si dimentica.
 *
 * Se il Keystore non riesce piu' a decifrare (telefono ripristinato, chiave invalidata) la voce
 * si cancella e l'utente vede "reinserisci la chiave": meglio di un errore 401 senza spiegazione.
 */
class AiKeyStore(
  private val store: DataStore<Preferences>,
  private val cipher: SecretCipher,
) {

  constructor(context: Context, cipher: SecretCipher = KeystoreCipher()) : this(context.aiKeysStore, cipher)

  val states: Flow<Map<ProviderId, KeyState>> = store.data.map { preferences ->
    ProviderId.entries.associateWith { provider ->
      KeyState(
        present = !preferences[keyOf(provider)].isNullOrBlank(),
        verifiedAtMillis = preferences[verifiedOf(provider)],
      )
    }
  }

  /** Vero se almeno un provider ha una chiave verificata: la condizione per accendere l'assistente. */
  val anyVerified: Flow<Boolean> = states.map { map -> map.values.any { it.verified } }

  suspend fun currentStates(): Map<ProviderId, KeyState> = states.first()

  /** La chiave in chiaro, adesso. Null se manca o se il Keystore non la legge piu' (e allora la toglie). */
  suspend fun key(provider: ProviderId): String? {
    val blob = store.data.first()[keyOf(provider)] ?: return null
    val plain = cipher.decrypt(blob)
    if (plain == null) {
      set(provider, null)
      return null
    }
    return plain.takeIf { it.isNotBlank() }
  }

  /** Salva (cifrata) o rimuove; una chiave nuova parte non verificata. */
  suspend fun set(provider: ProviderId, key: String?) {
    val trimmed = key?.trim()
    store.edit { preferences ->
      if (trimmed.isNullOrEmpty()) {
        preferences.remove(keyOf(provider))
        preferences.remove(verifiedOf(provider))
      } else {
        preferences[keyOf(provider)] = cipher.encrypt(trimmed)
        preferences.remove(verifiedOf(provider))
      }
    }
  }

  suspend fun markVerified(provider: ProviderId, atMillis: Long) {
    store.edit { preferences ->
      if (!preferences[keyOf(provider)].isNullOrBlank()) preferences[verifiedOf(provider)] = atMillis
    }
  }

  private fun keyOf(provider: ProviderId) = stringPreferencesKey("key_${provider.id}")
  private fun verifiedOf(provider: ProviderId) = longPreferencesKey("verified_${provider.id}")
}
