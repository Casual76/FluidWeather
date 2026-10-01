package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.LastFix
import kotlinx.coroutines.flow.first

private val Context.lastFixDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "last_fix",
  // Corrotto = si riparte da vuoto: un ultimo fix perso costa un giro del barometro, non l'app.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * L'ultimo fix GPS fresco, su un file suo (`last_fix`).
 *
 * File a parte e non chiavi dentro `fluidweather`: e' un dato di posizione, e "Dati e privacy" lo
 * deve poter cancellare da solo e verificare che sia sparito, senza toccare le impostazioni che
 * restano. Vive su disco e non in memoria perche' i worker in background nascono in processi nuovi
 * e l'ultimo fix e' proprio cio' che serve quando il fix nuovo non arriva.
 */
class LastFixStore(private val context: Context) {

  suspend fun current(): LastFix? {
    val preferences = context.lastFixDataStore.data.first()
    val latitude = preferences[Latitude] ?: return null
    val longitude = preferences[Longitude] ?: return null
    val fixedAt = preferences[FixedAt] ?: return null
    return LastFix(latitude, longitude, fixedAt)
  }

  /**
   * Tiene solo il piu' recente, per l'istante del fix e non per l'ordine d'arrivo: due giri che
   * finiscono fuori ordine (uno col fix vecchio di un quarto d'ora, arrivato dopo) non devono far
   * tornare indietro il punto.
   */
  suspend fun record(fix: LastFix) {
    context.lastFixDataStore.edit { preferences ->
      val stored = preferences[FixedAt]
      if (stored != null && stored > fix.fixedAtMillis) return@edit
      preferences[Latitude] = fix.latitude
      preferences[Longitude] = fix.longitude
      preferences[FixedAt] = fix.fixedAtMillis
    }
  }

  suspend fun clear() {
    context.lastFixDataStore.edit { it.clear() }
  }

  private companion object {
    val Latitude = doublePreferencesKey("last_fix_latitude")
    val Longitude = doublePreferencesKey("last_fix_longitude")
    val FixedAt = longPreferencesKey("last_fix_at")
  }
}
