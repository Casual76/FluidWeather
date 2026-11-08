package dev.pampa.fluidweather

import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.core.data.*
import dev.pampa.fluidweather.core.weather.NowcastUseCase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual production API boundary; only pressure acquisition is synthetic, never persisted. */
class WeatherLocalReadFailureTest {
  private val graph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FluidWeatherApp).graph
  private fun reader(dao: ReadOnlyDao) = NowcastUseCase(
    pressureRepository = PressureRepository(dao), cleaningPipeline = graph.cleaningPipeline,
    calibrationStore = graph.calibrationStore, learningRepository = graph.learningRepository,
    learningStore = graph.learningStore, nowcastHistory = graph.nowcastHistoryStore,
    baselineStore = graph.barometerBaselineStore, sensorAvailable = { true },
  )
  private val now = 1_794_128_400_000L

  @Test(timeout = 30_000) fun productionHomeModePropagatesReadFailureInsteadOfNullOrZeroHistory() = runBlocking {
    withTimeout(10_000) {
      val dao = ReadOnlyDao().apply { failure = IOException("SYNTHETIC local read failure") }
      val useCase = reader(dao)
      // This is the old swallowed-error boundary, not a throwing fake UI reader.
      assertNull(useCase.clean(now - 86_400_000L))
      assertEquals(0.0, useCase.readiness(now, null).historyHours, 0.0)
      assertSame(dao.failure, runCatching { useCase.clean(now - 86_400_000L, failOnLocalReadError = true) }.exceptionOrNull())
      assertSame(dao.failure, runCatching { useCase.readiness(now, null, failOnLocalReadError = true) }.exceptionOrNull())
      assertSame(dao.failure, runCatching { useCase.evaluate(null, now, failOnLocalReadError = true) }.exceptionOrNull())
    }
  }

  @Test(timeout = 30_000) fun successfulEmptyIsDistinctAndRecoveryKeepsTheSameCleaningNumerics() = runBlocking {
    withTimeout(10_000) {
      val dao = ReadOnlyDao()
      val useCase = reader(dao)
      val empty = useCase.clean(now - 86_400_000L, failOnLocalReadError = true)
      assertNotNull(empty)
      assertTrue(empty!!.filtered.isEmpty())
      assertEquals(0.0, useCase.readiness(now, null, failOnLocalReadError = true).historyHours, 0.0)
      dao.rows = (0..840).map { minute -> PressureSampleEntity(
        timestampMillis = now - (840 - minute) * 60_000L,
        pressureHpa = 1000.0 + kotlin.math.sin(minute / 60.0) * 0.1,
        source = "PERIODIC", burstId = null, altitudeMeters = 100.0,
        latitude = 43.8, longitude = 11.2, activity = "UNKNOWN", activityConfidence = null,
      ) }
      val strict = useCase.clean(now - 86_400_000L, failOnLocalReadError = true)
      assertTrue(strict!!.historyHours > 13.0)
      assertEquals(useCase.clean(now - 86_400_000L), strict)
      assertEquals(useCase.readiness(now, null), useCase.readiness(now, null, failOnLocalReadError = true))
      dao.failure = IOException("SYNTHETIC temporary failure")
      assertTrue(runCatching { useCase.clean(now - 86_400_000L, failOnLocalReadError = true) }.isFailure)
      dao.failure = null
      assertEquals(strict, useCase.clean(now - 86_400_000L, failOnLocalReadError = true))
    }
  }

  @Test(timeout = 30_000) fun homeModePreservesCancellationIdentity() = runBlocking {
    withTimeout(10_000) {
      val dao = ReadOnlyDao().apply { failure = CancellationException("SYNTHETIC cancelled read") }
      val useCase = reader(dao)
      assertSame(dao.failure, runCatching { useCase.clean(now, failOnLocalReadError = true) }.exceptionOrNull())
      assertSame(dao.failure, runCatching { useCase.readiness(now, null, failOnLocalReadError = true) }.exceptionOrNull())
    }
  }

  private class ReadOnlyDao : PressureDao {
    var rows = emptyList<PressureSampleEntity>()
    var failure: Exception? = null
    override suspend fun samplesSince(sinceMillis: Long): List<PressureSampleEntity> {
      failure?.let { throw it }; return rows.filter { it.timestampMillis >= sinceMillis }
    }
    override suspend fun oldestTimestamp(): Long? { failure?.let { throw it }; return rows.minOfOrNull { it.timestampMillis } }
    override fun latest(limit: Int): Flow<List<PressureSampleEntity>> = flowOf(rows.takeLast(limit))
    override fun count(): Flow<Long> = flowOf(rows.size.toLong())
    override suspend fun newestTimestamp() = rows.maxOfOrNull { it.timestampMillis }
    override suspend fun averageHourlyPressureSince(sinceMillis: Long): Double? = error("Unexpected acquisition path")
    override suspend fun samplesOfBurst(burstId: String): List<PressureSampleEntity> = error("Unexpected acquisition path")
    override suspend fun insertAll(samples: List<PressureSampleEntity>): Unit = error("Read-only fixture")
    override suspend fun deleteOlderThan(beforeMillis: Long): Unit = error("Read-only fixture")
  }
}
