package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Le chiavi API dell'utente (BYO-key). File DataStore separato e dedicato: nessun segreto
 * nell'APK, nessun segreto nel repo, nessun segreto mescolato alle preferenze — e un domani
 * l'export dei dati puo' escludere questo file e nient'altro.
 */
private val Context.providerKeysStore: DataStore<Preferences> by preferencesDataStore(name = "provider_keys")

class ProviderKeysStore(private val context: Context) {

  /** providerId -> chiave. Solo le chiavi presenti e non vuote. */
  val keys: Flow<Map<String, String>> = context.providerKeysStore.data.map { preferences ->
    preferences.asMap().entries
      .mapNotNull { (key, value) ->
        val id = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) }
        val secret = value as? String
        if (id != null && !secret.isNullOrBlank()) id to secret else null
      }
      .toMap()
  }

  suspend fun current(): Map<String, String> = keys.first()

  suspend fun set(providerId: String, key: String?) {
    context.providerKeysStore.edit { preferences ->
      val preferenceKey = stringPreferencesKey("$PREFIX$providerId")
      if (key.isNullOrBlank()) preferences -= preferenceKey else preferences[preferenceKey] = key
    }
  }

  private companion object {
    const val PREFIX = "key_"
  }
}
