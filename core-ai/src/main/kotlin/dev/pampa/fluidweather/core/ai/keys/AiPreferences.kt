package dev.pampa.fluidweather.core.ai.keys

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * I due file DataStore dell'assistente, gli stessi di sempre (`ai_keys` per le chiavi cifrate,
 * `ai` per le preferenze), aperti qui e passati a `AiKeyStore` e `AiSettingsStore` dell'engine.
 *
 * Perche' qui e non coi costruttori a `Context` dell'engine: quelli aprono gli stessi file senza un
 * gestore della corruzione, e un file corrotto vuol dire ogni lettura E ogni scrittura fallite per
 * sempre, in silenzio (i chiamanti hanno tutti un runCatching), con la cancellazione dei dati come
 * unico rimedio. Un file si apre una volta sola per processo: nessuno deve chiamare anche il
 * costruttore a `Context` dell'engine, o DataStore si rifiuta di avere due istanze sullo stesso file.
 */
internal val Context.aiKeysDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "ai_keys",
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal val Context.aiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "ai",
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Cio' che FluidWeather sa delle sue preferenze IA e l'engine no. */
object AiPreferences {

  /**
   * L'alias della chiave del Keystore con cui FluidWeather ha sempre cifrato le chiavi dei provider.
   * L'engine ne usa un altro (`fluidengine.ai`): senza questo, dopo l'aggiornamento nessuna chiave
   * salvata si decifrerebbe piu'.
   */
  const val KEYSTORE_ALIAS = "fluidweather.ai"

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

/** Quante richieste ai modelli `:free` di OpenRouter sono partite in un giorno. */
data class OpenRouterFreeUsage(val epochDay: Long = 0L, val count: Int = 0) {
  /** Le richieste di [today]: un giorno nuovo riparte da zero. */
  fun countOn(today: Long): Int = if (epochDay == today) count else 0
}

/**
 * Il contatore dei gratuiti di OpenRouter (50 al giorno, 1000 con crediti), sulle stesse chiavi del
 * file `ai` di prima. L'engine non lo conosce: e' un adattatore dell'app, da portare in
 * `FluidOpenRouterQuota` di engine-ai-ui con la 3.0.
 *
 * Nota onesta: [count] non lo chiama nessuno, ne' prima ne' adesso (il vecchio
 * `countOpenRouterFreeRequest` non aveva chiamanti), quindi la scheda della chiave mostra sempre 0.
 * Il comportamento resta quello; il collegamento e' una decisione a parte.
 */
class OpenRouterFreeCounter(private val store: DataStore<Preferences>) {

  val usage: Flow<OpenRouterFreeUsage> = store.data.map { prefs ->
    OpenRouterFreeUsage(epochDay = prefs[Day] ?: 0L, count = prefs[Count] ?: 0)
  }

  /** Una richiesta in piu' verso un modello gratuito di OpenRouter, oggi; il giorno nuovo azzera. */
  suspend fun count(epochDay: Long): Int {
    var count = 0
    store.edit { prefs ->
      val sameDay = prefs[Day] == epochDay
      count = (if (sameDay) prefs[Count] ?: 0 else 0) + 1
      prefs[Day] = epochDay
      prefs[Count] = count
    }
    return count
  }

  private companion object {
    val Count = intPreferencesKey("openrouter_free_count")
    val Day = longPreferencesKey("openrouter_free_day")
  }
}
