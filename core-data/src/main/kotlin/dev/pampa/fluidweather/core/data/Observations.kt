package dev.pampa.fluidweather.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.Observation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Una segnalazione dell'utente, com'e' stata fatta: la verita' di riferimento che entra da lui. */
@Entity(tableName = "observations", indices = [Index("timestampMillis")])
data class ObservationEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val timestampMillis: Long,
  /** Il name() di ObservedCondition. */
  val condition: String,
  val latitude: Double?,
  val longitude: Double?,
  val placeName: String?,
)

@Dao
interface ObservationDao {

  @Insert
  suspend fun insert(observation: ObservationEntity): Long

  @Query("SELECT * FROM observations ORDER BY timestampMillis DESC LIMIT :limit")
  fun recent(limit: Int): Flow<List<ObservationEntity>>

  @Query("SELECT * FROM observations WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
  suspend fun since(sinceMillis: Long): List<ObservationEntity>

  @Query("DELETE FROM observations WHERE id = :id")
  suspend fun delete(id: Long)
}

/** L'archivio delle osservazioni: poche righe, ma sono le uniche scritte da un essere umano. */
class ObservationRepository(private val dao: ObservationDao) {

  fun recent(limit: Int = 10): Flow<List<Observation>> = dao.recent(limit).map { list -> list.mapNotNull { it.toModel() } }

  suspend fun since(sinceMillis: Long): List<Observation> = dao.since(sinceMillis).mapNotNull { it.toModel() }

  suspend fun record(
    condition: ObservedCondition,
    timestampMillis: Long,
    latitude: Double?,
    longitude: Double?,
    placeName: String?,
  ): Observation {
    val id = dao.insert(
      ObservationEntity(
        timestampMillis = timestampMillis,
        condition = condition.name,
        latitude = latitude,
        longitude = longitude,
        placeName = placeName,
      ),
    )
    return Observation(id, timestampMillis, condition, latitude, longitude, placeName)
  }

  suspend fun delete(id: Long) = dao.delete(id)

  private fun ObservationEntity.toModel(): Observation? {
    val parsed = runCatching { ObservedCondition.valueOf(condition) }.getOrNull() ?: return null
    return Observation(id, timestampMillis, parsed, latitude, longitude, placeName)
  }
}
