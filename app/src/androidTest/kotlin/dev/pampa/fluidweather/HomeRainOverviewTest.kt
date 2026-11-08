package dev.pampa.fluidweather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.feature.home.HomeRainOverview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic presentation only; does not claim meteorological accuracy or device QA. */
class HomeRainOverviewTest {
  @get:Rule val compose = createComposeRule()
  private val now = 1_800_000L
  private fun brief(conflict: Boolean = false) = WeatherBrief(LocalPrecipitationAssessment(
    state = if (conflict) PrecipitationState.UNCERTAIN else PrecipitationState.WET,
    evaluatedAtMillis = now,
    evidence = listOf(WeatherEvidence("model", WeatherEvidenceType.MODEL, sourceTimestampMillis = now - 600_000,
      downloadedAtMillis = now - 300_000, precipitationState = PrecipitationState.WET)) +
      if (conflict) listOf(WeatherEvidence("radar", WeatherEvidenceType.RADAR_ESTIMATE,
        sourceTimestampMillis = now - 300_000, downloadedAtMillis = now - 300_000, precipitationState = PrecipitationState.DRY)) else emptyList(),
    limits = if (conflict) setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS) else setOf(WeatherEvidenceLimit.MODEL_ONLY),
  ))
  private fun show(width: Int, scale: Float = 1f, brief: WeatherBrief? = brief()) {
    compose.setContent {
      val device = LocalDensity.current
      // Fit both axes: on a landscape tablet, fitting width alone would leave a 225dp-tall
      // virtual phone. Keep the requested 360/411 x 760dp viewport and target font scale.
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val targetDensity = device.density * minOf(maxWidth.value / width, maxHeight.value / 760f)
        CompositionLocalProvider(LocalDensity provides Density(targetDensity, scale)) {
          FluidTheme(settings = dev.antigravity.fluidengine.foundation.EngineSettings()) {
            Column(Modifier.requiredWidth(width.dp).height(760.dp).verticalScroll(rememberScrollState())) {
              HomeRainOverview(brief, "Roma", nowMillis = now)
            }
          }
        }
      }
    }
  }
  @Test fun answerAndTimelineVisibleAt360WithoutOpeningTiles() {
    show(360)
    compose.onNodeWithTag("home_rain_headline").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_timeline").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_overview").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
      .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
  }
  @Test fun answerAndTimelineVisibleAt411WithoutOpeningTiles() {
    show(411)
    compose.onNodeWithTag("home_rain_headline").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_timeline").assertIsDisplayed()
  }
  @Test fun originalSourceTimesRemainAvailableWithoutCrowdingTheAnswer() {
    show(411)
    compose.onNodeWithTag("home_rain_sources_details").assertDoesNotExist()
    compose.onNodeWithTag("home_rain_headline").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_sources_toggle").performClick()
    compose.onNodeWithTag("home_rain_sources_details").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("home_rain_sources_toggle").performClick()
    compose.onNodeWithTag("home_rain_sources_details").assertDoesNotExist()
  }
  @Test fun unavailableIsAnExplicitHeading() {
    show(411, brief = null)
    val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
    compose.onNodeWithTag("home_rain_timeline").assertContentDescriptionContains(resources.getString(dev.pampa.fluidweather.strings.R.string.home_rain_timeline_unknown), substring = true)
    compose.onNodeWithTag("home_rain_headline").assertIsDisplayed()
      .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
    // Expected localized wording comes from resources, and must differ from a model wet result.
    val actual = compose.onNodeWithTag("home_rain_headline").fetchSemanticsNode()
      .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
    assertEquals(resources.getString(dev.pampa.fluidweather.strings.R.string.home_rain_unavailable), actual)
  }
  @Test fun conflictIsExplicitAndDoesNotOfferAnEta() {
    show(411, brief = brief(true))
    val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
    compose.onNodeWithTag("home_rain_headline").assertTextEquals(resources.getString(dev.pampa.fluidweather.strings.R.string.home_rain_conflict))
    compose.onNodeWithText(resources.getString(dev.pampa.fluidweather.strings.R.string.home_rain_no_eta)).assertIsDisplayed()
  }
  @Test fun doubledFontWrapsWithoutTruncationAndTimelineRemainsReachable() {
    show(360, 2f)
    val results = mutableListOf<TextLayoutResult>()
    compose.onNodeWithTag("home_rain_headline").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    assertTrue(results.isNotEmpty())
    assertTrue(results.single().lineCount > 1)
    assertFalse(results.single().didOverflowWidth)
    assertFalse(results.single().didOverflowHeight)
    compose.onNodeWithTag("home_rain_timeline").performScrollTo().assertIsDisplayed()
  }
}
