package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattMapRecord
import dev.pampa.fluidweather.core.model.PlattMaps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Un verdetto iscritto alla verifica: feature e probabilita' grezze (colonne piatte, CSV per le feature).
 *
 * [modelVersion], [tier] e [roundId] sono le colonne della migrazione 6->7: con quale modello e in
 * quale regime di contesto e' stato emesso il verdetto, e in quale giro. I valori di default
 * sono quelli che la migrazione da' alle righe gia' presenti ("legacy", nessun livello, giro 0) e
 * vanno scritti anche nell'annotazione: Room confronta lo schema reale con questo, default compresi.
 */
@Entity(tableName = "nowcast_issues")
data class NowcastIssueEntity(
  @PrimaryKey val issuedAtMillis: Long,
  /** Le 20 feature, separate da virgola, "NaN" dove mancavano. */
  val features: String,
  val rawProbability01: Double,
  val rawProbability13: Double,
  val rawProbability36: Double,
  @ColumnInfo(defaultValue = "legacy") val modelVersion: String = "legacy",
  val tier: String? = null,
  @ColumnInfo(defaultValue = "0") val roundId: Long = 0L,
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
        modelVersion = record.modelVersion,
        tier = record.tier,
        roundId = record.roundId,
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
        modelVersion = entity.modelVersion,
        tier = entity.tier,
        roundId = entity.roundId,
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

private val Context.learningStore: DataStore<Preferences> by preferencesDataStore(
  name = "learning",
  // Corrotto = si riparte da vuoto. Senza, ogni lettura E ogni scrittura falliscono per
  // sempre, in silenzio (i chiamanti hanno tutti un runCatching), e l'unico rimedio resta
  // cancellare i dati dell'app.
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Le mappe di ricalibrazione per variante di contesto e finestra, con la diagnosi di ognuna, la
 * versione con cui sono state stimate e quando.
 *
 * Il salvataggio **sostituisce tutto** ([replaceAll]): una finestra la cui ristima fallisce perde
 * la vecchia mappa, invece di tenerne una stimata su dati che oggi non basterebbero. E se la
 * versione (modello + regole) non e' quella corrente ([clearIfVersionMismatch]) si azzera ogni
 * cosa, anche le vecchie chiavi `platt_a_<finestra>` di prima delle varianti: nessuna mappa di un
 * modello che non c'e' piu' resta a correggere quello nuovo.
 *
 * Il costruttore col [DataStore] e' quello dei test (un file temporaneo, niente `Context`); l'app
 * usa quello col `Context`.
 */
class LearningStore(private val store: DataStore<Preferences>) {

  constructor(context: Context) : this(context.learningStore)

  val maps: Flow<PlattMaps> = store.data.map { readMaps(it) }

  suspend fun snapshot(): PlattMaps = maps.first()

  suspend fun lastFitMillis(): Long = store.data.map { it[LastFit] ?: 0L }.first()

  /** Una sola modifica atomica: niente stati intermedi in cui le mappe sono meta' vecchie e meta' nuove. */
  suspend fun replaceAll(version: String, records: List<PlattMapRecord>, fittedAtMillis: Long) {
    store.edit { preferences ->
      preferences.clear()
      records.forEach { write(preferences, it) }
      preferences[Version] = version
      preferences[LastFit] = fittedAtMillis
    }
  }

  /** Vero se ha azzerato: la versione salvata (o la sua assenza) non era [version]. */
  suspend fun clearIfVersionMismatch(version: String): Boolean {
    var cleared = false
    store.edit { preferences ->
      if (preferences[Version] != version) {
        preferences.clear()
        preferences[Version] = version
        cleared = true
      }
    }
    return cleared
  }

  suspend fun clear() {
    store.edit { it.clear() }
  }

  private fun readMaps(preferences: Preferences): PlattMaps {
    val records = VARIANT_KEYS.flatMap { variant ->
      WINDOWS.mapNotNull { window ->
        val prefix = "platt_${variant}_${window}_"
        // La riga esiste se esiste il conteggio: e' la prima chiave che si scrive e l'unica sempre presente.
        val samples = preferences[intPreferencesKey(prefix + "n")] ?: return@mapNotNull null
        PlattMapRecord(
          variant = variant,
          window = window,
          a = preferences[doublePreferencesKey(prefix + "a")],
          b = preferences[doublePreferencesKey(prefix + "b")],
          samples = samples,
          wet = preferences[intPreferencesKey(prefix + "wet")] ?: 0,
          dry = preferences[intPreferencesKey(prefix + "dry")] ?: 0,
          status = preferences[stringPreferencesKey(prefix + "status")] ?: "",
          active = preferences[booleanPreferencesKey(prefix + "active")] ?: false,
          guardDeltaBrier = preferences[doublePreferencesKey(prefix + "dbrier")],
          guardUpperBound = preferences[doublePreferencesKey(prefix + "dhigh")],
          guardTestDays = preferences[intPreferencesKey(prefix + "tdays")] ?: 0,
          useRule = preferences[booleanPreferencesKey(prefix + "rule")] ?: false,
          ruleDeltaBrier = preferences[doublePreferencesKey(prefix + "rdbrier")],
          ruleUpperBound = preferences[doublePreferencesKey(prefix + "rdhigh")],
          fittedAtMillis = preferences[longPreferencesKey(prefix + "at")] ?: 0L,
        )
      }
    }
    return PlattMaps(
      version = preferences[Version],
      lastFitMillis = preferences[LastFit] ?: 0L,
      records = records,
    )
  }

  private fun write(preferences: MutablePreferences, record: PlattMapRecord) {
    val prefix = "platt_${record.variant}_${record.window}_"
    preferences[intPreferencesKey(prefix + "n")] = record.samples
    preferences[intPreferencesKey(prefix + "wet")] = record.wet
    preferences[intPreferencesKey(prefix + "dry")] = record.dry
    preferences[stringPreferencesKey(prefix + "status")] = record.status
    preferences[booleanPreferencesKey(prefix + "active")] = record.active
    preferences[intPreferencesKey(prefix + "tdays")] = record.guardTestDays
    preferences[booleanPreferencesKey(prefix + "rule")] = record.useRule
    record.ruleDeltaBrier?.let { preferences[doublePreferencesKey(prefix + "rdbrier")] = it }
    record.ruleUpperBound?.let { preferences[doublePreferencesKey(prefix + "rdhigh")] = it }
    preferences[longPreferencesKey(prefix + "at")] = record.fittedAtMillis
    record.a?.let { preferences[doublePreferencesKey(prefix + "a")] = it }
    record.b?.let { preferences[doublePreferencesKey(prefix + "b")] = it }
    record.guardDeltaBrier?.let { preferences[doublePreferencesKey(prefix + "dbrier")] = it }
    record.guardUpperBound?.let { preferences[doublePreferencesKey(prefix + "dhigh")] = it }
  }

  private companion object {
    val WINDOWS = listOf("0-1h", "1-3h", "3-6h")

    /** Le chiavi delle varianti (`PlattVariant.key`): core-data non dipende da :nowcast, quindi sono qui. */
    val VARIANT_KEYS = listOf("fresh", "stale", "none", "enh")
    val Version = stringPreferencesKey("platt_version")
    val LastFit = longPreferencesKey("platt_last_fit")
  }
}
