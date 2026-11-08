package dev.pampa.fluidweather

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Parent-run only: real Activity/graph, offline, no wipe or global settings changes. */
class WeatherWidgetNavigationTest {
  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  private val app get() = instrumentation.targetContext.applicationContext as FluidWeatherApp
  private val graph get() = app.graph

  @Test fun uriIdentityAndLegacyCompatibility() {
    assertEquals(AppDestination.Home(42), AppDeepLink.parse(AppDeepLink.uri(AppDestination.Home(42))))
    assertFalse(intent(AppDestination.Home(42)).filterEquals(intent(AppDestination.Home(43))))
    for (widget in HomeWidget.entries) {
      val destination = AppDestination.Widget(widget, 42)
      assertEquals(destination, AppDeepLink.parse(AppDeepLink.uri(destination)))
      val a = intent(destination)
      assertFalse(a.filterEquals(intent(AppDestination.Widget(widget, 43))))
      assertFalse(a.filterEquals(intent(AppDestination.Widget(widget, Place.GPS_ID))))
      assertEquals(AppDestination.Widget(widget), AppDeepLink.parse(Uri.parse("fluidweather://widget/${widget.id}")))
      assertEquals(AppDestination.Widget(widget), AppDeepLink.parse(Uri.parse("fluidweather://open/${widget.id}?placeId=bad")))
      assertEquals(AppDestination.Widget(widget), AppDeepLink.parse(AppDeepLink.uri(AppDestination.Widget(widget))))
    }
    for (screen in AppScreen.entries) {
      assertEquals(AppDestination.Screen(screen), AppDeepLink.parse(AppDeepLink.uri(AppDestination.Screen(screen))))
    }
  }

