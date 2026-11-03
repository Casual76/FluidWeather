package dev.pampa.fluidweather.core.ai.keys

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.antigravity.fluidengine.ai.keys.AiDataStores
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy
import java.io.File

/**
 * Cio' che FluidWeather sa delle sue preferenze IA e l'engine no: l'alias del Keystore con cui ha
 * sempre cifrato le chiavi, e una chiave scritta dalla versione che aveva ancora un `core-ai` in casa.
 *
 * I due file DataStore (`ai_keys` per le chiavi cifrate, `ai` per le preferenze) sono quelli di
 * sempre, e li apre l'engine: le chiavi con `AiKeyStore(context, ...)`, le preferenze qui con
 * [AiDataStores.open], perche' la migrazione di sotto ha bisogno dello stesso handle. Un file si
 * apre una volta sola per processo: nessun delegato dell'app sullo stesso file.
 */
object AiPreferences {

  /**
   * L'alias della chiave del Keystore con cui FluidWeather ha sempre cifrato le chiavi dei provider.
   * L'engine ne usa un altro (`fluidengine.ai`): senza questo, dopo l'aggiornamento nessuna chiave
   * salvata si decifrerebbe piu'.
   */
  const val KEYSTORE_ALIAS = "fluidweather.ai"

  /** Il file delle preferenze, dove l'ha sempre messo DataStore (`preferencesDataStore(name = "ai")`). */
  fun settingsFile(context: Context): File = File(context.applicationContext.filesDir, "datastore/ai.preferences_pb")

  /** Le preferenze, aperte una volta col gestore della corruzione dell'engine (un file illeggibile riparte da vuoto). */
  fun openSettings(context: Context): DataStore<Preferences> = AiDataStores.open(settingsFile(context))

  private val LegacyDeny = booleanPreferencesKey("openrouter_deny_data_collection")
  private val DataPolicy = stringPreferencesKey("openrouter_data_policy")

  /**
   * Il vecchio core-ai scriveva la politica dei dati di OpenRouter come un interruttore
   * (`openrouter_deny_data_collection`), l'engine la legge come una scelta
   * (`openrouter_data_policy`). Chi aveva chiesto `deny` lo ritrova; chi non aveva scritto niente
   * resta sulla politica dell'account, che e' il default di entrambi. Si scrive solo se la scelta
   * nuova non c'e' ancora, quindi rifarlo a ogni avvio non cambia niente.
   */
  suspend fun migrateLegacy(store: DataStore<Preferences>) {
    store.edit { prefs ->
      if (prefs[LegacyDeny] == true && prefs[DataPolicy] == null) prefs[DataPolicy] = OpenRouterDataPolicy.DENY.name
    }
  }
}
