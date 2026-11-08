package dev.pampa.fluidweather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
  ), timeline = listOf(PrecipitationPeriod(
    WeatherInterval(now, now + 3_600_000L), PrecipitationState.WET,
    amountMm = 1.0, evidence = listOf(WeatherEvidence("forecast", WeatherEvidenceType.MODEL,
      temporalResolutionMillis = 900_000L, sourceTimestampMillis = now,
      downloadedAtMillis = now - 60_000L)), role = PrecipitationPeriodRole.FORECAST,
  )))
  private fun show(width: Int, scale: Float = 1f, brief: WeatherBrief? = brief(),
    language: String? = null, showCurrent: Boolean = true, showForecast: Boolean = true,
    onOpenSummary: (() -> Unit)? = null) {
    compose.setContent {
      val device = LocalDensity.current
      val baseContext = LocalContext.current
      val configuration = Configuration(LocalConfiguration.current).apply {
        if (language != null) setLocales(LocaleList.forLanguageTags(language))
      }
      val localizedContext = baseContext.createConfigurationContext(configuration)
      // Fit both axes: on a landscape tablet, fitting width alone would leave a 225dp-tall
      // virtual phone. Keep the requested 360/411 x 760dp viewport and target font scale.
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val targetDensity = device.density * minOf(maxWidth.value / width, maxHeight.value / 760f)
        CompositionLocalProvider(LocalDensity provides Density(targetDensity, scale),
          LocalContext provides localizedContext, LocalConfiguration provides configuration) {
          FluidTheme(settings = dev.antigravity.fluidengine.foundation.EngineSettings()) {
            Column(Modifier.requiredWidth(width.dp).height(760.dp).verticalScroll(rememberScrollState())) {
              HomeRainOverview(brief, "Roma", nowMillis = now, showCurrent = showCurrent,
                showForecast = showForecast, onOpenSummary = onOpenSummary)
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
    val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
    val sourceTime = dev.pampa.fluidweather.strings.TimeFormats.dayTime(now - 600_000,
      java.time.ZoneId.systemDefault(), resources.configuration.locales[0])
    compose.onNodeWithText(resources.getString(dev.pampa.fluidweather.strings.R.string.rain_quality_source_time, sourceTime))
      .performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("home_rain_sources_toggle").performScrollTo().performClick()
    compose.onNodeWithTag("home_rain_sources_details").assertDoesNotExist()
  }
  @Test fun unavailableIsAnExplicitHeading() {
    show(411, brief = null)
    val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
    // An entirely unknown strip is not a useful graph; the explicit unknown heading remains.
    compose.onNodeWithTag("home_rain_timeline").assertDoesNotExist()
    compose.onNodeWithTag("home_rain_headline").assertIsDisplayed()
      .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
    // Expected localized wording comes from resources, and must differ from a model wet result.
    val actual = compose.onNodeWithTag("home_rain_headline").fetchSemanticsNode()
      .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
    assertEquals(resources.getString(dev.pampa.fluidweather.strings.R.string.rain_quality_now_unknown), actual)
  }
  @Test fun conflictIsExplicitAndDoesNotOfferAnEta() {
    show(411, brief = brief(true))
    compose.onNodeWithTag("home_rain_transition").assertDoesNotExist()
    val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
    compose.onNodeWithTag("home_rain_headline").assertTextEquals(resources.getString(dev.pampa.fluidweather.strings.R.string.glance_rain_conflict))
    compose.onNodeWithText(resources.getString(dev.pampa.fluidweather.strings.R.string.home_rain_conflict_limit)).assertDoesNotExist()
    compose.onNodeWithTag("home_rain_sources_toggle").performClick()
    compose.onNodeWithText(resources.getString(dev.pampa.fluidweather.strings.R.string.home_rain_conflict_limit)).performScrollTo().assertIsDisplayed()
  }
  @Test fun doubledFontWrapsWithoutTruncationAndTimelineRemainsReachable() {
    // Short headings may fit on one line; test overflow and reachability, not line count.
    show(360, 2f, brief = brief(true))
    val results = mutableListOf<TextLayoutResult>()
    compose.onNodeWithTag("home_rain_headline").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    assertTrue(results.isNotEmpty())
    assertFalse(results.single().didOverflowWidth)
    assertFalse(results.single().didOverflowHeight)
    compose.onNodeWithTag("home_rain_timeline").performScrollTo().assertIsDisplayed()
  }
  private fun partial(probability: Double?) = brief().copy(
    precipitation = LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, now),
    timeline = listOf(PrecipitationPeriod(WeatherInterval(now + 900_000L, now + 2_700_000L),
      PrecipitationState.DRY, amountMm = 0.0, probabilityPercent = probability,
      evidence = listOf(WeatherEvidence("forecast", WeatherEvidenceType.MODEL)),
      role = PrecipitationPeriodRole.FORECAST)))

  @Test fun realZeroProbabilityHasMetricWhileUnknownHasNone() {
    show(360, brief = partial(0.0), showCurrent = false)
    compose.onNodeWithTag("home_rain_probability").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_gap_key").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_amount").assertDoesNotExist()
    compose.onNodeWithTag("home_rain_transition").assertDoesNotExist()
  }

  @Test fun missingProbabilityIsNeverRenderedAsZero() {
    show(360, brief = partial(null), showCurrent = false)
    compose.onNodeWithTag("home_rain_probability").assertDoesNotExist()
    compose.onNodeWithTag("home_rain_gap_key").assertIsDisplayed()
    compose.onNodeWithTag("home_rain_timeline").assertIsDisplayed()
  }

  @Test fun englishPopulatedGapTimelineAtDoubleFont() = populatedLargeFont("en")
  @Test fun italianPopulatedGapTimelineAtDoubleFont() = populatedLargeFont("it")

  private fun populatedLargeFont(language: String) {
    show(360, 2f, brief = partial(4.0), language = language, showCurrent = false)
    val results = mutableListOf<TextLayoutResult>()
    compose.onNodeWithTag("home_rain_headline").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    assertFalse(results.single().didOverflowWidth)
    assertFalse(results.single().didOverflowHeight)
    compose.onNodeWithTag("home_rain_timeline").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("home_rain_probability").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("home_rain_gap_key").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(if (language == "it") "Ora non disponibile" else "Now unavailable").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("home_rain_sources_details").assertDoesNotExist()
    compose.onNodeWithTag("home_rain_sources_toggle").performScrollTo().performClick()
    compose.onNodeWithTag("home_rain_sources_details").performScrollTo().assertIsDisplayed()
  }

  @Test fun forecastCanBeHiddenWithoutChangingSummaryCallback() {
    var opened = 0
    show(411, brief = partial(4.0), showCurrent = false, showForecast = false, onOpenSummary = { opened++ })
    compose.onNodeWithTag("home_rain_timeline").assertDoesNotExist()
    compose.onNodeWithTag("home_rain_probability").assertDoesNotExist()
    compose.onNodeWithTag("home_summary_open").performClick()
    assertEquals(1, opened)
  }

}
