package dev.pampa.fluidweather.core.weather

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.data.LearningDao
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.LearningStore
import dev.pampa.fluidweather.core.data.NowcastIssueEntity
import dev.pampa.fluidweather.core.data.NowcastOutcomeEntity
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattMapRecord
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.learning.PlattRefitPolicy
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import java.io.File
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Un archivio delle emissioni in memoria; [gate], se c'e', sospende la lettura degli esiti. */
private class FakeLearningDao : LearningDao {
  val issues = mutableListOf<NowcastIssueEntity>()
  val outcomes = mutableListOf<NowcastOutcomeEntity>()
  @Volatile var gate: CompletableDeferred<Unit>? = null
  val reading = CompletableDeferred<Unit>()

  override suspend fun insertIssue(issue: NowcastIssueEntity) { issues += issue }
  override suspend fun insertOutcome(outcome: NowcastOutcomeEntity) { outcomes += outcome }
  override suspend fun issuesSince(sinceMillis: Long) = issues.filter { it.issuedAtMillis >= sinceMillis }
  override suspend fun outcomesSince(sinceMillis: Long): List<NowcastOutcomeEntity> {
    gate?.let {
      reading.complete(Unit)
      it.await()
    }
    return outcomes.filter { it.issuedAtMillis >= sinceMillis }
  }
  override suspend fun outcomeCount() = outcomes.size
  override suspend fun pruneIssues(beforeMillis: Long) { issues.removeAll { it.issuedAtMillis < beforeMillis } }
  override suspend fun pruneOutcomes(beforeMillis: Long) { outcomes.removeAll { it.issuedAtMillis < beforeMillis } }
}

class PlattRefitterTest {

  private val day = 86_400_000L
  private val origin = 1_750_000_000_000L - 1_750_000_000_000L % day
  private val now = origin + 100 * day

  private lateinit var file: File
  private lateinit var scope: CoroutineScope
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: LearningStore
  private lateinit var dao: FakeLearningDao
  private lateinit var repository: LearningRepository
  private val wipeGuard = DataWipeGuard()
  private var refits = 0

  @Before
  fun setUp() {
    file = File.createTempFile("refitter-test", ".preferences_pb").also { it.delete() }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    store = LearningStore(dataStore)
    dao = FakeLearningDao()
    repository = LearningRepository(dao)
  }

  @After
  fun tearDown() {
    scope.cancel()
    file.delete()
  }

  private fun refitter() = PlattRefitter(
    learningRepository = repository,
    learningStore = store,
    onRefit = { refits++ },
    wipeGuard = wipeGuard,
    clock = { now },
    computeContext = Dispatchers.Unconfined,
  )

  /** Sessanta giorni di giri FRESH in cui il grezzo sovrastima di un logit e mezzo. */
  private fun fillArchive() = runBlocking {
    val random = Random(9)
    for (d in 0 until 60) {
      for (k in 0 until 10) {
        val at = now - (60 - d) * day + k * 600_000L + 1L
        val p = 0.05 + 0.9 * random.nextDouble()
        val rained = random.nextDouble() < 1.0 / (1.0 + exp(-(ln(p / (1 - p)) - 1.5)))
        repository.recordIssue(
          NowcastIssueRecord(
            at, List(ModelVersions.CURRENT_FEATURE_COUNT) { 0.0 }, p, p, p, ModelVersions.TAG, ContextTier.FRESH.name, roundId = at,
          ),
        )
        for (window in listOf("0-1h", "1-3h", "3-6h")) repository.recordOutcome(NowcastOutcomeRecord(at, window, rained))
      }
    }
  }

  private fun oldRecord() = PlattMapRecord("fresh", "0-1h", 1.3, -0.2, 500, 100, 400, "ACTIVE", true, fittedAtMillis = 1L)

  @Test
  fun `all'avvio le mappe di un'altra versione si azzerano e chi ha la cache lo sa`() = runBlocking {
    store.replaceAll("modello-vecchio", listOf(oldRecord()), 1L)

    assertTrue(refitter().ensureVersion())

    assertEquals(PlattRefitPolicy.VERSION, store.snapshot().version)
    assertTrue(store.snapshot().records.isEmpty())
    assertEquals(1, refits)
    // Alla seconda non c'e' piu' niente da azzerare.
    assertFalse(refitter().ensureVersion())
    assertEquals(1, refits)
  }

  @Test
  fun `la ristima salva nove mappe, sostituisce le vecchie e non si ripete prima di sei ore`() = runBlocking {
    fillArchive()
    store.replaceAll(PlattRefitPolicy.VERSION, listOf(oldRecord().copy(variant = "stale")), 1L)
    val refitter = refitter()

    val first = refitter.runIfDue(now)

    assertNotNull(first)
    val maps = store.snapshot()
    assertEquals(PlattRefitPolicy.VERSION, maps.version)
    assertEquals(now, maps.lastFitMillis)
    assertEquals(9, maps.records.size)
    // La mappa "stale" vecchia e' stata sostituita da una voce senza mappa: il suo archivio e' vuoto.
    assertNull(maps.records.first { it.variant == "stale" }.a)
    val fresh = maps.records.filter { it.variant == "fresh" }
    assertTrue(fresh.all { it.active && it.a != null })
    assertEquals(1, refits)

    assertNull(refitter.runIfDue(now + 3_600_000L))
    assertNotNull(refitter.runIfDue(now + PlattRefitPolicy.REFIT_INTERVAL_MILLIS))
  }

  @Test
  fun `una cancellazione durante il calcolo impedisce di riscrivere le mappe`() = runBlocking {
    fillArchive()
    dao.gate = CompletableDeferred()
    val refitter = refitter()

    val running = async(Dispatchers.Default) { refitter.runIfDue(now) }
    dao.reading.await()
    wipeGuard.wipe { store.clear() }
    dao.gate!!.complete(Unit)

    assertNull(running.await())
    val maps = store.snapshot()
    assertTrue(maps.records.isEmpty())
    assertEquals(0L, maps.lastFitMillis)
  }
}
