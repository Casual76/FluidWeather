package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Una riga per lettura del sensore, raffiche comprese: e' l'archivio grezzo su cui il banco di
 * prova rigioca la storia. Le colonne sono piatte (niente TypeConverter) perche' un export deve
 * restare leggibile con qualunque strumento apra un SQLite.
 */
@Entity(tableName = "pressure_samples", indices = [Index("timestampMillis")])
data class PressureSampleEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val timestampMillis: Long,
  val pressureHpa: Double,
  /** Il name() di SampleSource; stringa e non ordinal, cosi' l'archivio sopravvive ai refactor. */
  val source: String,
  val burstId: String?,
  val altitudeMeters: Double?,
  val latitude: Double?,
  val longitude: Double?,
  /** Il name() di ActivityKind. */
  val activity: String,
  val activityConfidence: Int?,
)

@Dao
interface PressureDao {

  @Insert
  suspend fun insertAll(samples: List<PressureSampleEntity>)

  @Query("SELECT * FROM pressure_samples WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
  suspend fun samplesSince(sinceMillis: Long): List<PressureSampleEntity>

  @Query("SELECT * FROM pressure_samples ORDER BY timestampMillis DESC LIMIT :limit")
  fun latest(limit: Int): Flow<List<PressureSampleEntity>>

  @Query("SELECT COUNT(*) FROM pressure_samples")
  fun count(): Flow<Long>

  /**
   * La media oraria della pressione di stazione su una finestra: la materia prima della normale.
   *
   * Media *delle medie orarie*, non media dei campioni: in sorveglianza il telefono legge ogni
   * minuto e in modalita' minima ogni venti, quindi una media grezza peserebbe le ore agitate
   * dieci volte piu' di quelle tranquille — e la normale racconterebbe il campionamento invece
   * del clima.
   */
  @Query(
    "SELECT AVG(oraria) FROM (SELECT AVG(pressureHpa) AS oraria FROM pressure_samples " +
      "WHERE timestampMillis >= :sinceMillis GROUP BY timestampMillis / 3600000)",
  )
  suspend fun averageHourlyPressureSince(sinceMillis: Long): Double?

  @Query("SELECT MIN(timestampMillis) FROM pressure_samples")
  suspend fun oldestTimestamp(): Long?

  @Query("SELECT MAX(timestampMillis) FROM pressure_samples")
  suspend fun newestTimestamp(): Long?

  @Query("SELECT * FROM pressure_samples WHERE burstId = :burstId ORDER BY timestampMillis ASC")
  suspend fun samplesOfBurst(burstId: String): List<PressureSampleEntity>

  @Query("DELETE FROM pressure_samples WHERE timestampMillis < :beforeMillis")
  suspend fun deleteOlderThan(beforeMillis: Long)
}

@Database(
  entities = [
    PressureSampleEntity::class,
    PendingPredictionEntity::class,
    ForecastVerificationEntity::class,
    SavedLocationEntity::class,
    NowcastVerdictEntity::class,
    ObservationEntity::class,
    NowcastIssueEntity::class,
    NowcastOutcomeEntity::class,
  ],
  version = 6,
  exportSchema = false,
)
abstract class FluidWeatherDatabase : RoomDatabase() {

  abstract fun pressureDao(): PressureDao

  abstract fun verificationDao(): VerificationDao

  abstract fun savedLocationsDao(): SavedLocationsDao

  abstract fun nowcastHistoryDao(): NowcastHistoryDao

  abstract fun observationDao(): ObservationDao

  abstract fun learningDao(): LearningDao

  companion object {
    fun build(context: Context): FluidWeatherDatabase =
      // Niente `fallbackToDestructiveMigration()`: c'era, contro il suo stesso commento, e
      // rendeva "gratis" ogni cambio di schema perche' cancellava in silenzio l'archivio
      // barometrico di chiunque. Ora un cambio di versione senza migrazione fa fallire l'apertura
      // in sviluppo, che e' il momento giusto per accorgersene. Toglierla lasciando la versione
      // dov'e' non tocca nessuna installazione esistente: Room chiede una migrazione solo quando
      // il numero cambia.
      Room.databaseBuilder(context, FluidWeatherDatabase::class.java, "fluidweather.db")
        // Il declassamento e' l'unico caso in cui cancellare e' l'unica cosa che si puo' fare:
        // uno schema del futuro non si riporta indietro, e chi installa un APK piu' vecchio dal
        // Pampa Store si troverebbe altrimenti un'app che non apre nemmeno il database — e ogni
        // chiamata che fallisce dentro un runCatching, cioe' un'app viva che non sa piu' niente.
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()
  }
}
