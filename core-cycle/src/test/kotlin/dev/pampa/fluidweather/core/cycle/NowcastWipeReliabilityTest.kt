package dev.pampa.fluidweather.core.cycle

import android.app.Application
import androidx.room.Room
import dev.pampa.fluidweather.core.data.*
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.*
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real GPS evaluation and stores, paused after local acquisition but before final side effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NowcastWipeReliabilityTest {
  private lateinit var database: FluidWeatherDatabase
  private lateinit var baseline: BarometerBaselineStore
  private lateinit var history: NowcastHistoryStore
  private lateinit var pressure: PressureRepository
  private lateinit var useCase: NowcastUseCase
  private val guard = DataWipeGuard()
  private val entered = CompletableDeferred<Unit>()
  private val release = CompletableDeferred<Unit>()
  private var pause = false
  private var sensor = false
  private val now = 1_781_517_600_000L
  private val snapshot = WeatherSnapshot("gps", 43.8, 11.2, now, emptyList(),
    FusedForecast(emptyList(), emptyMap()), null)

  @Before fun prepare() {
    val app = RuntimeEnvironment.getApplication()
    database = Room.inMemoryDatabaseBuilder(app, FluidWeatherDatabase::class.java).allowMainThreadQueries().build()
    baseline = BarometerBaselineStore(app)
    history = NowcastHistoryStore(database.nowcastHistoryDao())
    pressure = PressureRepository(database.pressureDao())
    useCase = NowcastUseCase(pressure, CleaningPipeline(), CalibrationStore(app),
      LearningRepository(database.learningDao()), LearningStore(app), history, baseline,
      sensorAvailable = { sensor }, localClimate = { _, _ ->
        if (pause) { entered.complete(Unit); release.await() }
        null
      }, wipeGuard = guard)
  }
  @After fun close() { database.close() }

  @Test fun liveGpsEvaluationCanRememberPlace() = runBlocking {
    useCase.evaluate(snapshot, now)
    assertEquals(43.8, baseline.current().latitude!!, 0.0)
    assertEquals(11.2, baseline.current().longitude!!, 0.0)
  }

  private suspend fun CoroutineScope.wipePausedEvaluation() {
    pause = true
    val result = async(Dispatchers.Default) { runCatching { useCase.evaluate(snapshot, now, record = true) } }
    withTimeout(5_000L) { entered.await() }
    guard.wipe { baseline.clear(); history.clear(); pressure.clear() }
    useCase.invalidateLearning()
    release.complete(Unit)
    val failure = withTimeout(5_000L) { result.await() }.exceptionOrNull()
    assertTrue("old evaluation must stop after deletion", failure is CancellationException)
    assertNull(baseline.current().latitude)
    assertNull(baseline.current().longitude)
    assertTrue(history.since(0L).isEmpty())
  }

  @Test fun pausedGpsCannotRestoreBaseline() = runBlocking { wipePausedEvaluation() }

  @Test fun pausedMeasuredVerdictCannotRestoreHistory() = runBlocking {
    sensor = true
    pressure.record((0..56).map { quarter -> PressureSample(now - 14 * 3_600_000L + quarter * 900_000L,
      1013.0, SampleSource.PERIODIC, altitudeMeters = 50.0, latitude = 43.8, longitude = 11.2) })
    val control = useCase.evaluate(snapshot, now, record = true)
    assertNotNull("control must reach the operational verdict path", control.explanation)
    assertTrue("control must really record a verdict", history.since(0L).isNotEmpty())
    wipePausedEvaluation()
  }

  @Test fun gpsStartedDuringWipeCannotRestoreBaseline() = runBlocking {
    useCase.evaluate(snapshot, now)
    assertEquals(43.8, baseline.current().latitude!!, 0.0)
    val started = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    val wipe = launch(Dispatchers.Default) {
      guard.wipe { started.complete(Unit); finish.await(); baseline.clear(); history.clear(); pressure.clear() }
    }
    withTimeout(5_000L) { started.await() }
    pause = true
    val evaluation = async(Dispatchers.Default) { runCatching { useCase.evaluate(snapshot, now, record = true) } }
    withTimeout(5_000L) { entered.await() }
    finish.complete(Unit)
    withTimeout(5_000L) { wipe.join() }
    release.complete(Unit)
    assertTrue(withTimeout(5_000L) { evaluation.await() }.exceptionOrNull() is CancellationException)
    assertNull(baseline.current().latitude)
    assertNull(baseline.current().longitude)
    assertTrue(history.since(0L).isEmpty())
  }
}
