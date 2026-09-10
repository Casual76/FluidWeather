package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.CalibrationOutcome
import dev.pampa.fluidweather.core.model.CalibrationRecord
import dev.pampa.fluidweather.core.model.PendingCalibrationBurst
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.calibrationStore: DataStore<Preferences> by preferencesDataStore(
  name = "calibration",
  // Corrotto = si riparte da vuoto. Senza, ogni lettura E ogni scrittura falliscono per
  // sempre, in silenzio (i chiamanti hanno tutti un runCatching), e l'unico rimedio resta
  // cancellare i dati dell'app.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** L'ultima taratura del dispositivo, con la sua provenienza; null finche' non ce n'e' una. */
class CalibrationStore(private val context: Context) {

  val record: Flow<CalibrationRecord?> = context.calibrationStore.data.map { preferences ->
    val bias = preferences[Keys.Bias] ?: return@map null
    val at = preferences[Keys.At] ?: return@map null
    CalibrationRecord(
      biasHpa = bias,
      confidence = preferences[Keys.Confidence] ?: 0.0,
      calibratedAtMillis = at,
      sampleCount = preferences[Keys.Count] ?: 0,
      localMslHpa = preferences[Keys.LocalMsl] ?: 0.0,
      referenceMslHpa = preferences[Keys.ReferenceMsl] ?: 0.0,
      altitudeMeters = preferences[Keys.Altitude],
      segmentCount = preferences[Keys.Segments] ?: 1,
      spreadHpa = preferences[Keys.Spread] ?: 0.0,
    )
  }

  suspend fun current(): CalibrationRecord? = record.first()

  suspend fun save(record: CalibrationRecord) {
    context.calibrationStore.edit { preferences ->
      preferences[Keys.Bias] = record.biasHpa
      preferences[Keys.Confidence] = record.confidence
      preferences[Keys.At] = record.calibratedAtMillis
      preferences[Keys.Count] = record.sampleCount
      preferences[Keys.LocalMsl] = record.localMslHpa
      preferences[Keys.ReferenceMsl] = record.referenceMslHpa
      preferences[Keys.Segments] = record.segmentCount
      preferences[Keys.Spread] = record.spreadHpa
      val altitude = record.altitudeMeters
      if (altitude != null) preferences[Keys.Altitude] = altitude else preferences -= Keys.Altitude
    }
  }

  suspend fun clear() {
    context.calibrationStore.edit { it.clear() }
  }

  /**
   * La raffica gia' in archivio che aspetta ancora una stima, e com'e' andato l'ultimo tentativo.
   *
   * Stanno qui e non in memoria perche' devono sopravvivere al riavvio: senza, chi riapriva l'app
   * dopo una taratura fallita non poteva piu' sapere che era fallita, ne' perche', ne' che dieci
   * minuti di campioni erano gia' sul disco pronti da rileggere.
   */
  val pendingBurst: Flow<PendingCalibrationBurst?> = context.calibrationStore.data.map { preferences ->
    val id = preferences[Keys.PendingBurstId] ?: return@map null
    PendingCalibrationBurst(
      burstId = id,
      startedAtMillis = preferences[Keys.PendingFrom] ?: return@map null,
      endedAtMillis = preferences[Keys.PendingTo] ?: return@map null,
    )
  }

  val lastOutcome: Flow<CalibrationOutcome?> = context.calibrationStore.data.map { preferences ->
    preferences[Keys.Outcome]?.let { name -> CalibrationOutcome.entries.firstOrNull { it.name == name } }
  }

  suspend fun setPendingBurst(burst: PendingCalibrationBurst?) {
    context.calibrationStore.edit { preferences ->
      if (burst == null) {
        preferences -= Keys.PendingBurstId
        preferences -= Keys.PendingFrom
        preferences -= Keys.PendingTo
      } else {
        preferences[Keys.PendingBurstId] = burst.burstId
        preferences[Keys.PendingFrom] = burst.startedAtMillis
        preferences[Keys.PendingTo] = burst.endedAtMillis
      }
    }
  }

  suspend fun setOutcome(outcome: CalibrationOutcome?) {
    context.calibrationStore.edit { preferences ->
      if (outcome == null) preferences -= Keys.Outcome else preferences[Keys.Outcome] = outcome.name
    }
  }

  private object Keys {
    val Bias = doublePreferencesKey("bias_hpa")
    val Confidence = doublePreferencesKey("confidence")
    val At = longPreferencesKey("calibrated_at")
    val Count = intPreferencesKey("sample_count")
    val LocalMsl = doublePreferencesKey("local_msl")
    val ReferenceMsl = doublePreferencesKey("reference_msl")
    val Altitude = doublePreferencesKey("altitude_m")
    val Segments = intPreferencesKey("segment_count")
    val Spread = doublePreferencesKey("spread_hpa")
    val PendingBurstId = stringPreferencesKey("pending_burst_id")
    val PendingFrom = longPreferencesKey("pending_burst_from")
    val PendingTo = longPreferencesKey("pending_burst_to")
    val Outcome = stringPreferencesKey("last_outcome")
  }
}

/**
 * Il primo avvio e' stato visto: la presentazione non si ripropone da sola.
 *
 * **Ha un file suo.** Stava dentro quello della taratura, e li' `CalibrationStore.clear()` —
 * cioe' "cancella tutti i dati" — se lo portava via insieme al bias: la presentazione ripartiva, e
 * la sua ultima pagina rilancia la raffica di taratura. Da fuori si vedeva un'app che dopo giorni
 * chiedeva di ritarare senza motivo.
 */
class OnboardingStore(private val context: Context) {

  /**
   * Il valore nuovo; se non c'e' ancora, quello che era rimasto nel file della taratura.
   *
   * Il ripiego non e' per sempre: [migrateFromCalibrationStore] lo sposta al primo avvio e da li'
   * in poi la chiave nuova c'e' sempre, quindi la vecchia non viene piu' nemmeno letta.
   */
  val done: Flow<Boolean> = context.onboardingStore.data.map { preferences ->
    preferences[DoneKey] ?: legacyDone()
  }

  suspend fun setDone(done: Boolean) {
    context.onboardingStore.edit { it[DoneKey] = done }
  }

  /** Sposta la chiave dal file della taratura al proprio. Idempotente: si puo' chiamare sempre. */
  suspend fun migrateFromCalibrationStore() {
    if (context.onboardingStore.data.first().contains(DoneKey)) return
    val legacy = context.calibrationStore.data.first()[DoneKey] ?: return
    context.onboardingStore.edit { it[DoneKey] = legacy }
  }

  private suspend fun legacyDone(): Boolean =
    context.calibrationStore.data.first()[DoneKey] ?: false

  private companion object {
    val DoneKey = booleanPreferencesKey("onboarding_done")
  }
}

private val Context.onboardingStore: DataStore<Preferences> by preferencesDataStore(
  name = "onboarding",
  // Corrotto = si riparte da vuoto. Senza, ogni lettura E ogni scrittura falliscono per
  // sempre, in silenzio (i chiamanti hanno tutti un runCatching), e l'unico rimedio resta
  // cancellare i dati dell'app.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
