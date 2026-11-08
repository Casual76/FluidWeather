package dev.pampa.fluidweather

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.pampa.fluidweather.core.data.FluidWeatherDatabase
import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.UnitFormatter
import java.util.UUID
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production Activity/graph/Room/Compose semantics, with explicitly SYNTHETIC local records. */
@RunWith(AndroidJUnit4::class)
class WeatherStartupReliabilityTest {
  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  private val app get() = instrumentation.targetContext.applicationContext as FluidWeatherApp
  private val graph get() = app.graph

  @Test(timeout = 90_000)
  fun coldGpsDeniedHome_showsPersistedPressureAndHistory_thenConsumesRoomUpdateWithoutRefresh() {
    assertEquals("Debug package only; never run against the production archive",
      "dev.pampa.fluidweather.debug", app.packageName)
    assertTrue(BuildConfig.DEBUG)
    instrumentation.runOnMainSync {
      val monitor = ActivityLifecycleMonitorRegistry.getInstance()
      assertTrue("Cold Activity precondition: close the existing MainActivity first",
        Stage.values().filter { it != Stage.DESTROYED }
          .flatMap { monitor.getActivitiesInStage(it) }.none { it is MainActivity })
    }
    assertEnvironment()
    assertTrue("Requires a pressure sensor; a sensorless tablet cannot pass this contract",
      (app.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        .getDefaultSensor(Sensor.TYPE_PRESSURE) != null)
    assertTrue(graph.barometer.isAvailable)
    assertNull("A release gate must be resolved by the parent, never bypassed here",
      io { ReleaseGate.of(graph.remoteConfig.config.first()) })
    assertNull("Requires no cached GPS weather; do not delete another user's snapshot",
      io { graph.weatherSnapshotStore.read(WeatherSnapshot.keyFor(Place.GPS_ID)) })
    assertFalse("Continuous sampling must already be off", io { graph.samplingSettingsStore.current() }.continuousWhileOpen)
    assertNull("Finish calibration before this test", graph.calibrationController.progress.value)

    // New Activity/composition, not a simulated process death: instrumentation owns this process.
    val tag = "SYNTHETIC-startup-${UUID.randomUUID()}"
    Log.i("WeatherStartupTest", "Fixture cleanup identifier: $tag")
    val epoch = System.currentTimeMillis()
    val times = listOf(epoch - 120_123, epoch - 60_123, epoch - 30_123)
    val db = FluidWeatherDatabase.build(app)
    var scenario: ActivityScenario<MainActivity>? = null
    val done = io { graph.onboardingStore.done.first() }
    val selected = graph.selectedPlaceStore.current()
    val order = io { graph.homeLayoutStore.current() }
    val tutorialsDisabled = io { graph.tutorialStore.disabledAll.first() }
    val beforeHistory = io { graph.nowcastHistoryStore.since(epoch - 24 * 3_600_000L) }
    assertTrue("No near-expiry history: counts must remain stable throughout the deadline",
      beforeHistory.none { it.timestampMillis < epoch - 24 * 3_600_000L + 90_000 })
    assertTrue("No future archive samples may mask the fixture",
      io { graph.pressureRepository.latestSampleMillis() }?.let { it < epoch } ?: true)
    val units = UnitFormatter(app.resources, io { graph.unitsStore.preferences.first() })
    val firstPressure = 987.3
    val nextPressure = 988.6
    assertNotEquals(units.pressureValue(firstPressure, 1), units.pressureValue(nextPressure, 1))
    try {
      io {
        // A second Room handle is used ONLY for isolated SQL fixtures/cleanup. Home reads the
        // real graph stores. Pressure writes below use the graph's own DAO invalidation tracker.
        db.openHelper.writableDatabase // Open/validate the existing production schema.
        times.forEach { time ->
          db.openHelper.readableDatabase.query(
            "SELECT timestampMillis FROM nowcast_verdicts WHERE timestampMillis = ?", arrayOf<Any>(time),
          ).use { assertFalse("Fixture timestamp collision; never replace history", it.moveToFirst()) }
        }
        graph.onboardingStore.setDone(true)
        graph.tutorialStore.setDisabledAll(true)
        graph.homeLayoutStore.setOrder(listOf("pressure", "nowcast") + order.filterNot { it == "pressure" || it == "nowcast" })
        graph.selectedPlaceStore.select(Place.GPS_ID)
        times.take(2).forEach { insertHistory(db, it, tag) }
        graph.pressureRepository.record(listOf(sample(epoch - 123, firstPressure, tag)))
        assertEquals(firstPressure, graph.pressureRepository.latest(1).first().single().pressureHpa, 0.0)
        assertEquals(beforeHistory.size + 2, graph.nowcastHistoryStore.since(epoch - 24 * 3_600_000L).size)
      }

      val launchedAt = SystemClock.uptimeMillis()
      scenario = ActivityScenario.launch(MainActivity::class.java)
      assertEquals(Lifecycle.State.RESUMED, scenario.state)
      await("weather unavailable on cold GPS Home", launchedAt + 8_000) {
        hasText(it, app.getString(R.string.rain_quality_now_unknown))
      }
      // Accessibility scrolling only reveals the persisted value; it never pulls to refresh.
      reveal("persisted raw pressure", launchedAt + 8_000) {
        hasText(it, units.pressureValue(firstPressure, 1)) &&
          hasText(it, app.getString(R.string.tile_pressure_raw, units.pressureSymbol()))
      }

      click("open production Nowcast tile", SystemClock.uptimeMillis() + 8_000) { node ->
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK &&
          it.label?.toString() == app.getString(R.string.a11y_open_widget, app.getString(R.string.widget_nowcast)) }
      }
      click("expand signal/history disclosure", SystemClock.uptimeMillis() + 8_000) {
        hasText(it, app.getString(R.string.quality_signal_history)) && it.isClickable
      }
      val initialCount = beforeHistory.size + 2
      val initialAlerts = beforeHistory.count { it.level == "ALLERTA" } + 2
      val watches = beforeHistory.count { it.level == "SORVEGLIANZA" }
      revealHistory(initialCount, initialAlerts, watches, SystemClock.uptimeMillis() + 8_000)

      // While this SAME Home is resumed and its real detail remains open, change Room.
      // History has no Flow of its own: the graph pressure write must trigger local hydration.
      io {
        insertHistory(db, times[2], tag)
        graph.pressureRepository.record(listOf(sample(System.currentTimeMillis(), nextPressure, tag)))
      }
      // Five seconds is below the 60-second hydration timer: waiting for polling cannot pass.
      await("Room invalidation updates the visible history without refresh", SystemClock.uptimeMillis() + 5_000) {
        historyRow(it, initialCount + 1, initialAlerts + 1, watches)
      }
      // Exercise production Back rather than requiring a scrolled-away header control.
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      reveal("updated raw pressure in the original Home", SystemClock.uptimeMillis() + 5_000,
        direction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
        hasText(it, units.pressureValue(nextPressure, 1)) &&
          hasText(it, app.getString(R.string.tile_pressure_raw, units.pressureSymbol())) &&
          !hasText(it, units.pressureValue(firstPressure, 1))
      }
      assertEnvironment()
      assertNull(io { graph.weatherSnapshotStore.read(WeatherSnapshot.keyFor(Place.GPS_ID)) })
    } finally {
      try { scenario?.close() } finally {
        try {
          io {
            // No clear/prune/wipe. Both predicates are unique fixture identifiers.
            db.openHelper.writableDatabase.execSQL("DELETE FROM pressure_samples WHERE burstId = ?", arrayOf<Any>(tag))
            times.forEach { time -> db.openHelper.writableDatabase.execSQL(
              "DELETE FROM nowcast_verdicts WHERE timestampMillis = ? AND variant = ?", arrayOf<Any>(time, tag)) }
          }
        } finally {
          try {
            try { io { graph.homeLayoutStore.setOrder(order) } } finally {
              try { io { graph.tutorialStore.setDisabledAll(tutorialsDisabled) } }
              finally { io { graph.onboardingStore.setDone(done) } }
            }
          } finally { graph.selectedPlaceStore.select(selected); db.close() }
        }
      }
    }
  }

