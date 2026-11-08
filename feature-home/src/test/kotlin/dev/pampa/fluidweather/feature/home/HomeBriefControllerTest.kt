package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.*
import dev.pampa.fluidweather.core.weather.radar.RadarSample
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeBriefControllerTest {
  private val noon = 1_794_052_800_000L
  private val place = Place(1, "A", null, 44.0, 11.0)
  private val point = GeoPoint(44.0, 11.0)
  private val evaluator = LocalPrecipitationEvaluator()
  private fun snapshot(mm: Double = 1.0) = WeatherSnapshot("place-1", 44.0, 11.0, noon,
    emptyList(), FusedForecast(emptyList(), emptyMap()),
    ForecastBundle("open-meteo", noon, 44.0, 11.0, emptyList(),
      current = ProviderCurrent(noon, 10.0, WeatherKind.CLOUDY, mm, 900_000L, noon, true)))
  private fun model(p: Place, g: GeoPoint?, s: WeatherSnapshot?, now: Long = noon) =
    evaluator.evaluate(p, g, s, null, now)

  @Test fun `known local snapshot zone reaches presentation without using another places zone`() = runTest {
    for ((source, expected) in listOf(
      snapshot().copy(zoneId = "America/New_York") to "America/New_York",
      snapshot().copy(zoneId = "America/New_York", placeKey = "place-99") to null,
    )) {
      val values = mutableListOf<WeatherBrief?>()
      val controller = HomeBriefController({ p, g, s -> model(p, g, s) }, { noon }, StandardTestDispatcher(testScheduler))
      val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, source), { source }, values::add) }
      runCurrent()
      assertTrue(values.isNotEmpty())
      assertTrue(values.all { it?.precipitation?.place?.zoneId == expected })
      job.cancelAndJoin()
    }
  }

  @Test fun `cached current works with no fused hours and appears before bounded service`() = runTest {
    val values = mutableListOf<WeatherBrief?>()
    val service = WeatherBriefService(backgroundScope, { _, _ -> delay(10_000); RadarSample.Unavailable("offline") },
      { noon + testScheduler.currentTime }, loadTimeoutMillis = 100)
    val controller = HomeBriefController(service::brief, { noon + testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, snapshot()), { snapshot() }, values::add) }
    runCurrent()
    assertEquals(1, values.size)
    assertEquals(10.0, values.single()!!.current!!.temperatureC!!, 0.0)
    assertEquals(PrecipitationState.WET, values.single()!!.precipitation.state)
    advanceTimeBy(101); runCurrent()
    assertEquals(2, values.size)
    assertTrue(WeatherEvidenceLimit.MODEL_ONLY in values.last()!!.precipitation.limits)
    job.cancel()
  }

  @Test fun `shared radar assessment replaces immediate model without reinterpretation`() = runTest {
    val initial = model(place, point, snapshot())
    val radarEvidence = WeatherEvidence("radar", WeatherEvidenceType.RADAR_ESTIMATE,
      precipitationState = PrecipitationState.DRY)
    val shared = initial.copy(precipitation = initial.precipitation.copy(
      state = PrecipitationState.UNCERTAIN,
      evidence = initial.precipitation.evidence + radarEvidence,
      limits = setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS),
      radarAvailability = RadarAvailability.AVAILABLE), revision = "shared-radar")
    val values = mutableListOf<WeatherBrief?>()
    val controller = HomeBriefController({ _, _, _ -> delay(50); shared },
      { noon }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, snapshot()), { snapshot() }, values::add) }
    runCurrent(); assertEquals(PrecipitationState.WET, values.single()!!.precipitation.state)
    advanceTimeBy(51); runCurrent()
    assertSame(shared, values.last())
    job.cancel()
  }

  @Test fun `city switch cancels slow old request and never emits old context afterwards`() = runTest {
    val values = mutableListOf<WeatherBrief?>()
    val other = place.copy(id = 2, name = "B", latitude = 45.0)
    val inputs = MutableStateFlow(HomeBriefInput(place, point, snapshot()))
    val controller = HomeBriefController({ p, g, s -> delay(if (p.id == 1L) 5_000 else 10); model(p, g, s) },
      { noon }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { inputs.collectLatest { controller.consume(it, { it.snapshot }, values::add) } }
    runCurrent()
    values.clear()
    inputs.value = HomeBriefInput(other, GeoPoint(45.0, 11.0), null)
    runCurrent(); advanceTimeBy(6_000); runCurrent()
    assertTrue(values.isNotEmpty())
    assertTrue(values.all { it!!.precipitation.place == other })
    job.cancel()
  }

  @Test fun `content change at same fetched timestamp cancels old revision`() = runTest {
    val values = mutableListOf<WeatherBrief?>()
    val inputs = MutableStateFlow(HomeBriefInput(place, point, snapshot(1.0)))
    val controller = HomeBriefController({ p, g, s -> delay(100); model(p, g, s) }, { noon }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { inputs.collectLatest { controller.consume(it, { it.snapshot }, values::add) } }
    runCurrent(); val wetRevision = values.last()!!.revision
    inputs.value = inputs.value.copy(snapshot = snapshot(0.0))
    runCurrent(); advanceTimeBy(101); runCurrent()
    assertEquals(PrecipitationState.DRY, values.last()!!.precipitation.state)
    assertNotEquals(wetRevision, values.last()!!.revision)
    job.cancel()
  }

  @Test fun `same timestamp content written during radar work discards its late result`() = runTest {
    var stored = snapshot(1.0)
    val values = mutableListOf<WeatherBrief?>()
    val controller = HomeBriefController({ p, g, s -> delay(100); model(p, g, s) },
      { noon }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, stored), { stored }, values::add) }
    runCurrent(); stored = snapshot(0.0)
    advanceTimeBy(201); runCurrent()
    assertEquals(PrecipitationState.WET, values.first()!!.precipitation.state)
    assertTrue(values.drop(1).all { it!!.precipitation.state == PrecipitationState.DRY })
    job.cancel()
  }

  @Test fun `periodic reread finds same timestamp change despite conflated store event`() = runTest {
    var stored = snapshot(1.0)
    val values = mutableListOf<WeatherBrief?>()
    val controller = HomeBriefController({ p, g, s -> model(p, g, s) }, { noon }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, stored), { stored }, values::add) }
    runCurrent(); stored = snapshot(0.0)
    advanceTimeBy(60_000); runCurrent()
    assertEquals(PrecipitationState.DRY, values.last()!!.precipitation.state)
    job.cancel()
  }

  @Test fun `missing GPS invalid coordinates and wrong key never imply dry`() = runTest {
    assertNull(homePoint(Place.gps().latitude, Place.gps().longitude))
    assertNull(homePoint(Double.POSITIVE_INFINITY, 0.0))
    assertEquals(GeoPoint(0.0, 0.0), homePoint(0.0, 0.0))
    assertFalse(homeSnapshotMatches(place, point, snapshot().copy(placeKey = "gps")))
    assertFalse(homeSnapshotMatches(place, GeoPoint(0.0, 0.0), snapshot()))
    for (input in listOf(HomeBriefInput(Place.gps(), null, snapshot()),
      HomeBriefInput(place, point, snapshot().copy(placeKey = "gps")))) {
      val values = mutableListOf<WeatherBrief?>()
      val controller = HomeBriefController({ p, g, s -> model(p, g, s) }, { noon }, StandardTestDispatcher(testScheduler))
      val job = backgroundScope.launch { controller.consume(input, { input.snapshot }, values::add) }
      runCurrent()
      assertEquals(PrecipitationState.UNAVAILABLE, values.last()!!.precipitation.state)
      job.cancel()
    }
  }

  @Test fun `ETA boundary is reconsumed before next minute and is removed`() = runTest {
    val values = mutableListOf<WeatherBrief?>()
    var calls = 0
    val eta = noon + 10_000
    val controller = HomeBriefController({ p, g, s ->
      calls++
      val b = model(p, g, s, noon + testScheduler.currentTime)
      if (testScheduler.currentTime < 10_000) b.copy(precipitation = b.precipitation.copy(
        horizon = WeatherInterval(noon, noon + 3_600_000), arrivalAtMillis = eta)) else b
    }, { noon + testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, snapshot()), { snapshot() }, values::add) }
    runCurrent(); assertEquals(eta, values.last()!!.precipitation.arrivalAtMillis)
    advanceTimeBy(10_000); runCurrent()
    assertEquals(2, calls)
    assertNull(values.last()!!.precipitation.arrivalAtMillis)
    job.cancel()
  }

  @Test fun `pause cancels consumption inactive time does no work resume consumes immediately`() = runTest {
    var calls = 0
    val controller = HomeBriefController({ p, g, s -> calls++; delay(100); model(p, g, s) },
      { noon }, StandardTestDispatcher(testScheduler))
    val values = mutableListOf<WeatherBrief?>()
    suspend fun consume() = controller.consume(HomeBriefInput(place, point, snapshot()), { snapshot() }, values::add)
    val job = backgroundScope.launch { consume() }
    runCurrent(); job.cancelAndJoin()
    val count = values.size
    advanceTimeBy(120_000); runCurrent()
    assertEquals(1, calls); assertEquals(count, values.size)
    val resumed = backgroundScope.launch { consume() }
    runCurrent(); assertEquals(2, calls); assertEquals(count + 1, values.size)
    resumed.cancel()
  }

  @Test fun `wipe generation rejects noncancellable late result and deleted snapshot stops reconsume`() = runTest {
    var generation = 0L
    val gate = CompletableDeferred<Unit>()
    val values = mutableListOf<WeatherBrief?>()
    val controller = HomeBriefController({ p, g, s -> withContext(NonCancellable) { gate.await() }; model(p, g, s) },
      { noon }, StandardTestDispatcher(testScheduler), generation = { generation })
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, snapshot()), { null }, values::add) }
    runCurrent(); generation++; gate.complete(Unit); runCurrent()
    assertNull(values.last()); assertEquals(2, values.size); assertTrue(job.isCompleted)
    val normal = HomeBriefController({ p, g, s -> model(p, g, s) }, { noon }, StandardTestDispatcher(testScheduler))
    val next = backgroundScope.launch { normal.consume(HomeBriefInput(place, point, snapshot()), { null }, values::add) }
    runCurrent(); advanceTimeBy(60_000); runCurrent()
    assertNull(values.last()); assertTrue(next.isCompleted)
  }
}
