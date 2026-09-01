package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.AppearanceSettings
import dev.pampa.fluidweather.core.model.GlassLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appearanceStore: DataStore<Preferences> by preferencesDataStore(name = "appearance")

/** La persistenza delle scelte d'aspetto; il modello e la politica vivono in core-model. */
class AppearanceSettingsStore(private val context: Context) {

  val settings: Flow<AppearanceSettings> = context.appearanceStore.data.map { preferences ->
    AppearanceSettings(
      autoGlass = preferences[Keys.AutoGlass] ?: true,
      manualLevel = preferences[Keys.ManualLevel]?.let { name ->
        runCatching { GlassLevel.valueOf(name) }.getOrNull()
      } ?: GlassLevel.FULL,
      reduceOnPowerSave = preferences[Keys.ReduceOnPowerSave] ?: true,
    )
  }

  suspend fun current(): AppearanceSettings = settings.first()

  suspend fun setAutoGlass(enabled: Boolean) {
    context.appearanceStore.edit { it[Keys.AutoGlass] = enabled }
  }

  suspend fun setManualLevel(level: GlassLevel) {
    context.appearanceStore.edit { it[Keys.ManualLevel] = level.name }
  }

  suspend fun setReduceOnPowerSave(enabled: Boolean) {
    context.appearanceStore.edit { it[Keys.ReduceOnPowerSave] = enabled }
  }

  private object Keys {
    val AutoGlass = booleanPreferencesKey("glass_auto")
    val ManualLevel = stringPreferencesKey("glass_manual_level")
    val ReduceOnPowerSave = booleanPreferencesKey("glass_reduce_on_power_save")
  }
}

/**
 * L'ordine della griglia della home, per id di widget. Una stringa CSV e non una tabella:
 * e' UNA preferenza, non un dataset — e un id sconosciuto (widget rimosso in un refactor)
 * si scarta in lettura senza migrazioni.
 */
class HomeLayoutStore(private val context: Context) {

  val order: Flow<List<String>> = context.appearanceStore.data.map { preferences ->
    preferences[Keys.Order]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
  }

  suspend fun current(): List<String> = order.first()

  suspend fun setOrder(ids: List<String>) {
    context.appearanceStore.edit { it[Keys.Order] = ids.joinToString(",") }
  }

  private object Keys {
    val Order = stringPreferencesKey("home_widget_order")
  }
}
