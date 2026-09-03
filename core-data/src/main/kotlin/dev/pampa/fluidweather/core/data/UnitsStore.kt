package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.DistanceUnit
import dev.pampa.fluidweather.core.model.PrecipitationUnit
import dev.pampa.fluidweather.core.model.PressureUnit
import dev.pampa.fluidweather.core.model.TemperatureUnit
import dev.pampa.fluidweather.core.model.UnitOverrides
import dev.pampa.fluidweather.core.model.UnitPreferences
import dev.pampa.fluidweather.core.model.WindUnit
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.unitsStore: DataStore<Preferences> by preferencesDataStore(name = "units")

/**
 * Le unita' scelte dall'utente, una famiglia per volta; assente = dal paese del telefono.
 * Si salva il nome dell'enum: un valore sconosciuto (rinominato in un refactor) torna a null,
 * cioe' al default, senza migrazioni.
 */
class UnitsStore(private val context: Context) {

  val overrides: Flow<UnitOverrides> = context.unitsStore.data.map { preferences ->
    UnitOverrides(
      temperature = preferences[Keys.Temperature]?.let { name -> TemperatureUnit.entries.firstOrNull { it.name == name } },
      wind = preferences[Keys.Wind]?.let { name -> WindUnit.entries.firstOrNull { it.name == name } },
      pressure = preferences[Keys.Pressure]?.let { name -> PressureUnit.entries.firstOrNull { it.name == name } },
      precipitation = preferences[Keys.Precipitation]?.let { name -> PrecipitationUnit.entries.firstOrNull { it.name == name } },
      distance = preferences[Keys.Distance]?.let { name -> DistanceUnit.entries.firstOrNull { it.name == name } },
    )
  }

  /** Le unita' effettive: le scelte sopra il paese del locale corrente. */
  val preferences: Flow<UnitPreferences> = overrides.map { it.resolve(Locale.getDefault().country) }

  suspend fun current(): UnitPreferences = preferences.first()

  /**
   * Le unita' come stato sempre pronto, per chi non puo' sospendere.
   *
   * Serve ai testi delle notifiche: li' c'era un `runBlocking { current() }`, e quella lambda la
   * chiama anche il thread principale (la prova del ciclo dalle impostazioni), dove una lettura
   * bloccante del DataStore e' un ANR che aspetta il momento giusto. Il valore iniziale e' quello
   * del paese del locale: esattamente cio' che si otterrebbe senza scelte salvate.
   */
  fun preferencesIn(scope: CoroutineScope): StateFlow<UnitPreferences> = preferences.stateIn(
    scope,
    SharingStarted.Eagerly,
    UnitOverrides().resolve(Locale.getDefault().country),
  )

  suspend fun setTemperature(unit: TemperatureUnit?) = set(Keys.Temperature, unit?.name)

  suspend fun setWind(unit: WindUnit?) = set(Keys.Wind, unit?.name)

  suspend fun setPressure(unit: PressureUnit?) = set(Keys.Pressure, unit?.name)

  suspend fun setPrecipitation(unit: PrecipitationUnit?) = set(Keys.Precipitation, unit?.name)

  suspend fun setDistance(unit: DistanceUnit?) = set(Keys.Distance, unit?.name)

  private suspend fun set(key: Preferences.Key<String>, value: String?) {
    context.unitsStore.edit { if (value == null) it -= key else it[key] = value }
  }

  private object Keys {
    val Temperature = stringPreferencesKey("temperature")
    val Wind = stringPreferencesKey("wind")
    val Pressure = stringPreferencesKey("pressure")
    val Precipitation = stringPreferencesKey("precipitation")
    val Distance = stringPreferencesKey("distance")
  }
}