  private fun sample(at: Long, pressure: Double, tag: String) = PressureSample(
    at, pressure, SampleSource.PERIODIC, burstId = tag, activity = ActivityKind.STILL,
  ) // SampleSource has no SYNTHETIC enum: burstId explicitly labels these fixtures.

  private fun insertHistory(db: FluidWeatherDatabase, at: Long, tag: String) {
    // Plain INSERT aborts on collision (the production DAO uses REPLACE). No throttling or
    // unrelated-row mutation; variant labels the SYNTHETIC data and guards exact cleanup.
    db.openHelper.writableDatabase.execSQL(
      "INSERT INTO nowcast_verdicts (timestampMillis, probability01, probability13, probability36, level, variant) VALUES (?, ?, ?, ?, ?, ?)",
      arrayOf<Any>(at, 0.61, 0.73, 0.42, "ALLERTA", tag),
    )
  }

  private fun assertEnvironment() {
    assertFalse("Fine AND coarse GPS permission must already be denied; test never changes permissions",
      graph.locationProvider.hasPermission())
    // No fix plus no GPS snapshot makes the Home weather load return unavailable regardless
    // of connectivity. This test must not require changing the owner's network settings.
  }

  private fun <T> io(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

  private fun revealHistory(count: Int, alerts: Int, watches: Int, deadline: Long) =
    reveal("persisted history count and alert breakdown", deadline) { historyRow(it, count, alerts, watches) }

  private fun historyRow(root: AccessibilityNodeInfo, count: Int, alerts: Int, watches: Int): Boolean =
    find(root) { node ->
      // Match the actual StatRow subtree, not an unrelated number somewhere in the window.
      hasText(node, app.getString(R.string.nowcast_verdicts_recorded)) &&
        hasText(node, count.toString()) &&
        hasText(node, app.getString(R.string.nowcast_verdict_counts, alerts, watches)) &&
        node.childCount in 1..3
    }

  private fun hasText(root: AccessibilityNodeInfo, text: String): Boolean = find(root) {
    it.text?.toString()?.split('\n')?.contains(text) == true || it.contentDescription?.toString() == text
  }

  /** Native semantics only; never waits for global animation idleness or prints user data. */
  private fun await(label: String, deadline: Long, predicate: (AccessibilityNodeInfo) -> Boolean) {
    while (SystemClock.uptimeMillis() < deadline) {
      if (window(predicate)) return
      Thread.sleep(50)
    }
    dumpFailure(label)
    throw AssertionError("Deadline exceeded: $label")
  }

  private fun reveal(label: String, deadline: Long,
    direction: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
    predicate: (AccessibilityNodeInfo) -> Boolean) {
    while (SystemClock.uptimeMillis() < deadline) {
      if (window(predicate)) return
      window { root -> find(root) { it.isScrollable && it.performAction(direction) } }
      Thread.sleep(150)
    }
    dumpFailure(label)
    throw AssertionError("Deadline exceeded revealing: $label")
  }

  private fun click(label: String, deadline: Long, scroll: Boolean = true,
    direction: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, match: (AccessibilityNodeInfo) -> Boolean) {
    while (SystemClock.uptimeMillis() < deadline) {
      if (window { root -> find(root) { match(it) && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } }) return
      if (scroll) window { root -> find(root) { it.isScrollable && it.performAction(direction) } }
      Thread.sleep(150)
    }
    dumpFailure(label)
    throw AssertionError("Deadline exceeded clicking: $label")
  }

  private fun dumpFailure(label: String) {
    val out = StringBuilder(label).append('\n')
    val root = instrumentation.uiAutomation.rootInActiveWindow
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
      if (depth > 30 || out.length > 100_000) return
      if (node.packageName?.toString() == app.packageName) out.append(" ".repeat(depth))
        .append("visible=").append(node.isVisibleToUser).append(" clickable=").append(node.isClickable)
        .append(" children=").append(node.childCount).append(" text=").append(node.text)
        .append(" desc=").append(node.contentDescription).append('\n')
      for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
        try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
      }
    }
    root?.let { try { visit(it, 0) } finally { @Suppress("DEPRECATION") it.recycle() } }
    File(app.getExternalFilesDir(null), "startup-test-failure.txt").writeText(out.toString())
  }

  private fun window(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
    try { return block(root) } finally { @Suppress("DEPRECATION") root.recycle() }
  }

  private fun find(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
    var visited = 0
    fun visit(node: AccessibilityNodeInfo): Boolean {
      check(++visited <= 2_000) { "Native accessibility tree exceeded node budget" }
      if (node.isVisibleToUser && node.packageName?.toString() == app.packageName && predicate(node)) return true
      for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        try { if (visit(child)) return true } finally { @Suppress("DEPRECATION") child.recycle() }
      }
      return false
    }
    return visit(root)
  }
}
