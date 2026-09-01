package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.SamplingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Il DataStore dell'app, separato da quello dell'engine (`fluid_engine`) per lo stesso motivo per
 * cui quello e' separato da noi: ognuno puo' migrare o azzerare il proprio file senza toccare
 * l'altro.
 */
internal val Context.fluidWeatherStore: DataStore<Preferences> by preferencesDataStore(name = "fluidweather")

data class SamplingSettings(
  /** BILANCIATA finche' l'onboarding (fase 15) non fa scegliere esplicitamente. */
  val mode: SamplingMode = SamplingMode.BILANCIATA,
  /** Il toggle: monitoraggio continuo mentre l'app e' aperta. */
  val continuousWhileOpen: Boolean = false,
)

class SamplingSettingsStore(private val context: Context) {

  val settings: Flow<SamplingSettings> = context.fluidWeatherStore.data.map { preferences ->
    SamplingSettings(
      mode = preferences[Keys.Mode]?.let { name ->
        runCatching { SamplingMode.valueOf(name) }.getOrNull()
      } ?: SamplingSettings().mode,
      continuousWhileOpen = preferences[Keys.ContinuousWhileOpen] ?: false,
    )
  }

  suspend fun current(): SamplingSettings = settings.first()

  suspend fun setMode(mode: SamplingMode) {
    context.fluidWeatherStore.edit { it[Keys.Mode] = mode.name }
  }

  suspend fun setContinuousWhileOpen(enabled: Boolean) {
    context.fluidWeatherStore.edit { it[Keys.ContinuousWhileOpen] = enabled }
  }

  private object Keys {
    val Mode = stringPreferencesKey("sampling_mode")
    val ContinuousWhileOpen = booleanPreferencesKey("continuous_while_open")
  }
}

/**
 * L'override della fusione: "usa solo questo dove e' supportato". Null = cascata normale.
 * Vive accanto alle altre impostazioni dell'app; la UI arriva con Benchmark e Impostazioni.
 */
class FusionSettingsStore(private val context: Context) {

  val onlyProviderId: Flow<String?> = context.fluidWeatherStore.data.map { preferences ->
    preferences[OnlyProvider]?.takeIf { it.isNotBlank() }
  }

  suspend fun currentOnlyProviderId(): String? = onlyProviderId.first()

  suspend fun setOnlyProvider(providerId: String?) {
    context.fluidWeatherStore.edit { preferences ->
      if (providerId.isNullOrBlank()) preferences.remove(OnlyProvider) else preferences[OnlyProvider] = providerId
    }
  }

  private companion object {
    val OnlyProvider = stringPreferencesKey("fusion_only_provider")
  }
}

data class LatestActivity(
  val kind: ActivityKind,
  val confidence: Int,
  val observedAtMillis: Long,
)

/**
 * L'ultima attivita' riconosciuta, scritta dal receiver e letta da chiunque stia per registrare
 * un campione. Vive su DataStore e non in memoria perche' worker e allarmi girano anche a
 * processo appena nato.
 */
class LatestActivityStore(private val context: Context) {

  val latest: Flow<LatestActivity?> = context.fluidWeatherStore.data.map { preferences ->
    val name = preferences[Keys.Kind] ?: return@map null
    val kind = runCatching { ActivityKind.valueOf(name) }.getOrNull() ?: return@map null
    LatestActivity(
      kind = kind,
      confidence = preferences[Keys.Confidence] ?: 0,
      observedAtMillis = preferences[Keys.ObservedAt] ?: 0L,
    )
  }

  suspend fun current(): LatestActivity? = latest.first()

  suspend fun store(activity: LatestActivity) {
    context.fluidWeatherStore.edit { preferences ->
      preferences[Keys.Kind] = activity.kind.name
      preferences[Keys.Confidence] = activity.confidence
      preferences[Keys.ObservedAt] = activity.observedAtMillis
    }
  }

  private object Keys {
    val Kind = stringPreferencesKey("latest_activity_kind")
    val Confidence = intPreferencesKey("latest_activity_confidence")
    val ObservedAt = longPreferencesKey("latest_activity_observed_at")
  }
}
