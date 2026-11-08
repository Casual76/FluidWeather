package dev.pampa.fluidweather

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.*
import dev.pampa.fluidweather.core.weather.radar.RadarSample
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.UnitFormatter
import dev.pampa.fluidweather.strings.WidgetSharedRainFormatter
import dev.pampa.fluidweather.strings.labelRes
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real application/Activity consumers; synthetic weather only. Requires an isolated offline QA app. */
@RunWith(AndroidJUnit4::class)
class WeatherLifecycleIntegrationTest {
  private val app get() = InstrumentationRegistry.getInstrumentation()
    .targetContext.applicationContext as FluidWeatherApp
  private val graph get() = app.graph
  private val a = Place(-6_601, "F6 synthetic A", null, 44.0, 11.0, "Europe/Rome")
  private val b = Place(-6_602, "F6 synthetic B", null, 45.0, 12.0, "Europe/Rome")
  private var scenario: ActivityScenario<MainActivity>? = null
  private var restore: (suspend () -> Unit)? = null

  @Before fun prepareRealGraph() = runBlocking {
    assertOffline()
    assertNull("Cached release gate blocks the real Home; do not bypass it", ReleaseGate.of(graph.remoteConfig.config.first()))
    val saved = graph.savedLocationsRepository.places.first()
    val reserved = saved.filter { it.id == a.id || it.id == b.id }
    if (reserved.isNotEmpty() && InstrumentationRegistry.getArguments().getString("recoverAbortedFixtures") == "true") {
      assertTrue("Recovery may remove only exact fixtures left by this test", reserved.all { it == a || it == b })
      reserved.forEach { graph.savedLocationsRepository.remove(it.id) }
      Log.i("WeatherLifecycleTest", "Removed exact fixtures from an explicitly recovered aborted run")
    } else {
      assertTrue("Reserved fixture IDs must not replace existing places", reserved.isEmpty())
    }
    val done = graph.onboardingStore.done.first()
    val selected = graph.selectedPlaceStore.current()
    restore = {
      graph.selectedPlaceStore.select(selected)
      try { graph.savedLocationsRepository.remove(a.id) }
      finally {
        try { graph.savedLocationsRepository.remove(b.id) }
        finally { graph.onboardingStore.setDone(done) }
      }
    }
    graph.onboardingStore.setDone(true)
    graph.savedLocationsRepository.save(a)
    graph.savedLocationsRepository.save(b)
    graph.selectedPlaceStore.select(a.id)
    // Authorized archive cleanup, through the same privacy boundary as the assertion below.
    graph.wipeAllData()
    val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val available = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    assertEquals("Production barometer capability", available, graph.barometer.isAvailable)
    Log.i("WeatherLifecycleTest", "barometerAvailable=$available; synthetic weather; offline")
    Unit
  }

  @After fun finish() {
    try {
      scenario?.close()
    } finally {
      runBlocking {
        try { if (restore != null) graph.wipeAllData() }
        finally { restore?.invoke() }
      }
    }
  }

