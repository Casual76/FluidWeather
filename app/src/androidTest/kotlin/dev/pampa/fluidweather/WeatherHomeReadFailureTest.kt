package dev.pampa.fluidweather

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.data.*
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.weather.*
import dev.pampa.fluidweather.core.weather.radar.RadarSample
import dev.pampa.fluidweather.feature.home.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real rememberHomeState, real NowcastUseCase/cleaner; all mutable acquisition is isolated. */
class WeatherHomeReadFailureTest {
  @get:Rule val compose = createComposeRule()

  @Test(timeout = 90_000)
  fun actualHomeRetainsOnFailure_finishesWeatherWork_recovers_clearsConfirmedEmpty_andCancels() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as FluidWeatherApp
    val graph = app.graph
    val directory = File(app.cacheDir, "SYNTHETIC-home-read-${UUID.randomUUID()}").apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val noGps = object : ContextWrapper(app) {
      override fun getSystemService(name: String): Any? =
        if (name == Context.LOCATION_SERVICE) null else super.getSystemService(name)
    }
    val observed = AtomicReference<HomeUiState>()
    val handle = AtomicReference<HomeStateHandle>()
    var active by mutableStateOf(true)
    val names = AtomicInteger()
    val airRequests = AtomicInteger()
    val now = System.currentTimeMillis()
    val dao = PressureFixture(now)
    val history = NowcastHistoryStore(object : NowcastHistoryDao {
      override suspend fun insert(record: NowcastVerdictEntity) = error("Short history must not infer a verdict")
      override suspend fun since(sinceMillis: Long) = emptyList<NowcastVerdictEntity>()
      override suspend fun latestMillis(): Long? = null
      override suspend fun pruneOlderThan(beforeMillis: Long) = Unit
    })
    try {
      val baseline = BarometerBaselineStore(PreferenceDataStoreFactory.create(scope = scope,
        produceFile = { File(directory, "baseline.preferences_pb") }))
      val repository = PressureRepository(dao)
      val nowcast = NowcastUseCase(repository, graph.cleaningPipeline, graph.calibrationStore,
        graph.learningRepository, graph.learningStore, history, baseline, sensorAvailable = { true })
      val snapshots = WeatherSnapshotStore(File(directory, "snapshots"))
      runBlocking { snapshots.write(WeatherSnapshot(WeatherSnapshot.GPS_KEY, 44.0, 11.0, now,
        emptyList(), FusedForecast(listOf(FusedHour(now,
          mapOf(FusionVariables.TEMPERATURE to FusedValue(23.0, emptyList())), WeatherKind.CLOUDY)), emptyMap()), null)) }
      val refresher = WeatherSnapshotRefresher(object : RoundSource {
        override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound =
          error("Cached no-fix Home must not fetch weather")
      }, snapshots)
      val http = ProviderHttp(object : EngineHttp() {
        override suspend fun readText(url: String, headers: Map<String, String>): String {
          airRequests.incrementAndGet()
          throw IOException("SYNTHETIC offline auxiliary data")
        }
      }, UrlCache(File(directory, "http")))
      val briefs = WeatherBriefService(scope, { _, _ -> RadarSample.Unavailable("SYNTHETIC offline") })
      val deps = HomeDependencies(refresher, snapshots, graph.samplingSettingsStore,
        LocationProvider(noGps), repository, history, graph.calibrationStore,
        graph.calibrationController, graph.learningRepository, graph.learningStore, nowcast,
        AirQualityClient(http), graph.appearanceSettingsStore, graph.homeLayoutStore,
        graph.savedLocationsRepository, graph.selectedPlaceStore, GeocodingClient(http), {},
        true, briefs, graph.weatherSummaryService, { 0L }, graph.routineWindowStore,
        resolveLocationName = { _, _, _ -> names.incrementAndGet(); "SYNTHETIC local home" })
      val place = Place(Place.GPS_ID, "SYNTHETIC local home", null, 44.0, 11.0, "Europe/Rome")
      compose.setContent {
        CompositionLocalProvider(LocalContext provides noGps) {
          val home = rememberHomeState(deps, place, active)
          val state = home.state.value
          SideEffect { handle.set(home); observed.set(state) }
        }
      }
      fun await(predicate: (HomeUiState) -> Boolean) {
        compose.waitUntil(15_000) { observed.get()?.let(predicate) == true }
      }
      fun refresh() = instrumentation.runOnMainSync { handle.get().refresh() }
      await { !it.loading && !it.refreshing && (it.readiness?.historyHours ?: 0.0) > 0.4 && names.get() > 0 }
      val retained = observed.get()
      val beforeFailure = names.get()
      val airBeforeFailure = airRequests.get()
      dao.failure = IOException("SYNTHETIC repository unavailable")
      refresh()
      await { names.get() > beforeFailure && !it.loading && !it.refreshing }
      assertTrue("Remaining weather work must continue after local failure", airRequests.get() > airBeforeFailure)
      assertEquals(retained.cleaning, observed.get().cleaning)
      assertEquals(retained.latestRawPressureHpa, observed.get().latestRawPressureHpa)
      assertEquals(retained.readiness?.historyHours, observed.get().readiness?.historyHours)
      assertEquals(now, observed.get().dataAtMillis)

      dao.failure = null
      dao.replace(now, 1002.0)
      val beforeRecovery = names.get()
      refresh()
      await { names.get() > beforeRecovery && !it.loading && !it.refreshing && it.latestRawPressureHpa == 1002.0 }
      assertTrue(observed.get().cleaning!!.historyHours > 0.4)

      // Cancel while the real ordinary evaluate call is suspended in acquisition.
      val entered = CompletableDeferred<Unit>()
      dao.hold = entered to CompletableDeferred()
      val beforeCancel = names.get()
      refresh()
      compose.waitUntil(15_000) { entered.isCompleted }
      instrumentation.runOnMainSync { active = false }
      compose.waitUntil(15_000) { dao.cancellations.get() > 0 }
      assertEquals("Cancelled load must not continue name/weather work", beforeCancel, names.get())
      dao.hold = null
      instrumentation.runOnMainSync { active = true }
      await { names.get() > beforeCancel && !it.loading && !it.refreshing }

      dao.clear()
      val beforeEmpty = names.get()
      refresh()
      await { names.get() > beforeEmpty && !it.loading && !it.refreshing && it.latestRawPressureHpa == null &&
        it.cleaning?.filtered?.isEmpty() == true && it.readiness?.historyHours == 0.0 }
      assertEquals(now, observed.get().dataAtMillis)
    } finally {
      instrumentation.runOnMainSync { active = false }
      compose.waitForIdle()
      runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
      check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
      check(directory.deleteRecursively())
    }
  }

  private class PressureFixture(now: Long) : PressureDao {
    private val updates = MutableStateFlow(samples(now, 1000.0))
    @Volatile var failure: Exception? = null
    @Volatile var hold: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    val cancellations = AtomicInteger()
    fun replace(now: Long, pressure: Double) { updates.value = samples(now, pressure) }
    fun clear() { updates.value = emptyList() }
    override suspend fun samplesSince(sinceMillis: Long): List<PressureSampleEntity> {
      failure?.let { throw it }
      hold?.let { (entered, release) ->
        entered.complete(Unit)
        try { release.await() } catch (cancelled: CancellationException) {
          cancellations.incrementAndGet(); throw cancelled
        }
      }
      return updates.value.filter { it.timestampMillis >= sinceMillis }
    }
    override fun latest(limit: Int): Flow<List<PressureSampleEntity>> = updates.map { it.takeLast(limit) }
    override fun count(): Flow<Long> = updates.map { it.size.toLong() }
    override suspend fun oldestTimestamp() = updates.value.minOfOrNull { it.timestampMillis }
    override suspend fun newestTimestamp() = updates.value.maxOfOrNull { it.timestampMillis }
    override suspend fun averageHourlyPressureSince(sinceMillis: Long): Double? = null
    override suspend fun samplesOfBurst(burstId: String) = emptyList<PressureSampleEntity>()
    override suspend fun insertAll(samples: List<PressureSampleEntity>): Unit = error("Read-only fixture")
    override suspend fun deleteOlderThan(beforeMillis: Long): Unit = error("Read-only fixture")
    companion object {
      fun samples(now: Long, pressure: Double) = (0..30).map { minute -> PressureSampleEntity(
        timestampMillis = now - (30 - minute) * 60_000L, pressureHpa = pressure,
        source = "PERIODIC", burstId = null, altitudeMeters = 0.0, latitude = 44.0,
        longitude = 11.0, activity = "UNKNOWN", activityConfidence = null) }
    }
  }
}
