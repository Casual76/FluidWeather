package dev.pampa.fluidweather.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.antigravity.fluidengine.foundation.UpdateChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.releaseStore: DataStore<Preferences> by preferencesDataStore(
  name = "release",
  // Corrotto = si riparte da vuoto. Senza, ogni lettura E ogni scrittura falliscono per
  // sempre, in silenzio (i chiamanti hanno tutti un runCatching), e l'unico rimedio resta
  // cancellare i dati dell'app.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Le scelte sull'aggiornamento (fase 18): il canale seguito e la versione che l'utente ha
 * deciso di saltare. Il canale di default lo decide la build ("beta" finche' l'app esce come
 * beta), cosi' chi installa una beta continua a riceverle senza toccare niente.
 */
class ReleaseSettingsStore(
  private val context: Context,
  private val defaultChannel: UpdateChannel,
) {

  val channel: Flow<UpdateChannel> = context.releaseStore.data.map { preferences ->
    preferences[Keys.Channel]?.let { name -> UpdateChannel.entries.firstOrNull { it.name == name } } ?: defaultChannel
  }

  val ignoredVersion: Flow<String> = context.releaseStore.data.map { it[Keys.IgnoredVersion].orEmpty() }

  suspend fun currentChannel(): UpdateChannel = channel.first()

  suspend fun setChannel(channel: UpdateChannel) {
    context.releaseStore.edit { it[Keys.Channel] = channel.name }
  }

  suspend fun setIgnoredVersion(version: String) {
    context.releaseStore.edit { it[Keys.IgnoredVersion] = version }
  }

  private object Keys {
    val Channel = stringPreferencesKey("update_channel")
    val IgnoredVersion = stringPreferencesKey("ignored_version")
  }

  companion object {
    fun channelFromName(name: String): UpdateChannel =
      UpdateChannel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UpdateChannel.STABLE
  }
}