  @Test(timeout = 120_000) fun wipeInvalidatesRealHome_recreateReopenOfflineRejectLateRound_thenFreshRefreshRecovers() {
    val seed = fixture(a, wet = true)
    runBlocking { graph.weatherSnapshotStore.write(seed) }
    launchHome()
    awaitKnown(a, wet = true, temperature = 17.0)
    assertWidget(a, PrecipitationState.WET, 17.0)
    assertEquals(seed, runBlocking { diskStore().read(key(a)) })

    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val delayed = refresher(seed, entered, release)
    val oldRound = scope.async {
      try {
        delayed.refresh(key(a), a.latitude, a.longitude, registerPredictions = false)
        false
      } catch (_: CancellationException) {
        true
      }
    }
    try {
      runBlocking { withTimeout(10_000) { entered.await() } }
      val places = runBlocking { graph.savedLocationsRepository.places.first() }
      val routines = runBlocking { graph.routineWindowStore.current() }
      val units = runBlocking { graph.unitsStore.preferences.first() }
      val settings = runBlocking { graph.engineSettingsStore.settings.first() }
      val generation = graph.wipeGuard.generation()
      runBlocking { graph.wipeAllData() }
      assertTrue(graph.wipeGuard.generation() > generation)
      awaitUnknown()

      // No cancellation from the test: the pre-wipe source returns after the actual graph wipe.
      release.complete(Unit)
      assertTrue("Old refresh must be rejected by the shared wipe epoch", runBlocking {
        withTimeout(10_000) { oldRound.await() }
      })
      assertErased(a)
      scenario!!.recreate()
      awaitUnknown()
      assertErased(a)
      scenario!!.close()
      scenario = null
      assertOffline()
      launchHome()
      awaitUnknown()
      assertErased(a)
      runBlocking {
        assertEquals(places, graph.savedLocationsRepository.places.first())
        assertEquals(routines, graph.routineWindowStore.current())
        assertEquals(units, graph.unitsStore.preferences.first())
        assertEquals(settings, graph.engineSettingsStore.settings.first())
        assertTrue(graph.onboardingStore.done.first())
      }
      assertEquals(a.id, graph.selectedPlaceStore.current())

      // Explicit acquisition: real refresher/store/guard, deliberately synthetic RoundSource.
      val fresh = fixture(a, wet = false, temperature = 23.0)
      runBlocking { refresher(fresh).refresh(key(a), a.latitude, a.longitude, registerPredictions = false) }
      awaitKnown(a, wet = false, temperature = 23.0)
      assertWidget(a, PrecipitationState.DRY, 23.0)
      assertEquals(fresh.context, runBlocking { diskStore().read(key(a)) }!!.context)
      assertOffline()
    } finally {
      release.complete(Unit)
      scope.cancel()
    }
  }

  @Test(timeout = 120_000) fun activeHomeConsumesSameTimestampChanges_andRapidPlaceSwitches_acrossPauseResume() {
    val original = fixture(a, wet = true)
    runBlocking {
      graph.weatherSnapshotStore.write(original)
      graph.weatherSnapshotStore.write(fixture(b, wet = false, temperature = 29.0))
    }
    launchHome()
    awaitKnown(a, wet = true, temperature = 17.0)
    val revision = graph.weatherSnapshotStore.changes.value.getValue(key(a))
    // Preserve all clocks, changing only actual content. updates alone cannot signal this.
    val originalContext = original.context!!
    val changed = original.copy(context = originalContext.copy(
      current = originalContext.current!!.copy(temperatureC = 21.0, precipitationMm = 0.0)))
    runBlocking { graph.weatherSnapshotStore.write(changed) }
    assertEquals(original.fetchedAtMillis, graph.weatherSnapshotStore.updates.value[key(a)])
    assertTrue(graph.weatherSnapshotStore.changes.value.getValue(key(a)) > revision)
    awaitKnown(a, wet = false, temperature = 21.0)
    repeat(3) {
      graph.selectedPlaceStore.select(b.id)
      graph.selectedPlaceStore.select(a.id)
    }
    graph.selectedPlaceStore.select(b.id)
    awaitKnown(b, wet = false, temperature = 29.0)
    assertWidget(b, PrecipitationState.DRY, 29.0)
    scenario!!.moveToState(Lifecycle.State.CREATED)
    assertEquals(Lifecycle.State.CREATED, scenario!!.state)
    runBlocking { graph.weatherSnapshotStore.write(fixture(b, wet = true, temperature = 31.0)) }
    scenario!!.moveToState(Lifecycle.State.RESUMED)
    awaitKnown(b, wet = true, temperature = 31.0)
    graph.selectedPlaceStore.select(a.id)
    awaitKnown(a, wet = false, temperature = 21.0)
    assertWidget(a, PrecipitationState.DRY, 21.0)
    assertOffline()
  }

