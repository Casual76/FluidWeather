package dev.pampa.fluidweather

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.LocalUnits
import dev.pampa.fluidweather.core.ui.BlackSheetColor
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.pages.HourlyWeatherSheet
import dev.pampa.fluidweather.feature.home.pages.SunPage
import dev.pampa.fluidweather.strings.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production content and a real 360dp Material window, on the device running this suite. */
class WeatherQualityCorrectionTest {
  @get:Rule val compose = createComposeRule()

  @Test fun englishSunDatesUseFullRowsAtDoubleFont() = sunDates("en")
  @Test fun italianSunDatesUseFullRowsAtDoubleFont() = sunDates("it")

  private fun sunDates(language: String) {
    compose.setContent {
      val original = LocalContext.current
      val config = remember { Configuration(original.resources.configuration).apply { setLocale(Locale(language)) } }
      val localized = remember { original.createConfigurationContext(config) }
      val device = LocalDensity.current
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = device.density * minOf(maxWidth.value / 360, maxHeight.value / 900)
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
          LocalDensity provides Density(fit, 2f)) {
          FluidTheme(settings = EngineSettings(themeMode = ThemeMode.DARK)) {
            Column(Modifier.requiredWidth(360.dp).height(900.dp).background(BlackSheetColor).verticalScroll(rememberScrollState())) {
              SunPage(HomeUiState(latitude = 43.83, longitude = 11.21))
            }
          }
        }
      }
    }
    compose.waitUntil(20_000) { compose.onAllNodesWithTag("sun_twelve_label").fetchSemanticsNodes().isNotEmpty() }
    listOf("sun_longest_label", "sun_shortest_label", "sun_twelve_label").forEach { tag ->
      val label = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
      val layouts = mutableListOf<TextLayoutResult>()
      label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertTrue(layouts.isNotEmpty())
      layouts.forEach { result ->
      assertFalse(result.didOverflowHeight)
      assertTrue("A full-width label must not become a vertical sliver", result.lineCount <= 3)
      for (line in 0 until result.lineCount) {
        assertTrue(result.getLineRight(line) <= result.size.width + 1)
        assertFalse(result.isLineEllipsized(line))
      }
      }
    }
    compose.onNodeWithTag("sun_twelve_dates").performScrollTo()
    holdForNativeProof("sun-dates-$language")
  }

  @Test fun realNarrowHourlySheetDistinguishesUnknownZeroTraceAndScrollsAndCloses() = hourly(UnitPreferences.METRIC)
  @Test fun realNarrowHourlySheetPreservesTraceAmountsInImperialUnits() = hourly(UnitPreferences.forCountry("US"))

  private fun hourly(units: UnitPreferences) {
    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val start = System.currentTimeMillis() / 3_600_000L * 3_600_000L
    val deviceZone = java.time.ZoneId.systemDefault()
    val placeZone = if (deviceZone.id == "Pacific/Auckland") "Europe/Rome" else "Pacific/Auckland"
    val brief = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, start,
      place = Place(-88_001, "Synthetic clock fixture", null, -36.85, 174.76, placeZone)))
    val hours = (0 until 80).map { index ->
      FusedHour(start + index * 3_600_000L, buildMap {
        put(FusionVariables.TEMPERATURE, FusedValue(14.0, emptyList()))
        if (index > 0) {
          put(FusionVariables.PRECIP_PROBABILITY, FusedValue(if (index == 1) 0.0 else 1.0, emptyList()))
          put(FusionVariables.PRECIPITATION, FusedValue(if (index == 1) 0.0 else 0.01, emptyList()))
        }
      }, WeatherKind.CLOUDY)
    }
    compose.setContent {
      CompositionLocalProvider(LocalUnits provides units) {
        FluidTheme(settings = EngineSettings(themeMode = ThemeMode.DARK)) {
          var open by remember { mutableStateOf(true) }
          Box(Modifier.fillMaxSize().background(BlackSheetColor)) {
            if (open) HourlyWeatherSheet(HomeUiState(fusedHours = hours, weatherBrief = brief), { open = false }, contentMaxWidth = 360.dp)
          }
        }
      }
    }
    compose.onNodeWithText(context.getString(R.string.quality_device_times, deviceZone.id)).assertIsDisplayed()
    compose.onNodeWithTag("hourly_probability_$start").assertTextEquals("\u2014")
    compose.onNodeWithTag("hourly_amount_$start").assertTextEquals("\u2014")
    compose.onNodeWithTag("hourly_probability_${start + 3_600_000L}").assertTextEquals("0%")
    // Use the configured locale's decimal separator, rather than assuming the device language.
    val formatter = dev.pampa.fluidweather.strings.UnitFormatter(context.resources, units)
    compose.onNodeWithTag("hourly_amount_${start + 3_600_000L}").assertTextEquals(formatter.precipitationValue(0.0))
    compose.onNodeWithTag("hourly_probability_${start + 2 * 3_600_000L}").assertTextEquals("1%")
    val quantumMm = if (units.precipitation == PrecipitationUnit.MM) .1 else .254
    compose.onNodeWithTag("hourly_amount_${start + 2 * 3_600_000L}")
      .assertTextEquals("<" + formatter.precipitationValue(quantumMm))
    val prefix = if (units.precipitation == PrecipitationUnit.MM) "metric" else "imperial"
    holdForNativeProof("hourly-$prefix")
    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("hourly_probability_${start + 70 * 3_600_000L}"))
    compose.onNodeWithTag("hourly_probability_${start + 70 * 3_600_000L}").assertIsDisplayed()
    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("hourly_probability_$start"))
    compose.onNodeWithTag("hourly_probability_$start").assertIsDisplayed()
    compose.onNodeWithContentDescription(context.getString(R.string.common_close)).performClick()
    compose.onNodeWithTag("hourly_probability_$start").assertDoesNotExist()
  }

  private fun holdForNativeProof(name: String) {
    if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("qualityProofPause") == "true" &&
      name in setOf("hourly-metric", "sun-dates-en")) {
      Log.i("FluidQualityProof", "READY:$name")
      Thread.sleep(25_000)
    }
  }
}
