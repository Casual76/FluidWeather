package dev.pampa.fluidweather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.pages.ProviderPressurePage
import dev.pampa.fluidweather.feature.home.pages.DetailsPage
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.UnitFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlin.math.abs

/** Synthetic sparse data through actual production page bodies, not weather accuracy. */
class PressureGapQualityTest {
  @get:Rule val compose = createComposeRule()
  private val hour = 3_600_000L
  private val start = System.currentTimeMillis() / hour * hour
  private val context get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
  private val units get() = UnitFormatter(context.resources, UnitPreferences.METRIC)
  private fun sample(offset: Int, value: Double) = FusedHour(start + offset * hour,
    mapOf(FusionVariables.PRESSURE_MSL to FusedValue(value, emptyList())), null)
  private fun show(hours: List<FusedHour>, details: Boolean = false) {
    compose.setContent {
      FluidTheme(settings = EngineSettings(themeMode = ThemeMode.DARK)) {
        BlackSheet("Pressure fixture", {}) {
          val state = HomeUiState(fusedHours = hours, barometerApplies = false)
          if (details) DetailsPage(state) else ProviderPressurePage(state)
        }
      }
    }
  }
  private fun assertCell(label: Int, value: String) {
    val labelBounds = compose.onNodeWithText(context.getString(label).uppercase(), useUnmergedTree = true)
      .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    // Layout-only Columns are absent from the semantics tree. Match the value directly
    // beneath this label, rather than every value in the flattened grid's parent.
    val cellValues = compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes()
      .filter { abs(it.boundsInRoot.left - labelBounds.left) <= 1f &&
        abs(it.boundsInRoot.top - labelBounds.bottom) <= 1f }
    assertEquals("Value directly below ${context.getString(label)}", 1, cellValues.size)
  }
  @Test fun missingThreeHourPressureDoesNotShowSeventeenHourChangeOrStorm() {
    show(listOf(sample(0, 1020.0), sample(17, 1016.0)))
    assertCell(R.string.pressure_provider_next3h, "\u2014")
    assertCell(R.string.pressure_trend, "\u2014")
    compose.onNodeWithText(context.getString(R.string.pressure_storm)).assertDoesNotExist()
    compose.onNodeWithText(context.getString(R.string.pressure_change)).assertDoesNotExist()
  }
  @Test fun supportedThreeHourSampleKeepsItsValueAndRate() {
    show(listOf(sample(0, 1020.0), sample(3, 1018.5), sample(17, 1005.0)))
    assertCell(R.string.pressure_provider_next3h, units.pressure(1018.5, 1))
    assertCell(R.string.pressure_trend, units.pressureRate(-.5, 2))
  }
  @Test fun detailsPressureCurveExposesMissingElapsedTime() {
    show(listOf(sample(0, 1020.0), sample(17, 1017.0), sample(19, 1016.0)), details = true)
    compose.onNodeWithText(context.getString(R.string.quality_curve_gaps, 90)).performScrollTo().assertIsDisplayed()
  }
  @Test fun detailsPeakIsAvailableFutureUvAndRejectsInvalidHazards() {
    val now = java.time.Instant.parse("2026-11-08T14:10:00Z").toEpochMilli()
    fun uv(utcHour: Int, value: Double): FusedHour {
      val t = java.time.Instant.parse("2026-11-08T${utcHour.toString().padStart(2, '0')}:00:00Z").toEpochMilli()
      return FusedHour(t, mapOf(FusionVariables.UV_INDEX to FusedValue(value, emptyList())), null)
    }
    val hours = listOf(uv(11, 7.0), uv(14, 3.0), uv(15, 2.0), uv(16, Double.POSITIVE_INFINITY),
      uv(17, Double.NaN), uv(18, 10.0), uv(18, 1.0))
    compose.setContent {
      FluidTheme(settings = EngineSettings(themeMode = ThemeMode.DARK)) {
        BlackSheet("UV fixture", {}) { DetailsPage(HomeUiState(fusedHours = hours), evaluatedAtMillis = now) }
      }
    }
    compose.onNodeWithText(context.getString(R.string.quality_uv_available_peak)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("2", substring = false).assertIsDisplayed()
    compose.onNodeWithText(context.getString(R.string.details_uv_max)).assertDoesNotExist()
    compose.onNodeWithText("Infinity", substring = true).assertDoesNotExist()
    compose.onNodeWithText("NaN", substring = true).assertDoesNotExist()
  }
}