  @Test(timeout = 120_000) fun activeHomeRecoversFromExplicitPostWipeRoundWithoutRecreation() {
    runBlocking { graph.weatherSnapshotStore.write(fixture(a, wet = true)) }
    launchHome()
    awaitKnown(a, wet = true, temperature = 17.0)
    runBlocking { graph.wipeAllData() }
    awaitUnknown()
    assertErased(a)
    val fresh = fixture(a, wet = false, temperature = 24.0)
    runBlocking { refresher(fresh).refresh(key(a), a.latitude, a.longitude, registerPredictions = false) }
    assertWidget(a, PrecipitationState.DRY, 24.0)
    awaitKnown(a, wet = false, temperature = 24.0)
    // Do not wait for the empty state: the StateFlow clear emission may be conflated.
    runBlocking {
      graph.wipeAllData()
      val immediate = fixture(a, wet = true, temperature = 25.0)
      refresher(immediate).refresh(key(a), a.latitude, a.longitude, registerPredictions = false)
    }
    awaitKnown(a, wet = true, temperature = 25.0)
    assertWidget(a, PrecipitationState.WET, 25.0)
    assertOffline()
  }

  private fun launchHome() {
    scenario = ActivityScenario.launch(MainActivity::class.java)
    assertEquals(Lifecycle.State.RESUMED, scenario!!.state)
  }

  private fun awaitKnown(place: Place, wet: Boolean, temperature: Double) {
    val degrees = UnitFormatter(app.resources, graph.notificationUnits.value).degrees(temperature)
    val headline = app.getString(if (wet) R.string.home_rain_model_wet else R.string.home_rain_model_dry)
    val current = app.getString(R.string.home_rain_current_model, degrees,
      app.getString(WeatherKind.CLOUDY.labelRes()!!))
    val visible = awaitWindow("$headline / ${place.name} / $current") {
      headline in it.headings && place.name in it.texts && current in it.texts
    }
    assertFalse(app.getString(R.string.home_rain_current_unknown) in visible.texts)
    Log.i("WeatherLifecycleTest", "Verified real Home: ${place.name}, wet=$wet, temperatureC=$temperature")
  }

  private fun awaitUnknown() {
    val unavailable = app.getString(R.string.home_rain_unavailable)
    val unknown = app.getString(R.string.home_rain_current_unknown)
    val timelinePrefix = app.getString(R.string.home_rain_hour) + ". "
    val visible = awaitWindow("unavailable current/rain and visible timeline") {
      unavailable in it.headings && unknown in it.texts && it.descriptions.any { text -> text.startsWith(timelinePrefix) }
    }
    val timeline = visible.descriptions.filter { it.startsWith(timelinePrefix) }.joinToString(" ")
    assertFalse("No wet timeline may survive wipe", timeline.contains(": " + app.getString(R.string.home_rain_timeline_wet)))
    assertFalse("Unknown must not become dry", timeline.contains(": " + app.getString(R.string.home_rain_timeline_dry)))
    Log.i("WeatherLifecycleTest", "Verified real Home: current/rain/timeline unavailable")
  }

  private data class WindowSnapshot(val texts: Set<String>, val headings: Set<String>, val descriptions: Set<String>)

  /** Reads the real Android accessibility window without Espresso's global graphics-idle gate. */
  private fun awaitWindow(expected: String, predicate: (WindowSnapshot) -> Boolean): WindowSnapshot {
    val end = SystemClock.uptimeMillis() + 15_000
    var last: WindowSnapshot? = null
    while (SystemClock.uptimeMillis() < end) {
      last = readWindow()
      if (last != null && predicate(last)) return last
      Thread.sleep(50)
    }
    throw AssertionError("Real Home did not show $expected; last=$last")
  }

