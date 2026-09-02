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
  ],
  version = 5,
  exportSchema = false,
)
abstract class FluidWeatherDatabase : RoomDatabase() {

  abstract fun pressureDao(): PressureDao

  abstract fun verificationDao(): VerificationDao

  abstract fun savedLocationsDao(): SavedLocationsDao

  abstract fun nowcastHistoryDao(): NowcastHistoryDao

  abstract fun observationDao(): ObservationDao

  companion object {
    fun build(context: Context): FluidWeatherDatabase =
      Room.databaseBuilder(context, FluidWeatherDatabase::class.java, "fluidweather.db")
        // Fino alla prima release le migrazioni sono distruttive per dichiarazione: nessun
        // utente ha ancora un archivio da proteggere. Questa riga SPARISCE con la fase 18.
        .fallbackToDestructiveMigration()
        .build()
  }
}
