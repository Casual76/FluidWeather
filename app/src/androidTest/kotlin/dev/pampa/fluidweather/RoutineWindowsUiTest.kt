package dev.pampa.fluidweather

import android.view.View
import android.widget.TimePicker
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.core.data.RoutineWindowStore
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.LocalUnits
import dev.pampa.fluidweather.feature.home.HomeRoutineWindows
import dev.pampa.fluidweather.feature.settings.RoutineWindowsScreen
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.theme.FluidWeatherTheme
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Dedicated QA emulator only: original personal settings are restored after each test. */
class RoutineWindowsUiTest {
  @get:Rule val compose = createComposeRule()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private lateinit var store: RoutineWindowStore
  private var original = emptyList<RoutineWindow>()

  @Before fun preserveSettings() = runBlocking {
    store = RoutineWindowStore(context)
    original = store.current()
    store.clear()
  }
  @After fun restoreSettings() = runBlocking {
    store.clear()
    original.forEach { store.upsert(it) }
  }

  private fun label(id: Int) = context.getString(id)
  private fun reach(text: String): SemanticsNodeInteraction {
    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    return compose.onNodeWithText(text)
  }
  private fun pickTime(labelId: Int, hour: Int, minute: Int) {
    reach(label(labelId)).performClick()
    Espresso.onView(isAssignableFrom(TimePicker::class.java)).inRoot(isDialog()).perform(object : ViewAction {
      override fun getDescription() = "choose $hour:$minute in the visible time picker"
      override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
      override fun perform(controller: UiController, view: View) {
        (view as TimePicker).apply { this.hour = hour; this.minute = minute }
        controller.loopMainThreadUntilIdle()
      }
    })
    Espresso.onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
  }

  @Test fun editorAtDoubleFontValidatesCreatesEditsAndDeletesAnOvernightWindow() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        FluidWeatherTheme { RoutineWindowsScreen(store, onBack = {}) }
      }
    }
    compose.onNodeWithText(label(R.string.routines_add)).performClick()
    compose.onNode(hasSetTextAction()).performTextInput("Unsaved draft")
    Espresso.closeSoftKeyboard()
    Espresso.pressBack()
    reach(label(R.string.routines_add)).performClick()
    reach(label(R.string.routines_save)).performClick()
    reach(label(R.string.routines_invalid)).assertIsDisplayed()
    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasSetTextAction())
    compose.onNode(hasSetTextAction()).performTextInput("Passeggiata serale")
    Espresso.closeSoftKeyboard()
    val locale = context.resources.configuration.locales[0]
    reach(DayOfWeek.MONDAY.getDisplayName(java.time.format.TextStyle.FULL, locale)).performClick()
    pickTime(R.string.routines_start, 23, 0)
    pickTime(R.string.routines_end, 1, 0)
    reach(label(R.string.routines_overnight)).assertIsDisplayed()
    reach(label(R.string.routines_save)).performClick()
    compose.waitUntil(5_000) { runBlocking { store.current().size == 1 } }
    val saved = runBlocking { store.current().single() }
    assertEquals(LocalTime.of(23, 0), saved.start)
    assertEquals(LocalTime.of(1, 0), saved.end)
    assertEquals(setOf(DayOfWeek.MONDAY), saved.daysOfWeek)
    reach("Passeggiata serale").performClick()
    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasSetTextAction())
    compose.onNode(hasSetTextAction()).performTextReplacement("Rientro")
    Espresso.closeSoftKeyboard()
    reach(label(R.string.routines_save)).performClick()
    compose.waitUntil(5_000) { runBlocking { store.current().singleOrNull()?.name == "Rientro" } }
    assertEquals(saved.id, runBlocking { store.current().single().id })
    reach("Rientro").performClick()
    reach(label(R.string.routines_delete)).performClick()
    compose.waitUntil(5_000) { runBlocking { store.current().isEmpty() } }
    reach(label(R.string.routines_empty)).assertIsDisplayed()
  }

  @Test fun homeShowsTwoThenExpandsOthersAndUsesSelectedUnits() {
    val now = System.currentTimeMillis()
    val assessments = (1..3).map { index ->
      val interval = WeatherInterval(now + index * 3_600_000L, now + (index + 1) * 3_600_000L)
      val occurrence = RoutineOccurrence(RoutineWindow("$index", "Moment $index", setOf(DayOfWeek.MONDAY),
        LocalTime.NOON, LocalTime.of(13, 0)), interval,
        ResolvedWeatherZone(ZoneId.of("Europe/Rome"), WeatherZoneSource.PLACE))
      RoutineWindowAssessment(occurrence, now, PrecipitationState.WET, 3_600_000, 2.54,
        minTemperatureC = 20.0, maxTemperatureC = 20.0,
        evidence = listOf(WeatherEvidence("test-model", WeatherEvidenceType.MODEL, interval, now)))
    }
    var opened = false
    compose.setContent {
      CompositionLocalProvider(LocalUnits provides UnitPreferences.forCountry("US")) {
        FluidWeatherTheme {
          Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HomeRoutineWindows(assessments) { opened = true }
          }
        }
      }
    }
    compose.onNodeWithText("Moment 1").assertExists()
    compose.onNodeWithText("Moment 2").assertExists()
    compose.onNodeWithText("Moment 3").assertDoesNotExist()
    compose.onAllNodesWithText("68 " + label(R.string.unit_fahrenheit), substring = true).assertCountEquals(2)
    val amount = String.format(Locale.getDefault(), "%.2f", 0.1) + " " + label(R.string.unit_inch)
    compose.onAllNodesWithText(amount, substring = true).assertCountEquals(2)
    compose.onNodeWithText(context.getString(R.string.routines_more, 3)).performScrollTo().performClick()
    compose.onNodeWithText("Moment 3").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(label(R.string.routines_edit)).performScrollTo().performClick()
    compose.runOnIdle { assertTrue(opened) }
  }
}
