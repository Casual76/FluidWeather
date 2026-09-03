package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tutorialStore: DataStore<Preferences> by preferencesDataStore(name = "tutorials")

/**
 * Cosa l'utente ha gia' visto (fase 21). Sta qui e non nel componente dell'engine perche' la
 * memoria di un suggerimento e' una cosa dell'app: il componente sa disegnare un callout, non
 * cosa questa persona ha gia' imparato.
 *
 * [baselineVersionCode] serve a chi aggiorna: al primo avvio dopo un aggiornamento si segnano
 * visti i suggerimenti delle funzioni che c'erano gia', cosi' l'app non spiega da capo un'app che
 * la persona usa da mesi — e restano solo quelli delle novita'.
 */
class TutorialStore(private val context: Context) {

  val seen: Flow<Set<String>> = context.tutorialStore.data.map { it[Keys.Seen] ?: emptySet() }

  val disabledAll: Flow<Boolean> = context.tutorialStore.data.map { it[Keys.DisabledAll] ?: false }

  val baselineVersionCode: Flow<Int?> = context.tutorialStore.data.map { it[Keys.Baseline] }

  suspend fun currentSeen(): Set<String> = seen.first()

  suspend fun currentBaseline(): Int? = baselineVersionCode.first()

  suspend fun markSeen(id: String) {
    context.tutorialStore.edit { preferences ->
      preferences[Keys.Seen] = (preferences[Keys.Seen] ?: emptySet()) + id
    }
  }

  suspend fun markSeen(ids: Collection<String>) {
    if (ids.isEmpty()) return
    context.tutorialStore.edit { preferences ->
      preferences[Keys.Seen] = (preferences[Keys.Seen] ?: emptySet()) + ids
    }
  }

  /** "Rivedi i suggerimenti": si torna al primo giorno, senza toccare la presentazione. */
  suspend fun resetAll() {
    context.tutorialStore.edit { preferences ->
      preferences[Keys.Seen] = emptySet()
      preferences[Keys.DisabledAll] = false
    }
  }

  suspend fun setDisabledAll(disabled: Boolean) {
    context.tutorialStore.edit { it[Keys.DisabledAll] = disabled }
  }

  suspend fun setBaselineVersionCode(versionCode: Int) {
    context.tutorialStore.edit { it[Keys.Baseline] = versionCode }
  }

  private object Keys {
    val Seen = stringSetPreferencesKey("seen")
    val DisabledAll = booleanPreferencesKey("disabled_all")
    val Baseline = intPreferencesKey("baseline_version_code")
  }
}
