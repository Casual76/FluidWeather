package dev.pampa.fluidweather

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.feature.home.*
import dev.pampa.fluidweather.feature.home.pages.DetailsPage
import dev.pampa.fluidweather.feature.home.pages.PrecipitationPage
import dev.pampa.fluidweather.strings.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

/** Runs production composables. Parent executes; synthetic input supplies no weather accuracy evidence. */
@RunWith(Parameterized::class)
class WeatherF6AccessibilityTest(private val language: String, private val width: Int, private val scale: Float) {
  companion object {
    @JvmStatic @Parameterized.Parameters(name = "{0}-{1}dp-{2}x")
    fun cases(): List<Array<Any>> = listOf("en", "it").flatMap { language ->
      listOf(360, 800).flatMap { width -> listOf(1f, 2f).map { arrayOf<Any>(language, width, it) } }
    }
  }
  @get:Rule val compose = createComposeRule()
  private lateinit var resources: android.content.res.Resources
  private fun show(content: @Composable () -> Unit) {
    compose.setContent {
      val original = LocalContext.current
      val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale(language))) }
      val localized = original.createConfigurationContext(config)
      resources = localized.resources
      val device = LocalDensity.current
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = device.density * minOf(maxWidth.value / width, maxHeight.value / 900f)
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
          LocalDensity provides Density(fit, scale)) {
          FluidTheme(settings = EngineSettings()) {
            Column(Modifier.requiredWidth(width.dp).height(900.dp).verticalScroll(rememberScrollState())) { content() }
          }
        }
      }
    }
  }
  @Test fun dailyLongDayAndTemperaturesHaveRealUnclippedTextLayouts() {
    val day = if (language == "it") "Oggi - mercoled\u00ec 30 settembre" else "Today - Wednesday 30 September"
    show { DailyForecastRows(listOf(DailyForecastRow("first", day, -123.4, 123.4, WeatherKind.RAIN),
      DailyForecastRow("last", day, -98.0, 106.0, WeatherKind.SNOW)), -123.4, 123.4) }
    listOf("first", "last").forEach { id ->
      compose.onNodeWithTag("daily_row_$id").performScrollTo().assertIsDisplayed()
      listOf("day", "min", "max").forEach { field ->
        val node = compose.onNodeWithTag("daily_${field}_$id", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { result ->
          val description = "daily_${field}_$id text=${result.layoutInput.text} size=${result.size} constraints=${result.layoutInput.constraints} paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}"
          assertFalse(description, result.didOverflowHeight)
          for (line in 0 until result.lineCount) {
            assertFalse(description, result.isLineEllipsized(line))
            // The semantics result may reuse a paragraph laid out at maxWidth while Text
            // measures to its intrinsic width. Check the painted line bounds, not that
            // paragraph's unused trailing width. One pixel permits integer rounding.
            assertTrue(description, result.getLineLeft(line) >= -1f)
            assertTrue(description, result.getLineRight(line) <= result.size.width + 1f)
            assertTrue(description, result.getLineBottom(line) <= result.size.height + 1f)
          }
          // A temperature must never be broken into vertical digits.
          val text = result.layoutInput.text.text
          val digits = text.indices.filter { text[it].isDigit() }
          if (digits.isNotEmpty()) assertEquals(result.getLineForOffset(digits.first()), result.getLineForOffset(digits.last()))
        }
      }
    }
    compose.onNodeWithTag("daily_day_first", true).assertTextEquals(day)
  }
  private fun state(conflict: Boolean): HomeUiState {
    val now = System.currentTimeMillis()
    val evidence = listOf(WeatherEvidence("model", WeatherEvidenceType.MODEL, sourceTimestampMillis = now - 120000,
      downloadedAtMillis = now - 60000, precipitationState = PrecipitationState.WET),
      WeatherEvidence("radar", WeatherEvidenceType.RADAR_ESTIMATE, sourceTimestampMillis = now - 120000,
        downloadedAtMillis = now - 60000, precipitationState = PrecipitationState.DRY))
    return HomeUiState(locationName = "Roma", weatherBrief = if (conflict) WeatherBrief(LocalPrecipitationAssessment(
      PrecipitationState.UNCERTAIN, now, evidence = evidence, limits = setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS))) else null)
  }
  private fun assertSharedWithoutHours(precipitation: Boolean, conflict: Boolean) {
    val state = state(conflict)
    show { if (precipitation) PrecipitationPage(state) else DetailsPage(state) }
    compose.onNodeWithTag("home_rain_headline").assertTextEquals(resources.getString(
      if (conflict) R.string.home_rain_conflict else R.string.home_rain_unavailable))
    compose.onNodeWithTag("home_rain_timeline").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(resources.getString(R.string.home_rain_current_unknown)).assertExists()
    if (conflict) {
      compose.onNodeWithTag("home_rain_sources_toggle").performScrollTo().performClick()
      compose.onNodeWithTag("home_rain_sources_details").performScrollTo().assertIsDisplayed()
    }
  }
  @Test fun detailsRetainsConflictAndSourceActionWithoutHourlyData() = assertSharedWithoutHours(false, true)
  @Test fun detailsRetainsUnknownWithoutHourlyData() = assertSharedWithoutHours(false, false)
  @Test fun precipitationRetainsConflictAndSourceActionWithoutHourlyData() = assertSharedWithoutHours(true, true)
  @Test fun precipitationRetainsUnknownWithoutHourlyData() = assertSharedWithoutHours(true, false)
  @Test fun rainGraphKeepsZeroMissingAndGapClocks() {
    val hour = 3_600_000L
    val now = System.currentTimeMillis()
    val start = now - Math.floorMod(now, hour) + hour
    fun sample(at: Long, mm: Double?, pop: Double?) = FusedHour(at, buildMap {
      mm?.let { put(FusionVariables.PRECIPITATION, FusedValue(it, emptyList())) }
      pop?.let { put(FusionVariables.PRECIP_PROBABILITY, FusedValue(it, emptyList())) }
    }, null)
    val state = HomeUiState(locationName = "Roma", fusedHours = listOf(sample(start, 0.0, 0.0),
      sample(start + 2*hour, 2.0, null), sample(start + 3*hour, 0.0, 0.0), sample(start + 3*hour, 0.0, 0.0)))
    show { PrecipitationPage(state) }
    val locale = Locale(language)
    val zone = java.time.ZoneId.systemDefault()
    fun label(at: Long, value: String) = resources.getString(R.string.accessibility_probability_sample,
      dev.pampa.fluidweather.strings.accessibilitySampleTime(at, zone, locale), value)
    // Zero is explicitly 0%; an absent timestamp and duplicate timestamp remain unknown.
    compose.onAllNodesWithText(label(start, "0%")).onFirst().performScrollTo().assertIsDisplayed()
    val unknown = resources.getString(R.string.home_rain_timeline_unknown)
    compose.onAllNodesWithText(label(start + hour, unknown)).onFirst().performScrollTo().assertIsDisplayed()
    compose.onAllNodesWithText(label(start + 3*hour, unknown)).onFirst().performScrollTo().assertIsDisplayed()
  }
  @Test fun shortTimelineUsesLocalizedQualifierAndPreservesSourcePrecision() {
    val now = System.currentTimeMillis()
    val source = WeatherEvidence("model", WeatherEvidenceType.MODEL, temporalResolutionMillis = 900000,
      sourceTimestampMillis = now, downloadedAtMillis = now, precipitationState = PrecipitationState.WET)
    val brief = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.WET, now,
      evidence = listOf(source), limits = setOf(WeatherEvidenceLimit.MODEL_ONLY)), timeline = listOf(
      PrecipitationPeriod(WeatherInterval(now - 10000, now + 8000), PrecipitationState.WET,
        amountMm = 1.0, evidence = listOf(source), role = PrecipitationPeriodRole.FORECAST)))
    show { HomeRainOverview(brief, "Roma", nowMillis = now) }
    val zone = java.time.ZoneId.systemDefault()
    val expected = dev.pampa.fluidweather.strings.accessibleIntervalLabel(resources, now, now + 8000, zone, Locale(language))
    compose.onNodeWithTag("home_rain_timeline").assertContentDescriptionContains(expected, substring = true)
    compose.onNodeWithTag("home_rain_timeline").assertContentDescriptionContains(
      resources.getString(R.string.accessibility_resolution, "900.0 s"), substring = true)
  }

}
