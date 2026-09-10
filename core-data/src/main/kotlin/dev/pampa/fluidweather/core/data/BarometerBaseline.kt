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
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** I due numeri lenti del barometro: dove sei, e quanto e' la tua pressione normale. */
data class BarometerBaseline(
  /** La quota con cui ridurre al mare, stabile fra un giro e l'altro. Null = ancora ignota. */
  val referenceAltitudeMeters: Double? = null,
  /** La media del livello sui 30 giorni: il metro dell'anomalia. Null = storia troppo corta. */
  val normalHpa: Double? = null,
  val normalUpdatedAtMillis: Long = 0L,
  /**
   * L'ultima temperatura e l'ultimo posto noti.
   *
   * Servono a non cambiare righello quando la rete manca. La riduzione al mare usa la
   * temperatura: senza, si ripiegava sui 15 gradi dell'atmosfera standard, e a cinquecento metri
   * sono tre hPa di traslazione istantanea di tutta la curva — offline e online mostravano due
   * pressioni diverse per gli stessi dati. Le coordinate servono allo stadio 3: senza, la marea
   * atmosferica passava dal prior climatologico a zero, che e' un altro gradino di un hPa e mezzo.
   */
  val lastTemperatureC: Double? = null,
  val latitude: Double? = null,
  val longitude: Double? = null,
)

private val Context.baselineStore: DataStore<Preferences> by preferencesDataStore(
  name = "barometer_baseline",
  // Corrotto = si riparte da vuoto. Senza, ogni lettura E ogni scrittura falliscono per
  // sempre, in silenzio (i chiamanti hanno tutti un runCatching), e l'unico rimedio resta
  // cancellare i dati dell'app.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * La memoria lenta del barometro.
 *
 * **La quota.** Ridurre al livello del mare con la quota GPS del singolo campione voleva dire
 * far entrare nel segnale l'errore verticale del fix: dieci metri sono 1,2 hPa, cioe' piu' del
 * segnale sinottico che si sta cercando di leggere. La quota di riferimento vive qui perche' deve
 * essere *la stessa* fra un refresh e il successivo: la mediana della finestra scorrevole no,
 * cambiava insieme alla finestra e traslava tutta la curva. Si semina con la taratura, si insegue
 * piano, e si aggancia di colpo solo quando il posto e' cambiato davvero.
 *
 * **La normale.** La media del livello sui trenta giorni precedenti — la stessa definizione che
 * il banco usa per addestrare. Prima l'app passava semplicemente null, quindi una delle feature
 * del modello era addestrata su un valore che in produzione non esisteva: il modello se
 * l'aspettava e non la riceveva mai.
 */
class BarometerBaselineStore(private val context: Context) {

  val baseline: Flow<BarometerBaseline> = context.baselineStore.data.map { preferences ->
    BarometerBaseline(
      referenceAltitudeMeters = preferences[Keys.Altitude],
      normalHpa = preferences[Keys.Normal],
      normalUpdatedAtMillis = preferences[Keys.NormalAt] ?: 0L,
      lastTemperatureC = preferences[Keys.Temperature],
      latitude = preferences[Keys.Latitude],
      longitude = preferences[Keys.Longitude],
    )
  }

  suspend fun current(): BarometerBaseline = baseline.first()

  /**
   * La quota osservata di recente entra piano se e' vicina a quella che conosciamo, di colpo se
   * e' lontana: un traslocco o una gita non devono essere inseguiti per giorni, e il
   * ballonzolamento del GPS non deve essere inseguito per niente.
   */
  suspend fun observeAltitude(medianMeters: Double) {
    context.baselineStore.edit { preferences ->
      val known = preferences[Keys.Altitude]
      preferences[Keys.Altitude] = when {
        known == null -> medianMeters
        abs(medianMeters - known) > SNAP_METERS -> medianMeters
        else -> known + FOLLOW_RATE * (medianMeters - known)
      }
    }
  }

  /** L'ultimo posto e l'ultima temperatura noti: si aggiornano quando la rete c'e'. */
  suspend fun observePlace(temperatureCelsius: Double?, latitude: Double?, longitude: Double?) {
    if (temperatureCelsius == null && latitude == null) return
    context.baselineStore.edit { preferences ->
      temperatureCelsius?.let { preferences[Keys.Temperature] = it }
      latitude?.let { preferences[Keys.Latitude] = it }
      longitude?.let { preferences[Keys.Longitude] = it }
    }
  }

  suspend fun saveNormal(normalHpa: Double, nowMillis: Long) {
    context.baselineStore.edit {
      it[Keys.Normal] = normalHpa
      it[Keys.NormalAt] = nowMillis
    }
  }

  suspend fun clear() {
    context.baselineStore.edit { it.clear() }
  }

  private object Keys {
    val Altitude = doublePreferencesKey("reference_altitude_m")
    val Normal = doublePreferencesKey("normal_hpa")
    val NormalAt = longPreferencesKey("normal_at")
    val Temperature = doublePreferencesKey("last_temperature_c")
    val Latitude = doublePreferencesKey("last_latitude")
    val Longitude = doublePreferencesKey("last_longitude")
  }

  companion object {
    /** Oltre quaranta metri non e' rumore del GPS: e' un altro posto. */
    const val SNAP_METERS = 40.0

    /** Un decimo per giro: la quota di casa si corregge in giorni, non in minuti. */
    const val FOLLOW_RATE = 0.1

    /** Sotto quindici giorni di archivio la "normale" e' un'opinione, non una media. */
    const val MIN_NORMAL_DAYS = 15

    /** La finestra della normale, la stessa del banco. */
    const val NORMAL_WINDOW_DAYS = 30
  }
}
