package dev.pampa.fluidweather

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Parent-run only: real Activity/graph, offline, no wipe or global settings changes. */
class WeatherWidgetNavigationTest {
  @get:Rule val compose = createEmptyComposeRule()
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
    val place = Place(-System.currentTimeMillis(), "SYNTHETIC-widget-${UUID.randomUUID()}", null, 44.0, 11.0, "Europe/Rome")
    val key = WeatherSnapshot.keyFor(place.id)
    val file = File(app.filesDir, "snapshots/$key.json")
    assertFalse(file.exists())
    assertTrue(io { graph.savedLocationsRepository.places.first() }.none { it.id == place.id })
    val previous = graph.selectedPlaceStore.current()
    val start = System.currentTimeMillis() / 3_600_000 * 3_600_000
    val hour = start + 3_600_000
    var scenario: ActivityScenario<MainActivity>? = null
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
        compose.waitUntil(15_000) {
          graph.selectedPlaceStore.current() == place.id &&
            compose.onAllNodesWithTag("hourly_probability_$hour").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("hourly_probability_$hour").assertTextEquals("37%")
      }
      awaitSavedHourly()
      compose.onNodeWithContentDescription(app.getString(R.string.common_close)).performClick()
      compose.waitUntil(5_000) { compose.onAllNodesWithTag("hourly_probability_$hour").fetchSemanticsNodes().isEmpty() }
      graph.selectedPlaceStore.select(Place.GPS_ID)
      instrumentation.targetContext.startActivity(intent(AppDestination.Home(place.id))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      compose.waitUntil(15_000) { graph.selectedPlaceStore.current() == place.id }
      compose.onNodeWithTag("hourly_probability_$hour").assertDoesNotExist()
      // Actual singleTask delivery: identical URI must still generate a fresh AppRequest nonce.
      instrumentation.targetContext.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      awaitSavedHourly()
      compose.onNodeWithContentDescription(app.getString(R.string.common_close)).performClick()
      compose.waitUntil(5_000) { compose.onAllNodesWithTag("hourly_probability_$hour").fetchSemanticsNodes().isEmpty() }
      io { graph.savedLocationsRepository.remove(place.id) }
      instrumentation.targetContext.startActivity(link)
      compose.waitUntil(15_000) {
        graph.selectedPlaceStore.current() == Place.GPS_ID &&
          compose.onAllNodesWithText(app.getString(R.string.widget_hourly)).fetchSemanticsNodes().isNotEmpty() &&
          compose.onAllNodesWithContentDescription(app.getString(R.string.common_close)).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag("hourly_probability_$hour").assertDoesNotExist()
      assertTrue(io { graph.savedLocationsRepository.places.first() }.none { it.id == place.id })
    } finally {
      try {
        scenario?.close()
        io { graph.savedLocationsRepository.remove(place.id) }
        // Only the unique file owned by this fixture. No global cache clear/wipe.
        if (file.exists()) assertTrue(file.delete())
      } finally {
        graph.selectedPlaceStore.select(previous)
        io { graph.tutorialStore.setDisabledAll(previousTutorialsDisabled) }
      }
    }
  }

  private fun intent(destination: AppDestination) = Intent(Intent.ACTION_VIEW, AppDeepLink.uri(destination), app, MainActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
  private fun <T> io(block: suspend () -> T): T = runBlocking { kotlinx.coroutines.withTimeout(5_000) { block() } }
}
