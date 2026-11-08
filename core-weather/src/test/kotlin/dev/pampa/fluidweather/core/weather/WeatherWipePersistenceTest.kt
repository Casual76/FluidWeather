package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherWipePersistenceTest {
  private val now = 1_788_220_800_000L
  private fun snapshot(temperature: Double = 12.0) = WeatherSnapshot(
    "gps", 43.8321, 11.1994, now, emptyList(), FusedForecast(emptyList(), emptyMap()),
    ForecastBundle("open-meteo", now - 60_000L, 43.8321, 11.1994,
      listOf(HourlyPoint(now + 3_600_000L, temperatureC = temperature))),
  )
  private fun round() = WeatherRound(listOf(ProviderFetch(
    ProviderRegistry.all.first { it.id == ProviderRegistry.OPEN_METEO }, snapshot().context, null,
  )), snapshot().fused)
  private fun evaluation(): LocalEvaluation {
    val verdict = NowcastVerdict(emptyList(), AlertLevel.QUIETE)
    return LocalEvaluation(verdict, verdict, doubleArrayOf(1.0), null)
  }

  @Test fun `cancelled queued write preserves last valid disk memory and revision`() = runTest {
    val dir = Files.createTempDirectory("snapshot-cancel").toFile()
    try {
      val store = WeatherSnapshotStore(dir, ioContext = StandardTestDispatcher(testScheduler))
      store.write(snapshot())
      val revision = store.changes.value
      val work = launch(start = CoroutineStart.UNDISPATCHED) { store.write(snapshot(99.0)) }
      work.cancelAndJoin()
      assertEquals(snapshot(), store.read("gps"))
      assertEquals(snapshot(), WeatherSnapshotStore(dir).read("gps"))
      assertEquals(revision, store.changes.value)
    } finally { dir.deleteRecursively() }
  }

  @Test fun `same fetch content emits revisions and clear preserves monotonicity`() = runTest {
    val dir = Files.createTempDirectory("snapshot-content").toFile()
    try {
      val store = WeatherSnapshotStore(dir)
      store.write(snapshot())
      val first = store.changes.value.getValue("gps")
      store.write(snapshot(23.0))
      assertTrue(store.changes.value.getValue("gps") > first)
      assertEquals(mapOf("gps" to now), store.updates.value)
      assertEquals(snapshot(23.0), WeatherSnapshotStore(dir).read("gps"))
      val second = store.changes.value.getValue("gps")
      store.clear()
      assertTrue(store.changes.value.isEmpty())
      assertTrue(store.updates.value.isEmpty())
      store.write(snapshot())
      assertTrue(store.changes.value.getValue("gps") > second)
    } finally { dir.deleteRecursively() }
  }

  @Test fun `disk read queued before clear cannot restore memory or return prior content`() = runTest {
    val dir = Files.createTempDirectory("snapshot-read-clear").toFile()
    try {
      WeatherSnapshotStore(dir).write(snapshot())
      val store = WeatherSnapshotStore(dir, ioContext = StandardTestDispatcher(testScheduler))
      val read = async(start = CoroutineStart.UNDISPATCHED) { store.read("gps") }
      val clear = launch(start = CoroutineStart.UNDISPATCHED) { store.clear() }
      clear.join()
      assertNull(read.await())
      assertNull(store.read("gps"))
      assertNull(WeatherSnapshotStore(dir).read("gps"))
    } finally { dir.deleteRecursively() }
  }

  @Test fun `legacy v1 without optional metadata remains readable without refreshing clocks`() = runTest {
    val dir = Files.createTempDirectory("snapshot-legacy").toFile()
    try {
      File(dir, "gps.json").writeText("""{"version":1,"placeKey":"gps","latitude":43.8321,"longitude":11.1994,"fetchedAtMillis":1234,"hours":[],"weights":{},"fetches":[]}""")
      val saved = WeatherSnapshotStore(dir).read("gps")!!
      assertEquals(1234L, saved.fetchedAtMillis)
      assertNull(saved.context)
      assertNull(saved.predictionsRegisteredAtMillis)
      assertNull(saved.zoneId)
    } finally { dir.deleteRecursively() }
  }

  @Test fun `noncooperative late refresh is rejected on disk memory callback and recreation then fresh succeeds`() = runTest {
    val dir = Files.createTempDirectory("snapshot-late").toFile()
    try {
      val guard = DataWipeGuard()
      val store = WeatherSnapshotStore(dir, guard)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val callbacks = mutableListOf<RegisteredRound>()
      val registrations = InMemoryRainEventStore()
      val registrar = LocalRoundRegistrar(
        LocalEvaluator { _, _ -> evaluation() }, registrations, {}, { _, _ -> null }, { false },
        { now }, StandardTestDispatcher(testScheduler), guard,
      )
      var calls = 0
      val source = object : RoundSource {
        override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
          calls++
          entered.complete(Unit)
          withContext(NonCancellable) { release.await() }
          return round()
        }
      }
      val refresher = WeatherSnapshotRefresher(source, store, {
        callbacks += it
        registrar.register(it)
      }, { now }, guard)
      val late = async { refresher.refresh("gps", 43.8321, 11.1994) }
      entered.await()
      val queued = async { refresher.refresh("gps", 43.8321, 11.1994) }
      runCurrent()
      guard.wipe { store.clear() }
      release.complete(Unit)
      try { late.await(); fail("old refresh returned") } catch (_: CancellationException) { }
      try { queued.await(); fail("queued old refresh returned") } catch (_: CancellationException) { }
      assertEquals(1, calls)
      assertTrue(callbacks.isEmpty())
      assertNull(store.read("gps"))
      assertNull(WeatherSnapshotStore(dir, guard).read("gps"))
      val fresh = refresher.refresh("gps", 43.8321, 11.1994)
      assertEquals(now, fresh.fetchedAtMillis)
      assertEquals(now - 60_000L, fresh.context!!.fetchedAtMillis)
      assertEquals(guard.generation(), callbacks.single().originatingWipeGeneration)
      assertTrue(registrations.pending.isNotEmpty())
      assertEquals(fresh, WeatherSnapshotStore(dir, guard).read("gps"))
    } finally { dir.deleteRecursively() }
  }

  @Test fun `callback begun after wipe retains round epoch and cannot register`() = runTest {
    val guard = DataWipeGuard()
    val old = RegisteredRound(now, snapshot(), emptyList(), guard.generation())
    guard.wipe { }
    val rows = InMemoryRainEventStore()
    var evaluated = false
    var archived = false
    val registrar = LocalRoundRegistrar(
      LocalEvaluator { _, _ -> evaluated = true; LocalEvaluation(null, null, null, null) }, rows,
      { archived = true }, { _, _ -> null }, { true }, { now },
      StandardTestDispatcher(testScheduler), guard,
    )
    assertNull(registrar.register(old))
    assertFalse(evaluated)
    assertFalse(archived)
    assertTrue(rows.pending.isEmpty())
    assertNotNull(registrar.register(old.copy(originatingWipeGeneration = guard.generation())))
    assertTrue(evaluated)
    assertTrue(rows.pending.isNotEmpty())
  }

  @Test fun `wipe during registrar evaluation rejects late side effects then permits fresh archive`() = runTest {
    val guard = DataWipeGuard()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val rows = InMemoryRainEventStore()
    var archived = false
    val registrar = LocalRoundRegistrar(
      LocalEvaluator { _, _ ->
        entered.complete(Unit)
        withContext(NonCancellable) { release.await() }
        evaluation()
      }, rows, { archived = true }, { _, _ -> null }, { true }, { now },
      StandardTestDispatcher(testScheduler), guard,
    )
    val pending = async { registrar.register(RegisteredRound(now, snapshot(), emptyList(), 0L)) }
    entered.await()
    guard.wipe { rows.pending.clear() }
    release.complete(Unit)
    assertNull(pending.await())
    assertTrue(rows.pending.isEmpty())
    assertFalse(archived)
    assertNotNull(registrar.register(RegisteredRound(now, snapshot(), emptyList(), guard.generation())))
    assertTrue(archived)
    assertTrue(rows.pending.isNotEmpty())
  }

  @Test fun `registrar propagates evaluator cancellation without side effects`() = runTest {
    val rows = InMemoryRainEventStore()
    var archived = false
    val registrar = LocalRoundRegistrar(
      LocalEvaluator { _, _ -> throw CancellationException("fixture cancellation") }, rows,
      { archived = true }, { _, _ -> null }, { true }, { now }, StandardTestDispatcher(testScheduler),
    )
    try { registrar.register(RegisteredRound(now, snapshot(), emptyList())); fail("cancellation swallowed") }
    catch (_: CancellationException) { }
    assertTrue(rows.pending.isEmpty())
    assertFalse(archived)
  }

  @Test fun `nested verifier entering after wipe never renews originating epoch`() = runTest {
    val guard = DataWipeGuard()
    val epoch = WeatherWipeEpoch(guard, guard.generation())
    guard.wipe { }
    val store = InMemoryVerificationStore()
    val verifier = ForecastVerifier(store, clock = { now }, wipeGuard = guard)
    withContext(epoch) {
      try { verifier.registerPending(round().fetches); fail("stale registration") } catch (_: CancellationException) { }
      try { verifier.settle(round().fetches); fail("stale settlement") } catch (_: CancellationException) { }
    }
    assertTrue(store.pending.isEmpty())
    assertTrue(store.verifications.isEmpty())
    verifier.registerPending(round().fetches)
    assertTrue(store.pending.isNotEmpty())
  }

  @Test fun `verifier read during wipe cannot settle old predictions into new store`() = runTest {
    val guard = DataWipeGuard()
    val backing = InMemoryVerificationStore()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val old = PendingPrediction("open-meteo", "temperature", now, 3.0, now - 3_600_000L, 43.8321, 11.1994)
    var writes = 0
    val store = object : VerificationStore by backing {
      override suspend fun duePending(nowMillis: Long): List<PendingPrediction> {
        entered.complete(Unit)
        withContext(NonCancellable) { release.await() }
        return listOf(old)
      }
      override suspend fun addVerifications(verifications: List<ForecastVerification>) { writes++ }
      override suspend fun removePending(predictions: List<PendingPrediction>) { writes++ }
    }
    val work = async { ForecastVerifier(store, 1, { now }, guard).settle(round().fetches) }
    entered.await()
    guard.wipe { }
    release.complete(Unit)
    try { work.await(); fail("stale settlement") } catch (_: CancellationException) { }
    assertEquals(0, writes)
  }

  @Test fun `HTTP late noncooperative body cannot recreate provider cache after wipe`() = runTest {
    val dir = Files.createTempDirectory("provider-late").toFile()
    try {
      val guard = DataWipeGuard()
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val transport = object : EngineHttp() {
        override suspend fun readText(url: String, headers: Map<String, String>): String {
          entered.complete(Unit)
          return withContext(NonCancellable) { release.await(); "original-body" }
        }
      }
      val http = ProviderHttp(transport, UrlCache(dir) { now }, { now }, guard)
      val request = async { http.readTextTimed("https://fixture.invalid/weather", 60_000L) }
      entered.await()
      guard.wipe { dir.deleteRecursively() }
      release.complete(Unit)
      try { request.await(); fail("stale body") } catch (_: CancellationException) { }
      assertNull(UrlCache(dir).readTimed("https://fixture.invalid/weather", Long.MAX_VALUE))
      assertEquals("original-body", http.readText("https://fixture.invalid/weather", 60_000L))
      assertEquals(now, UrlCache(dir) { now }.readTimed("https://fixture.invalid/weather", 60_000L)!!.writtenAtMillis)
    } finally { dir.deleteRecursively() }
  }
}
