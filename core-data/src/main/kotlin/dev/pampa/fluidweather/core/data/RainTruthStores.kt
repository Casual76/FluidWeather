package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.LocalClimatologyRecord
import dev.pampa.fluidweather.core.model.LocalClimatologyRecords
import dev.pampa.fluidweather.core.model.MaintenanceThrottle
import kotlinx.coroutines.flow.first

/**
 * Un solo file (`rain_truth`) per tutto cio' che la verita' della pioggia conserva fuori da Room:
 * le climatologie locali e il freno della manutenzione. Un file e non due perche' "Dati e
 * privacy" lo svuota in un colpo solo, e le climatologie sono dati di posizione (le celle in cui
 * si e' stati) che non devono sopravvivere alla cancellazione.
 */
private val Context.rainTruthStore: DataStore<Preferences> by preferencesDataStore(
  name = "rain_truth",
  // Corrotto = si riparte da vuoto: la climatologia si riscarica, il freno si azzera.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Le climatologie locali, una per cella. Per ogni cella sette chiavi: `rc_clima_<k>`,
 * `rc_base_<k>`, `rc_lat_<k>`, `rc_lon_<k>`, `rc_built_<k>`, `rc_used_<k>`, `rc_failed_<k>`. La
 * presenza di `rc_built_<k>` e' cio' che fa esistere la cella: [all] scorre quelle.
 *
 * Preferences e non Room perche' sono poche righe (al piu' tre celle) fatte di stringhe gia'
 * codificate, e perche' chi le scrive (`LocalClimatology`) non ha motivo di far crescere lo schema
 * del database per questo.
 */
class LocalClimatologyStore(private val context: Context) : LocalClimatologyRecords {

  override suspend fun get(cellKey: String): LocalClimatologyRecord? =
    context.rainTruthStore.data.first().recordOf(cellKey)

  override suspend fun all(): List<LocalClimatologyRecord> {
    val preferences = context.rainTruthStore.data.first()
    return preferences.asMap().keys
      .map { it.name }
      .filter { it.startsWith(BUILT_PREFIX) }
      .mapNotNull { preferences.recordOf(it.removePrefix(BUILT_PREFIX)) }
  }

  override suspend fun put(record: LocalClimatologyRecord) {
    val k = record.cellKey
    context.rainTruthStore.edit { preferences ->
      preferences[clima(k)] = record.climatology
      preferences[base(k)] = record.baselines
      preferences[lat(k)] = record.latitude
      preferences[lon(k)] = record.longitude
      preferences[built(k)] = record.builtAtMillis
      preferences[used(k)] = record.usedAtMillis
      // Una riuscita cancella la memoria del fallimento: il prossimo rinnovo, fra sei mesi, non
      // deve trovare un "riprova domani" scritto da un tentativo che poi e' andato a buon fine.
      preferences.remove(failed(k))
    }
  }

  override suspend fun remove(cellKey: String) {
    context.rainTruthStore.edit { preferences ->
      preferences.remove(clima(cellKey))
      preferences.remove(base(cellKey))
      preferences.remove(lat(cellKey))
      preferences.remove(lon(cellKey))
      preferences.remove(built(cellKey))
      preferences.remove(used(cellKey))
      preferences.remove(failed(cellKey))
    }
  }

  /** Aggiorna l'ultimo uso solo di una cella che esiste: un touch non deve inventarne una. */
  override suspend fun touch(cellKey: String, usedAtMillis: Long) {
    context.rainTruthStore.edit { preferences ->
      if (preferences[built(cellKey)] != null) preferences[used(cellKey)] = usedAtMillis
    }
  }

  override suspend fun lastFailureMillis(cellKey: String): Long? =
    context.rainTruthStore.data.first()[failed(cellKey)]

  /**
   * Segna il fallimento, e nello stesso colpo butta i segni vecchi di celle che non sono mai
   * riuscite: chi si sposta molto con la rete che non tiene lascerebbe una chiave per ogni cella
   * visitata, per sempre. Una settimana basta a ricordare "riprova domani" con larghezza.
   */
  override suspend fun markFailure(cellKey: String, atMillis: Long) {
    context.rainTruthStore.edit { preferences ->
      preferences[failed(cellKey)] = atMillis
      val stale = preferences.asMap().entries
        .filter { (key, value) ->
          key.name.startsWith(FAILED_PREFIX) &&
            (value as? Long ?: 0L) < atMillis - FAILURE_MEMORY_MILLIS &&
            preferences[built(key.name.removePrefix(FAILED_PREFIX))] == null
        }
        .map { it.key }
      stale.forEach { preferences -= it }
    }
  }

  /** Dati e privacy: via tutto il file, il freno della manutenzione compreso. */
  override suspend fun clear() {
    context.rainTruthStore.edit { it.clear() }
  }

  private fun Preferences.recordOf(cellKey: String): LocalClimatologyRecord? {
    val builtAt = this[built(cellKey)] ?: return null
    return LocalClimatologyRecord(
      cellKey = cellKey,
      latitude = this[lat(cellKey)] ?: return null,
      longitude = this[lon(cellKey)] ?: return null,
      climatology = this[clima(cellKey)] ?: return null,
      baselines = this[base(cellKey)] ?: return null,
      builtAtMillis = builtAt,
      usedAtMillis = this[used(cellKey)] ?: builtAt,
    )
  }

  internal companion object {
    const val BUILT_PREFIX = "rc_built_"
    const val FAILED_PREFIX = "rc_failed_"

    /** Una settimana: quanto si ricorda un fallimento di una cella che non e' mai riuscita. */
    const val FAILURE_MEMORY_MILLIS: Long = 7L * 24 * 3_600_000L

    fun clima(k: String) = stringPreferencesKey("rc_clima_$k")
    fun base(k: String) = stringPreferencesKey("rc_base_$k")
    fun lat(k: String) = doublePreferencesKey("rc_lat_$k")
    fun lon(k: String) = doublePreferencesKey("rc_lon_$k")
    fun built(k: String) = longPreferencesKey("$BUILT_PREFIX$k")
    fun used(k: String) = longPreferencesKey("rc_used_$k")
    fun failed(k: String) = longPreferencesKey("$FAILED_PREFIX$k")
  }
}

/**
 * L'istante dell'ultima manutenzione della pioggia (giudizi e climatologia), nello stesso file.
 * Vive su disco perche' i worker in background nascono in processi nuovi: un freno in memoria
 * non frenerebbe nessuno, e ogni giro rifarebbe le stesse richieste.
 */
class RainMaintenanceStore(private val context: Context) : MaintenanceThrottle {

  override suspend fun lastRunMillis(): Long? = context.rainTruthStore.data.first()[LastRun]

  override suspend fun markRun(atMillis: Long) {
    context.rainTruthStore.edit { it[LastRun] = atMillis }
  }

  private companion object {
    val LastRun = longPreferencesKey("rain_maintenance_last_run")
  }
}
