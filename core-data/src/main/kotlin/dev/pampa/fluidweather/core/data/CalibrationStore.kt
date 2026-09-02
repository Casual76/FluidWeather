package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.CalibrationRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.calibrationStore: DataStore<Preferences> by preferencesDataStore(name = "calibration")

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
      val altitude = record.altitudeMeters
      if (altitude != null) preferences[Keys.Altitude] = altitude else preferences.remove(Keys.Altitude)
    }
  }

  suspend fun clear() {
    context.calibrationStore.edit { it.clear() }
  }

  private object Keys {
    val Bias = doublePreferencesKey("bias_hpa")
    val Confidence = doublePreferencesKey("confidence")
    val At = longPreferencesKey("calibrated_at")
    val Count = intPreferencesKey("sample_count")
    val LocalMsl = doublePreferencesKey("local_msl")
    val ReferenceMsl = doublePreferencesKey("reference_msl")
    val Altitude = doublePreferencesKey("altitude_m")
  }
}

/** Il primo avvio e' stato visto: la presentazione non si ripropone da sola. */
class OnboardingStore(private val context: Context) {

  val done: Flow<Boolean> = context.calibrationStore.data.map { it[DoneKey] ?: false }

  suspend fun setDone(done: Boolean) {
    context.calibrationStore.edit { it[DoneKey] = done }
  }

  private companion object {
    val DoneKey = booleanPreferencesKey("onboarding_done")
  }
}
