package dev.pampa.fluidweather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.pages.PrecipitationPage
import dev.pampa.fluidweather.strings.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Production page under a phone viewport. Synthetic data proves presentation, not weather truth. */
class PrecipitationQualityTest {
  @get:Rule val compose = createComposeRule()
  private val hour = 3_600_000L
  private val now = System.currentTimeMillis()
  private val start = now - Math.floorMod(now, hour) + hour
  private val resources get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
  @Test fun smallPositiveZeroAndMissingHaveVisibleValuesWithoutOpeningTheTable() {
    show(listOf(sample(start, 0.0, 0.0), sample(start + hour, 1.0, 0.0), sample(start + 2 * hour, null, null)))
    compose.onNodeWithTag("precip_bar_value_0").performScrollTo().assertTextEquals("0%")
    compose.onNodeWithTag("precip_bar_value_1").performScrollTo().assertTextEquals("1%")
    compose.onNodeWithTag("precip_bar_value_2").performScrollTo().assertTextEquals("\u2014")
    compose.onNodeWithTag("precip_hour_table").assertDoesNotExist()
  }
  private fun sample(at: Long, probability: Double?, amount: Double?) = FusedHour(at, buildMap {
    probability?.let { put(FusionVariables.PRECIP_PROBABILITY, FusedValue(it, emptyList())) }
    amount?.let { put(FusionVariables.PRECIPITATION, FusedValue(it, emptyList())) }
  }, null)
  private fun show(hours: List<FusedHour>, scale: Float = 1f, brief: WeatherBrief? = null) {
    compose.setContent {
      val density = LocalDensity.current
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = density.density * minOf(maxWidth.value / 360f, maxHeight.value / 800f)
        CompositionLocalProvider(LocalDensity provides Density(fit, scale)) {
          FluidTheme(settings = EngineSettings()) {
            Column(Modifier.requiredWidth(360.dp).height(800.dp).verticalScroll(rememberScrollState())) {
              PrecipitationPage(HomeUiState(locationName = "Roma", fusedHours = hours, weatherBrief = brief))
            }
          }
        }
      }
    }
  }
  @Test fun weekOfSamplesHasBoundedDefaultAndExplicitDetails() {
    show((0 until 168).map { sample(start + it * hour, 40.0, 0.1) })
    compose.onNodeWithTag("precip_probability_chart").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("precip_hour_table").assertDoesNotExist()
    compose.onNodeWithTag("precip_sources_details").assertDoesNotExist()
    compose.onNodeWithTag("precip_hour_6").assertDoesNotExist()
    compose.onNodeWithTag("precip_forecast_peak").assertTextEquals("40%")
    compose.onNodeWithTag("precip_hours_toggle").performScrollTo().performClick()
    compose.onNodeWithTag("precip_hour_5").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("precip_hour_6").assertDoesNotExist()
    compose.onNodeWithTag("precip_sources_toggle").performScrollTo().performClick()
    compose.onNodeWithTag("precip_sources_details").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("precip_sources_toggle").performScrollTo().performClick()
    compose.onNodeWithTag("precip_sources_details").assertDoesNotExist()
  }
  @Test fun laterDayAndLaterHoursRemainReachableAtDoubleFont() {
    show((0 until 72).map { sample(start + it * hour, (it % 100).toDouble(), 0.0) }, scale = 2f)
    // Second available day is complete, so it always has more than one six-position page.
    compose.onNodeWithTag("precip_day_selector").performScrollTo().performScrollToIndex(2)
    val locale = resources.configuration.locales[0]
    val zone = java.time.ZoneId.systemDefault()
    val secondDay = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate().plusDays(1)
    compose.onNodeWithTag("precip_day_1")
      .assertTextEquals(dev.pampa.fluidweather.strings.TimeFormats.shortDate(secondDay, locale))
      .assertContentDescriptionEquals(dev.pampa.fluidweather.strings.TimeFormats.longDate(secondDay, locale))
    compose.onNodeWithTag("precip_day_1").performClick()
    compose.onNodeWithTag("precip_hours_toggle").performScrollTo().performClick()
    val firstPage = compose.onNodeWithTag("precip_hour_0").fetchSemanticsNode().config[
      androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
    compose.onNodeWithTag("precip_later").performScrollTo().performClick()
    val secondPage = compose.onNodeWithTag("precip_hour_0").fetchSemanticsNode().config[
      androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
    org.junit.Assert.assertNotEquals(firstPage, secondPage)
    compose.onNodeWithTag("precip_earlier").performScrollTo().performClick()
    assertEquals(firstPage, compose.onNodeWithTag("precip_hour_0").fetchSemanticsNode().config[
      androidx.compose.ui.semantics.SemanticsProperties.ContentDescription])
  }
  @Test fun zeroAndAbsentAmountsRemainDifferentInTheExactTable() {
    show(listOf(sample(start, 0.0, 0.0), sample(start + hour, null, null)))
    compose.onNodeWithTag("precip_forecast_peak").assertTextEquals("0%")
    compose.onNodeWithTag("precip_hours_toggle").performScrollTo().performClick()
    compose.onNodeWithTag("precip_hour_0").performScrollTo()
      .assertContentDescriptionContains("0%", substring = true)
    compose.onNodeWithTag("precip_hour_1").performScrollTo()
      .assertContentDescriptionContains(resources.getString(R.string.home_rain_timeline_unknown), substring = true)
  }
  @Test fun noHoursIsUnknownAndStillOffersMethod() {
    show(emptyList())
    compose.onNodeWithTag("precip_forecast_peak").assertTextEquals("\u2014")
    compose.onNodeWithTag("precip_empty").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("precip_sources_toggle").performScrollTo().performClick()
    compose.onNodeWithTag("precip_sources_details").performScrollTo().assertIsDisplayed()
  }
  @Test fun forecastPageHasOneChartInsteadOfRepeatingTheHomeTimeline() {
    val source = WeatherEvidence("model", WeatherEvidenceType.MODEL, sourceTimestampMillis = now,
      downloadedAtMillis = now, precipitationState = PrecipitationState.WET)
    val brief = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.WET, now,
      evidence = listOf(source), limits = setOf(WeatherEvidenceLimit.MODEL_ONLY)), timeline = listOf(
      PrecipitationPeriod(WeatherInterval(now, now + hour), PrecipitationState.WET,
        amountMm = 1.0, evidence = listOf(source), role = PrecipitationPeriodRole.FORECAST)))
    show((0 until 6).map { sample(start + it * hour, 40.0, 0.1) }, brief = brief)
    compose.onNodeWithTag("home_rain_headline").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_timeline").assertDoesNotExist()
    compose.onNodeWithTag("precip_probability_chart").performScrollTo().assertIsDisplayed()
  }
}