  private fun readWindow(): WindowSnapshot? {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
    val texts = linkedSetOf<String>(); val headings = linkedSetOf<String>(); val descriptions = linkedSetOf<String>()
    var nodes = 0
    fun visit(node: AccessibilityNodeInfo) {
      check(++nodes <= 2_000) { "Native window exceeded the bounded snapshot size" }
      if (node.isVisibleToUser && node.packageName?.toString() == app.packageName) {
        node.text?.toString()?.let { texts += it; if (node.isHeading) headings += it }
        node.contentDescription?.toString()?.let { descriptions += it }
      }
      for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }
      @Suppress("DEPRECATION")
      node.recycle()
    }
    visit(root)
    return WindowSnapshot(texts, headings, descriptions)
  }

  private fun assertErased(place: Place) {
    runBlocking {
      assertNull(app.snapshotStore.read(key(place)))
      assertNull(diskStore().read(key(place)))
      val radar = graph.radarAssessmentCache.read(GeoPoint(place.latitude, place.longitude))
      assertTrue("Only missing or explicit unavailable radar is allowed offline after wipe",
        radar == null || radar is RadarSample.Unavailable)
    }
    assertWidget(place, PrecipitationState.UNAVAILABLE, null)
  }

  private fun assertWidget(place: Place, state: PrecipitationState, temperature: Double?) = runBlocking {
    val snapshot = app.snapshotStore.read(key(place))
    val brief = app.cachedWeatherBriefs.cachedBrief(place, GeoPoint(place.latitude, place.longitude), snapshot)
    val now = System.currentTimeMillis()
    assertEquals(state, brief.precipitation.state)
    assertEquals(temperature, WidgetSharedRainFormatter.current(brief, now)?.temperatureC)
    assertEquals(when (state) {
      PrecipitationState.WET -> BriefRainHeadline.MODEL_WET
      PrecipitationState.DRY -> BriefRainHeadline.MODEL_DRY
      else -> BriefRainHeadline.UNAVAILABLE
    }, WidgetSharedRainFormatter.headline(brief, now))
  }

  private fun assertOffline() {
    assertEquals("Parent must enable airplane mode before instrumentation", 1,
      Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
    val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    assertNull("No active default network is permitted (including Wi-Fi/VPN)", connectivity.activeNetwork)
    assertTrue("Disconnect every network before instrumentation", connectivity.allNetworks.isEmpty())
  }

  private fun key(place: Place) = WeatherSnapshot.keyFor(place.id)
  private fun diskStore() = WeatherSnapshotStore(File(app.filesDir, "snapshots"), wipeGuard = graph.wipeGuard)

  private fun fixture(place: Place, wet: Boolean, temperature: Double = 17.0): WeatherSnapshot {
    val now = System.currentTimeMillis()
    val context = ForecastBundle("open-meteo", now - 1_000, place.latitude, place.longitude,
      emptyList(), current = ProviderCurrent(now - 2_000, temperature, WeatherKind.CLOUDY,
        if (wet) 1.0 else 0.0, 900_000, now - 1_000, true), zoneId = place.zoneId)
    return WeatherSnapshot(key(place), place.latitude, place.longitude, now, emptyList(),
      FusedForecast(emptyList(), emptyMap()), context, zoneId = place.zoneId)
  }

  private fun refresher(snapshot: WeatherSnapshot, entered: CompletableDeferred<Unit>? = null,
    release: CompletableDeferred<Unit>? = null): WeatherSnapshotRefresher {
    val source = object : RoundSource {
      override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
        assertEquals(snapshot.latitude, latitude, 0.0)
        assertEquals(snapshot.longitude, longitude, 0.0)
        assertFalse("Synthetic round must not register verification predictions", registerPredictions)
        entered?.complete(Unit)
        if (release != null) withContext(NonCancellable) { withTimeout(45_000) { release.await() } }
        return WeatherRound(listOf(ProviderFetch(ProviderRegistry.all.first { it.id == "open-meteo" },
          snapshot.context, null)), snapshot.fused)
      }
    }
    return WeatherSnapshotRefresher(source, graph.weatherSnapshotStore, wipeGuard = graph.wipeGuard)
  }
}
