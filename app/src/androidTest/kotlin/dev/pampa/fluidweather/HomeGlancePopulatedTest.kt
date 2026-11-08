package dev.pampa.fluidweather

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.feature.home.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.*
import java.util.Locale

/** Real populated formatter data, fake selector only. Virtual viewports fit the host display. */
@RunWith(Parameterized::class)
class HomeGlancePopulatedTest(private val language: String, private val width: Int,
  private val font: Float, private val state: PrecipitationState) {
  @get:Rule val compose = createComposeRule()
  companion object {
    @JvmStatic @Parameterized.Parameters(name = "{0}-{1}dp-font{2}-{3}")
    fun cases(): List<Array<Any>> = listOf("en", "it").flatMap { language ->
      listOf(360, 800).flatMap { width -> listOf(1f, 1.5f).flatMap { font ->
        PrecipitationState.entries.map { arrayOf<Any>(language, width, font, it) }
      } }
    }
  }
  private var calls = 0
  private lateinit var brief: WeatherBrief
  private fun show(sheet: Boolean) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.of("Europe/Rome")
    val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
    val start = tomorrow.atTime(7, 20).atZone(zone).toInstant().toEpochMilli()
    val end = tomorrow.atTime(14, 20).atZone(zone).toInstant().toEpochMilli()
    val source = WeatherEvidence("open-meteo", WeatherEvidenceType.MODEL,
      downloadedAtMillis = now, sourceTimestampMillis = now, effectiveInterval = WeatherInterval(start, end),
      temporalResolutionMillis = 3_600_000, precipitationState = state)
    val partial = state == PrecipitationState.UNCERTAIN || state == PrecipitationState.UNAVAILABLE
    val routine = RoutineWindowAssessment(RoutineOccurrence(RoutineWindow("school", "School",
      setOf(tomorrow.dayOfWeek), LocalTime.of(7, 20), LocalTime.of(14, 20)),
      WeatherInterval(start, end), ResolvedWeatherZone(zone, WeatherZoneSource.PLACE)),
      now, state, coveredMillis = if (partial) 3_600_000 else end - start,
      maxProbabilityPercent = 33.0, minTemperatureC = 13.0, maxTemperatureC = 15.0,
      evidence = listOf(source), limits = if (partial) setOf(WeatherEvidenceLimit.PARTIAL_COVERAGE) else emptySet())
    brief = WeatherBrief(LocalPrecipitationAssessment(state, now, evidence = listOf(source)),
      current = CurrentWeatherSummary(WeatherKind.PARTLY_CLOUDY, 13.0, listOf(source)), routines = listOf(routine))
    compose.setContent {
      val base = LocalContext.current
      val configuration = remember { Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) } }
      val context = remember { base.createConfigurationContext(configuration) }
      val device = LocalDensity.current
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val target = device.density * minOf(maxWidth.value / width, maxHeight.value / 900f)
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
          LocalDensity provides Density(target, font)) {
          FluidTheme(settings = EngineSettings()) {
            val host = rememberFluidGlassModalHostState()
            val backdrop = rememberGlassBackdrop()
            CompositionLocalProvider(LocalFluidGlassModalHostState provides host, LocalFluidCanvasBackdrop provides backdrop) {
              Box(Modifier.requiredWidth(width.dp).height(900.dp)) {
                if (sheet) {
                  HomeSummarySheet(true, brief, "Roma", WeatherSummaryGateway {
                    calls++; WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("routine_0", "current"))
                  }, true, { 0 }, {}, {})
                  FluidGlassModalHost(state = host, backdrop = backdrop)
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                  HomeRoutineWindows(listOf(routine)) {}
                }
              }
            }
          }
        }
      }
    }
  }

  private fun assertCompact() {
    compose.onNodeWithTag("moment_metadata_school").assertDoesNotExist()
    compose.onNodeWithText("open-meteo", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Europe/Rome", substring = true).assertDoesNotExist()
    compose.onNodeWithText("33%").assertExists()
    compose.onNodeWithText(if (language == "it") "Campioni" else "Samples").assertExists()
    compose.onNodeWithText(if (language == "it") "Domani" else "Tomorrow").assertExists()
    var checkedLayouts = 0
    compose.onAllNodes(hasText("School") or hasText("33%") or hasText("13°–15°")).fetchSemanticsNodes().forEach { node ->
      val layouts = mutableListOf<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
      layouts.forEach { checkedLayouts++; assertFalse(it.didOverflowWidth); assertFalse(it.didOverflowHeight) }
    }
    assertTrue("Inspect actual text layout results", checkedLayouts > 0)
    val card = compose.onNodeWithTag("moment_school").fetchSemanticsNode().boundsInRoot
    val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
    assertTrue("Default personal window must remain compact", card.height < viewport.height * 0.65f)
  }

  @Test fun summaryIsBoundedAndDisclosureRetainsExactMetadataAfterSelection() {
    show(true)
    assertCompact()
    compose.onNodeWithTag("weather_summary_status").assertDoesNotExist()
    compose.onNodeWithTag("weather_summary_request").performScrollTo().performClick()
    compose.waitUntil { calls == 1 }
    compose.onNodeWithTag("weather_summary_details").performScrollTo().performClick()
    compose.onNodeWithTag("weather_summary_sources").assertTextEquals("open-meteo")
    compose.onNodeWithTag("weather_summary_complete").performScrollTo().performClick()
    compose.onNodeWithTag("weather_summary_metadata").assertTextContains("Europe/Rome", substring = true)
      .assertTextContains("open-meteo", substring = true)
    compose.onNodeWithTag("weather_summary_request").performScrollTo().assertIsDisplayed()
  }

  @Test fun momentDisclosesOriginalIntervalAndSampleUncertainty() {
    show(false)
    assertCompact()
    compose.onNodeWithTag("moment_details_school").performScrollTo().performClick()
    compose.onNodeWithTag("moment_metadata_school").assertExists()
    compose.onNodeWithText("Europe/Rome", substring = true).assertExists()
    compose.onNodeWithText("open-meteo", substring = true).assertExists()
    compose.onNodeWithText(if (language == "it") "estremi" else "extrema", substring = true).assertExists()
    if (state == PrecipitationState.UNCERTAIN || state == PrecipitationState.UNAVAILABLE)
      compose.onNodeWithText(if (language == "it") "Parziale" else "Partial").assertExists()
  }
}
