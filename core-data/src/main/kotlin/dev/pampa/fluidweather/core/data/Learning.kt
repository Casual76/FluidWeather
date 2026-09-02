package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattParamsRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Un verdetto iscritto alla verifica: feature e probabilita' grezze (colonne piatte, CSV per le feature). */
@Entity(tableName = "nowcast_issues")
data class NowcastIssueEntity(
  @PrimaryKey val issuedAtMillis: Long,
  /** Le 16 feature, separate da virgola, "NaN" dove mancavano. */
  val features: String,
  val rawProbability01: Double,
  val rawProbability13: Double,
  val rawProbability36: Double,
)

/** L'esito di una finestra di un verdetto iscritto. */
@Entity(tableName = "nowcast_outcomes", primaryKeys = ["issuedAtMillis", "window"], indices = [Index("issuedAtMillis")])
data class NowcastOutcomeEntity(
  val issuedAtMillis: Long,
  val window: String,
  val rained: Boolean,
)

@Dao
interface LearningDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertIssue(issue: NowcastIssueEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOutcome(outcome: NowcastOutcomeEntity)

  @Query("SELECT * FROM nowcast_issues WHERE issuedAtMillis >= :sinceMillis ORDER BY issuedAtMillis ASC")
  suspend fun issuesSince(sinceMillis: Long): List<NowcastIssueEntity>

  @Query("SELECT * FROM nowcast_outcomes WHERE issuedAtMillis >= :sinceMillis")
  suspend fun outcomesSince(sinceMillis: Long): List<NowcastOutcomeEntity>

  @Query("SELECT COUNT(*) FROM nowcast_outcomes")
  suspend fun outcomeCount(): Int

  @Query("DELETE FROM nowcast_issues WHERE issuedAtMillis < :beforeMillis")
  suspend fun pruneIssues(beforeMillis: Long)

  @Query("DELETE FROM nowcast_outcomes WHERE issuedAtMillis < :beforeMillis")
  suspend fun pruneOutcomes(beforeMillis: Long)
}

/** L'archivio da cui il telefono impara: verdetti iscritti, esiti, e il loro incrocio. */
class LearningRepository(private val dao: LearningDao) {

  suspend fun recordIssue(record: NowcastIssueRecord) {
    dao.insertIssue(
      NowcastIssueEntity(
        issuedAtMillis = record.issuedAtMillis,
        features = record.features.joinToString(",") { if (it.isNaN()) "NaN" else it.toString() },
        rawProbability01 = record.rawProbability01,
        rawProbability13 = record.rawProbability13,
        rawProbability36 = record.rawProbability36,
      ),
    )
  }

  suspend fun recordOutcome(record: NowcastOutcomeRecord) {
    dao.insertOutcome(NowcastOutcomeEntity(record.issuedAtMillis, record.window, record.rained))
  }

  suspend fun issuesSince(sinceMillis: Long): List<NowcastIssueRecord> =
    dao.issuesSince(sinceMillis).map { entity ->
      NowcastIssueRecord(
        issuedAtMillis = entity.issuedAtMillis,
        features = entity.features.split(',').map { it.toDoubleOrNull() ?: Double.NaN },
        rawProbability01 = entity.rawProbability01,
        rawProbability13 = entity.rawProbability13,
        rawProbability36 = entity.rawProbability36,
      )
    }

  suspend fun outcomesSince(sinceMillis: Long): List<NowcastOutcomeRecord> =
    dao.outcomesSince(sinceMillis).map { NowcastOutcomeRecord(it.issuedAtMillis, it.window, it.rained) }

  suspend fun outcomeCount(): Int = dao.outcomeCount()

  suspend fun prune(nowMillis: Long) {
    dao.pruneIssues(nowMillis - KEEP_MILLIS)
    dao.pruneOutcomes(nowMillis - KEEP_MILLIS)
  }

  suspend fun clear() {
    dao.pruneIssues(Long.MAX_VALUE)
    dao.pruneOutcomes(Long.MAX_VALUE)
  }

  companion object {
    /** Due anni: gli analoghi vogliono le stagioni, e una riga l'ora pesa poco. */
    const val KEEP_MILLIS: Long = 2L * 365 * 24 * 3_600_000L
  }
}

private val Context.learningStore: DataStore<Preferences> by preferencesDataStore(name = "learning")

/** Le mappe di ricalibrazione per finestra, e quando sono state stimate. */
class LearningStore(private val context: Context) {

  val platt: Flow<Map<String, PlattParamsRecord>> = context.learningStore.data.map { preferences ->
    WINDOWS.mapNotNull { window ->
      val a = preferences[aKey(window)] ?: return@mapNotNull null
      val b = preferences[bKey(window)] ?: return@mapNotNull null
      window to PlattParamsRecord(
        window = window,
        a = a,
        b = b,
        samples = preferences[samplesKey(window)] ?: 0,
        fittedAtMillis = preferences[fittedKey(window)] ?: 0L,
      )
    }.toMap()
  }

  suspend fun current(): Map<String, PlattParamsRecord> = platt.first()

  suspend fun lastFitMillis(): Long = context.learningStore.data.map { it[LastFit] ?: 0L }.first()

  suspend fun save(records: List<PlattParamsRecord>, fittedAtMillis: Long) {
    context.learningStore.edit { preferences ->
      records.forEach { record ->
        preferences[aKey(record.window)] = record.a
        preferences[bKey(record.window)] = record.b
        preferences[samplesKey(record.window)] = record.samples
        preferences[fittedKey(record.window)] = record.fittedAtMillis
      }
      preferences[LastFit] = fittedAtMillis
    }
  }

  suspend fun clear() {
    context.learningStore.edit { it.clear() }
  }

  private companion object {
    val WINDOWS = listOf("0-1h", "1-3h", "3-6h")
    val LastFit = longPreferencesKey("platt_last_fit")
    fun aKey(window: String) = doublePreferencesKey("platt_a_$window")
    fun bKey(window: String) = doublePreferencesKey("platt_b_$window")
    fun samplesKey(window: String) = intPreferencesKey("platt_n_$window")
    fun fittedKey(window: String) = longPreferencesKey("platt_at_$window")
  }
}
