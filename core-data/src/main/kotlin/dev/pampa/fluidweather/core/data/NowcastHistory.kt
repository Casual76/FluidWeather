package dev.pampa.fluidweather.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.pampa.fluidweather.core.model.NowcastVerdictRecord

/** Un verdetto del nowcast, com'era: lo storico che la pagina mostra e la pagella giudichera'. */
@Entity(tableName = "nowcast_verdicts")
data class NowcastVerdictEntity(
  @PrimaryKey val timestampMillis: Long,
  val probability01: Double,
  val probability13: Double,
  val probability36: Double,
  val level: String,
)

@Dao
interface NowcastHistoryDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(record: NowcastVerdictEntity)

  @Query("SELECT * FROM nowcast_verdicts WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
  suspend fun since(sinceMillis: Long): List<NowcastVerdictEntity>

  @Query("SELECT MAX(timestampMillis) FROM nowcast_verdicts")
  suspend fun latestMillis(): Long?

  @Query("DELETE FROM nowcast_verdicts WHERE timestampMillis < :beforeMillis")
  suspend fun pruneOlderThan(beforeMillis: Long)
}

/**
 * Lo storico dei verdetti. [record] scrive al piu' uno ogni [MIN_GAP_MILLIS]: il ciclo in
 * background e la home calcolano lo stesso verdetto a pochi secondi di distanza, e due punti
 * sovrapposti non raccontano niente in piu'.
 */
class NowcastHistoryStore(private val dao: NowcastHistoryDao) {

  suspend fun record(record: NowcastVerdictRecord): Boolean {
    val latest = dao.latestMillis()
    if (latest != null && record.timestampMillis - latest < MIN_GAP_MILLIS) return false
    dao.insert(
      NowcastVerdictEntity(
        timestampMillis = record.timestampMillis,
        probability01 = record.probability01,
        probability13 = record.probability13,
        probability36 = record.probability36,
        level = record.level,
      ),
    )
    return true
  }

  suspend fun since(sinceMillis: Long): List<NowcastVerdictRecord> =
    dao.since(sinceMillis).map {
      NowcastVerdictRecord(it.timestampMillis, it.probability01, it.probability13, it.probability36, it.level)
    }

  suspend fun prune(nowMillis: Long) {
    dao.pruneOlderThan(nowMillis - KEEP_MILLIS)
  }

  suspend fun clear() {
    dao.pruneOlderThan(Long.MAX_VALUE)
  }

  companion object {
    const val MIN_GAP_MILLIS: Long = 10 * 60_000L

    /** Sessanta giorni: la pagella vuole storia, la pagina ventiquattro ore. */
    const val KEEP_MILLIS: Long = 60L * 24 * 3_600_000L
  }
}