  @Test(timeout = 90_000) fun coldGpsDefault_savedHourly_repeat_thenDeletedTargetFallsBack() {
    assertEquals("Debug archive only", "dev.pampa.fluidweather.debug", app.packageName)
    val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    assertNull("Parent must disconnect networks before running", connectivity.activeNetwork)
    assertTrue(connectivity.allNetworks.isEmpty())
    instrumentation.runOnMainSync {
      val monitor = ActivityLifecycleMonitorRegistry.getInstance()
      assertTrue("Close existing MainActivity; this test never closes somebody else's Activity",
        Stage.values().filter { it != Stage.DESTROYED }
          .flatMap { monitor.getActivitiesInStage(it) }.none { it is MainActivity })
    }
    assertTrue("Complete onboarding before running", io { graph.onboardingStore.done.first() })
    val previousTutorialsDisabled = io { graph.tutorialStore.disabledAll.first() }
    assertNull("Resolve release gate before running", io { ReleaseGate.of(graph.remoteConfig.config.first()) })
    // Explicit recovery for a parent's failed run only; never scans/deletes arbitrary archives.
    InstrumentationRegistry.getArguments().getString("cleanupOwnedPlaceId")?.toLongOrNull()?.let { id ->
      val owned = io { graph.savedLocationsRepository.places.first() }.singleOrNull { it.id == id }
      if (owned != null) {
        require(owned.name.startsWith("SYNTHETIC-widget-") && owned.latitude == 44.0 && owned.longitude == 11.0)
        UUID.fromString(owned.name.removePrefix("SYNTHETIC-widget-"))
        io { graph.savedLocationsRepository.remove(id) }
        val ownedFile = File(app.filesDir, "snapshots/${WeatherSnapshot.keyFor(id)}.json")
        if (ownedFile.exists()) check(ownedFile.delete())
      }
    }
    val place = Place(-System.currentTimeMillis(), "SYNTHETIC-widget-${UUID.randomUUID()}", null, 44.0, 11.0, "Europe/Rome")
    val key = WeatherSnapshot.keyFor(place.id)
    val file = File(app.filesDir, "snapshots/$key.json")
    assertFalse(file.exists())
    assertTrue(io { graph.savedLocationsRepository.places.first() }.none { it.id == place.id })
    val previous = graph.selectedPlaceStore.current()
    Log.i("WeatherWidgetNavTest", "Owned fixture id=${place.id}, name=${place.name}")
    val start = System.currentTimeMillis() / 3_600_000 * 3_600_000
    val hour = start + 3_600_000
    var scenario: ActivityScenario<MainActivity>? = null
    var primaryFailure: Throwable? = null
    try {
      io {
        graph.tutorialStore.setDisabledAll(true)
        graph.savedLocationsRepository.save(place)
        graph.weatherSnapshotStore.write(WeatherSnapshot(key, place.latitude, place.longitude,
          System.currentTimeMillis(), emptyList(), FusedForecast(listOf(FusedHour(hour,
            mapOf(FusionVariables.PRECIP_PROBABILITY to FusedValue(37.0, emptyList()),
              FusionVariables.TEMPERATURE to FusedValue(23.0, emptyList())), WeatherKind.CLOUDY)), emptyMap()),
          null, zoneId = place.zoneId))
      }
      graph.selectedPlaceStore.select(Place.GPS_ID)
      val link = intent(AppDestination.Widget(HomeWidget.HOURLY, place.id))
      scenario = ActivityScenario.launch(link)
      fun awaitSavedHourly() {
        await("saved-place Hourly with owned probability") { root ->
          graph.selectedPlaceStore.current() == place.id && hasText(root, "37%") &&
            hasText(root, app.getString(R.string.widget_hourly)) && closeVisible(root)
        }
      }
      awaitSavedHourly()
      closeDetail()
      // Actual singleTask delivery: identical URI must still generate a fresh AppRequest nonce.
      instrumentation.targetContext.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      awaitSavedHourly()
      // Same-place root delivery must dismiss a still-open detail; no Back or place change.
      instrumentation.targetContext.startActivity(intent(AppDestination.Home(place.id))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      await("same-place root dismisses the active Hourly detail") { root ->
        graph.selectedPlaceStore.current() == place.id && hasText(root, place.name) && !closeVisible(root)
      }
      graph.selectedPlaceStore.select(Place.GPS_ID)
      instrumentation.targetContext.startActivity(intent(AppDestination.Home(place.id))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      await("root widget opens saved Home without a detail") { root ->
        graph.selectedPlaceStore.current() == place.id && hasText(root, place.name) && !closeVisible(root)
      }
      io { graph.savedLocationsRepository.remove(place.id) }
      instrumentation.targetContext.startActivity(link)
      await("deleted target opens GPS Hourly") { root ->
        graph.selectedPlaceStore.current() == Place.GPS_ID &&
          hasText(root, app.getString(R.string.widget_hourly)) && closeVisible(root) && !hasText(root, "37%")
      }
      assertTrue(io { graph.savedLocationsRepository.places.first() }.none { it.id == place.id })
    } catch (failure: Throwable) {
      primaryFailure = failure
      Log.e("WeatherWidgetNavTest", "Primary navigation assertion failed", failure)
      throw failure
    } finally {
      try {
        try { scenario?.close() } catch (cleanup: Throwable) {
          if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
        } finally {
          io { graph.savedLocationsRepository.remove(place.id) }
          if (file.exists()) assertTrue(file.delete())
        }
      } finally {
        graph.selectedPlaceStore.select(previous)
        io { graph.tutorialStore.setDisabledAll(previousTutorialsDisabled) }
      }
    }
  }

  private fun intent(destination: AppDestination) = Intent(Intent.ACTION_VIEW, AppDeepLink.uri(destination), app, MainActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
  private fun <T> io(block: suspend () -> T): T = runBlocking { kotlinx.coroutines.withTimeout(5_000) { block() } }

  // Native visible nodes avoid coupling a live animated Home to global Compose idleness.
  private fun await(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 15_000
    while (SystemClock.uptimeMillis() < deadline) {
      val root = instrumentation.uiAutomation.rootInActiveWindow
      if (root != null) try { if (predicate(root)) return } finally { @Suppress("DEPRECATION") root.recycle() }
      Thread.sleep(50)
    }
    throw AssertionError("Deadline exceeded: $label")
  }
  private fun find(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
    var count = 0
    fun visit(node: AccessibilityNodeInfo): Boolean {
      check(++count <= 2_000)
      if (node.isVisibleToUser && node.packageName?.toString() == app.packageName && predicate(node)) return true
      for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
        try { if (visit(child)) return true } finally { @Suppress("DEPRECATION") child.recycle() }
      }
      return false
    }
    return visit(root)
  }
  private fun hasText(root: AccessibilityNodeInfo, text: String) = find(root) {
    it.text?.toString()?.split('\n')?.contains(text) == true || it.contentDescription?.toString() == text
  }
  private fun closeVisible(root: AccessibilityNodeInfo) = find(root) {
    it.contentDescription?.toString() == app.getString(R.string.common_close)
  }
  private fun closeDetail() {
    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    await("closed detail") { !closeVisible(it) }
  }
}
