package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.tide.TidalSummary
import dev.pampa.fluidweather.nowcast.tide.TideSource
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLocalHydrationTest {
  private val now = 1_794_052_800_000L
  private val ready = BarometerReadiness(false, 0, 600, true, 14.0, 13.0)
  private val record = NowcastVerdictRecord(now - 600_000, 0.1, 0.2, 0.3, "QUIETE")
  private val clean = CleaningResult(emptyList(), emptyList(), emptyList(), emptyList(),
    TidalSummary(TideSource.values().first(), 0.0, 0.0, 0.0))
  private fun snapshot(key: String = "gps", lat: Double = 44.0) = WeatherSnapshot(
    key, lat, 11.0, now - 600_000, emptyList(), FusedForecast(emptyList(), emptyMap()), null)

  @Test fun `cached local data hydrates while GPS and weather are still suspended`() = runTest {
    val gps = backgroundScope.launch { awaitCancellation() }
    val network = backgroundScope.launch { awaitCancellation() }
    var state = HomeUiState(hasLocation = false, dataAtMillis = now - 10_000)
    val local = HomeLocalHydration({ 0 }, { clean }, { 1001.5 }, { listOf(record) }, { ready })
    local.refresh(now, 0) { state = it.applyTo(state) }
    assertSame(clean, state.cleaning)
    assertEquals(listOf(record), state.verdictHistory)
    assertEquals(1001.5, state.latestRawPressureHpa!!, 0.0)
    assertSame(ready, state.readiness)
    assertFalse(state.hasLocation)
    assertEquals(now - 10_000, state.dataAtMillis)
    assertTrue(gps.isActive && network.isActive)
  }

  @Test fun `failed local attempt preserves archive and next sampling signal recovers without weather refresh`() = runTest {
    var failed = true
    val signals = MutableSharedFlow<Unit>()
    val previousClean = clean.copy(reductionAltitudeMeters = 123.0)
    var state = HomeUiState(cleaning = previousClean, latestRawPressureHpa = 999.0, verdictHistory = listOf(record))
    val local = HomeLocalHydration({ 0 }, { if (failed) throw IOException() else clean },
      { if (failed) throw IOException() else 1002.0 },
      { if (failed) throw IOException() else listOf(record.copy(timestampMillis = now)) }, { ready })
    val job = backgroundScope.launch {
      local.consume(signals, 0, { now }, StandardTestDispatcher(testScheduler)) { state = it.applyTo(state) }
    }
    runCurrent()
    assertSame(previousClean, state.cleaning)
    assertEquals(999.0, state.latestRawPressureHpa!!, 0.0)
    assertEquals(listOf(record), state.verdictHistory)
    failed = false
    signals.emit(Unit); runCurrent()
    assertSame(clean, state.cleaning)
    assertEquals(1002.0, state.latestRawPressureHpa!!, 0.0)
    assertEquals(now, state.verdictHistory.single().timestampMillis)
    job.cancelAndJoin()
  }

  @Test fun `timer refreshes verdict history and readiness without a sample or manual refresh`() = runTest {
    var reads = 0
    val values = mutableListOf<HomeLocalReading>()
    val local = HomeLocalHydration({ 0 }, { null }, { null },
      { reads++; listOf(record.copy(timestampMillis = reads.toLong())) }, { ready.copy(historyHours = reads.toDouble()) })
    val job = backgroundScope.launch {
      local.consume(MutableSharedFlow(), 0, { now }, StandardTestDispatcher(testScheduler), values::add)
    }
    runCurrent()
    advanceTimeBy(60_001); runCurrent()
    assertEquals(2, values.size)
    assertEquals(2L, values.last().history.getOrThrow().single().timestampMillis)
    assertEquals(2.0, values.last().readiness.getOrThrow().historyHours, 0.0)
    job.cancelAndJoin()
  }

  @Test fun `wipe during local disk read cannot republish old data and current epoch recovers`() = runTest {
    var generation = 0L
    val gate = CompletableDeferred<Unit>()
    val values = mutableListOf<HomeLocalReading>()
    val local = HomeLocalHydration({ generation }, { gate.await(); clean }, { 1000.0 }, { listOf(record) }, { ready })
    val job = launch { local.refresh(now, 0, values::add) }
    runCurrent(); generation++; gate.complete(Unit); job.join()
    assertTrue(values.isEmpty())
    local.refresh(now, 1, values::add)
    assertEquals(1, values.size)
  }

  @Test fun `cancellation swallowed by a reader still cannot emit`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val values = mutableListOf<HomeLocalReading>()
    val local = HomeLocalHydration({ 0 }, { try { gate.await() } catch (_: CancellationException) { }; clean },
      { 1000.0 }, { listOf(record) }, { ready })
    val job = launch { local.refresh(now, 0, values::add) }
    runCurrent(); job.cancelAndJoin()
    assertTrue(values.isEmpty())
  }

  @Test fun `snapshot read and zone backfill are both guarded against wipe`() = runTest {
    for (wipeAtRead in listOf(true, false)) {
      var generation = 0L
      var backfilled = false
      val result = homeReadSnapshot({ generation }, 0,
        read = { if (wipeAtRead) generation++; snapshot() }, accepts = { true },
        backfill = { backfilled = true; generation++ })
      assertNull(result)
      assertEquals(!wipeAtRead, backfilled)
    }
    for (cancelAtRead in listOf(true, false)) {
      val gate = CompletableDeferred<Unit>()
      var published = false
      suspend fun cancelledRead() { try { gate.await() } catch (_: CancellationException) { } }
      val job = launch {
        val result = homeReadSnapshot({ 0 }, 0,
          read = { if (cancelAtRead) cancelledRead(); snapshot() }, accepts = { true },
          backfill = { if (!cancelAtRead) cancelledRead() })
        if (result != null) published = true
      }
      runCurrent(); job.cancelAndJoin()
      assertFalse(published)
    }
    val current = homeReadSnapshot({ 2 }, 2, { snapshot() }, { true }, {})
    assertEquals(now - 600_000, current!!.fetchedAtMillis)
  }

  @Test fun `late GPS snapshot recovers initial no fix but saved city never becomes GPS weather`() {
    val gps = Place.gps()
    assertEquals(GeoPoint(44.0, 11.0), homeSnapshotRecoveryPoint(gps, null, snapshot(), false))
    assertNull(homeSnapshotRecoveryPoint(gps, null, snapshot("place-7"), false))
    assertNull(homeSnapshotRecoveryPoint(gps, null, snapshot(lat = Double.NaN), false))
    val a = GeoPoint(44.0, 11.0)
    assertNull(homeSnapshotRecoveryPoint(gps, a, snapshot(lat = 48.0), false))
    assertEquals(GeoPoint(48.0, 11.0), homeSnapshotRecoveryPoint(gps, null, snapshot(lat = 48.0), true))
  }

  @Test fun `local scope excludes saved locations and unsupported sensor even without GPS fix`() {
    assertTrue(homeLocalApplies(Place.gps(), true))
    assertFalse(homeLocalApplies(Place.gps(), false))
    assertFalse(homeLocalApplies(Place(7, "B", null, 48.0, 11.0), true))
    assertNull(homeSnapshotRecoveryPoint(Place(7, "B", null, 48.0, 11.0), null, snapshot("place-7"), false))
  }

  @Test fun `no sensor history produces no invented pressure verdict or weather`() = runTest {
    var state = HomeUiState(hasLocation = false)
    val local = HomeLocalHydration({ 0 }, { null }, { null }, { emptyList() }, { ready.copy(historyHours = 0.0) })
    local.refresh(now, 0) { state = it.applyTo(state) }
    assertNull(state.cleaning)
    assertNull(state.latestRawPressureHpa)
    assertNull(state.verdict)
    assertNull(state.dataAtMillis)
    assertTrue(state.verdictHistory.isEmpty())
    assertEquals(0.0, state.readiness!!.historyHours, 0.0)
  }

  @Test fun `successful empty archive clears expired local data while retaining weather clocks`() = runTest {
    var state = HomeUiState(cleaning = clean, latestRawPressureHpa = 999.0,
      verdictHistory = listOf(record), dataAtMillis = now - 60_000)
    val local = HomeLocalHydration({ 0 }, { null }, { null }, { emptyList() }, { ready.copy(historyHours = 0.0) })
    local.refresh(now, 0) { state = it.applyTo(state) }
    assertNull(state.cleaning)
    assertNull(state.latestRawPressureHpa)
    assertTrue(state.verdictHistory.isEmpty())
    assertEquals(now - 60_000, state.dataAtMillis)
  }
}
