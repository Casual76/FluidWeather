package dev.pampa.fluidweather

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.pages.NowcastPage
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.Factor
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import dev.pampa.fluidweather.strings.R
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Exercises the production Nowcast page with synthetic verdict input. These percentages and
 * factors are fixtures, not sensor readings or model predictions. No device sensor, collection
 * pipeline, provider accuracy or forecast performance is established by these UI checks.
 */
class NowcastQualityTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var resources: Resources

  @Test(timeout = 60_000)
  fun quietResultAndThreeWindowsPrecedeExpandableEnglishDiagnostics() = verifyPage("en")

  @Test(timeout = 60_000)
  fun quietResultAndThreeWindowsPrecedeExpandableItalianDiagnostics() = verifyPage("it")

  private fun verifyPage(language: String) {
    val synthetic = HomeUiState(
      loading = false,
      locationName = "Synthetic UI fixture",
      barometerApplies = true,
      sensorAvailable = true,
      verdict = NowcastVerdict(
        level = AlertLevel.QUIETE,
        windows = listOf(
          WindowVerdict("0-1h", .06, .06, .06, listOf(Factor("tendenza-1h", .4))),
          WindowVerdict("1-3h", .16, .16, .17, emptyList()),
          WindowVerdict("3-6h", .22, .21, .22, emptyList()),
        ),
      ),
    )
    compose.setContent {
      val original = LocalContext.current
      val configuration = remember(language) {
        Configuration(original.resources.configuration).apply { setLocale(Locale(language)) }
      }
      val localized = remember(configuration) { original.createConfigurationContext(configuration) }
      resources = localized.resources
      val device = LocalDensity.current
      // Fit a fixed reading viewport on either a phone emulator or the physical tablet.
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = device.density * minOf(maxWidth.value / 360f, maxHeight.value / 900f)
        CompositionLocalProvider(
          LocalContext provides localized,
          LocalConfiguration provides configuration,
          LocalDensity provides Density(fit, 1f),
        ) {
          FluidTheme(settings = EngineSettings(themeMode = ThemeMode.DARK)) {
            Column(Modifier.requiredWidth(360.dp).height(900.dp).verticalScroll(rememberScrollState())) {
              NowcastPage(synthetic)
            }
          }
        }
      }
    }

    // No scroll or tap is needed to read the result and all three windows.
    compose.onNodeWithText(resources.getString(R.string.level_quiet)).assertIsDisplayed()
    listOf("6%", "16%", "22%").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    listOf(R.string.window_0_1, R.string.window_1_3, R.string.window_3_6).forEach {
      compose.onNodeWithText(resources.getString(it)).assertIsDisplayed()
    }
    compose.onNodeWithTag("home_rain_overview").assertDoesNotExist()

    val factor = resources.getString(R.string.feature_trend_1h)
    val ensembleMethod = resources.getString(R.string.nowcast_band_note)
    val sourceMetadata = resources.getString(R.string.accessibility_diagnostic)
    compose.onNodeWithText(factor).assertDoesNotExist()
    compose.onNodeWithText(ensembleMethod).assertDoesNotExist()
    compose.onNodeWithText(sourceMetadata).assertDoesNotExist()

    val factorsToggle = compose.onNodeWithText(resources.getString(R.string.nowcast_factors_title))
    factorsToggle.performScrollTo().performClick()
    compose.onNodeWithText(ensembleMethod).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(factor).performScrollTo().assertIsDisplayed()
    factorsToggle.performScrollTo().performClick()
    compose.onNodeWithText(factor).assertDoesNotExist()

    compose.onNodeWithText(resources.getString(R.string.quality_method_sources))
      .performScrollTo().performClick()
    compose.onNodeWithText(sourceMetadata).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("home_rain_overview").assertDoesNotExist()
  }
}
