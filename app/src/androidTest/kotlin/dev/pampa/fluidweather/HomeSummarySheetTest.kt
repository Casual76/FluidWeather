package dev.pampa.fluidweather

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.feature.home.HomeSummarySheet
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Fake gateway only. Actual formatter/resources/Fluid host; no provider or weather IO. */
class HomeSummarySheetTest {
  @get:Rule val compose = createComposeRule()
  private val place = mutableStateOf("Roma")
  private var settingsOpened = false
  private var calls = 0
  private var input: WeatherSummaryDocument? = null
  private fun show(language: String = "en", scale: Float = 1f, configured: Boolean = true,
    result: suspend (WeatherSummaryDocument) -> WeatherSummaryResult = {
      WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, it.defaultFactIds)
    }) {
    val gateway = WeatherSummaryGateway { document -> calls++; input = document; result(document) }
    compose.setContent {
      val base = LocalContext.current
      val config = remember(language) { Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) } }
      val context = remember(config) { base.createConfigurationContext(config) }
      val density = LocalDensity.current
      CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
        LocalDensity provides Density(density.density, scale)) {
        FluidTheme(settings = EngineSettings()) {
          val host = rememberFluidGlassModalHostState()
          val backdrop = rememberGlassBackdrop()
          var visible by remember { mutableStateOf(true) }
          CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
            Box(Modifier.fillMaxSize()) {
              HomeSummarySheet(visible, null, place.value, gateway, configured, { 0 },
                { visible = false }, { settingsOpened = true })
              FluidGlassModalHost(state = host, backdrop = backdrop)
            }
          }
        }
      }
    }
  }

  @Test fun opensImmediatelyWithoutConfigurationOrGatewayCall() {
    show(configured = false)
    compose.onNodeWithTag("weather_summary_text").assertIsDisplayed().assertTextContains("Roma", substring = true)
    compose.runOnIdle { assertEquals(0, calls) }
    compose.onNodeWithTag("weather_summary_settings").performScrollTo().performClick()
    compose.runOnIdle { assertTrue(settingsOpened); assertEquals(0, calls) }
  }

  @Test fun explicitSelectionUsesCurrentDocumentAndKeepsMandatoryFactsReachable() {
    show()
    compose.runOnIdle { assertEquals(0, calls) }
    compose.onNodeWithTag("weather_summary_request").performScrollTo().performClick()
    compose.waitUntil { calls == 1 }
    compose.onNodeWithTag("weather_summary_text").assertTextEquals("Roma")
    compose.onNodeWithTag("weather_summary_rain").assertTextEquals(input!!.facts.first { it.id == "rain" }.text)
    compose.onNodeWithTag("weather_summary_status").assertTextEquals("AI highlights selected from the current weather facts.")
    compose.onNodeWithTag("weather_summary_details").performScrollTo().performClick()
    compose.onNodeWithTag("weather_summary_complete").performScrollTo().performClick()
    compose.onNodeWithTag("weather_summary_metadata").assertTextEquals(input!!.facts.joinToString("\n") { it.text })
  }

  @Test fun malformedIdsRetainDeterministicFacts() {
    show { WeatherSummaryResult(it.contextKey, WeatherSummaryStatus.READY, listOf("invented-time")) }
    compose.onNodeWithTag("weather_summary_request").performScrollTo().performClick()
    compose.waitUntil { calls == 1 }
    compose.onNodeWithTag("weather_summary_text").assertTextEquals("Roma")
    compose.onNodeWithTag("weather_summary_status").assertTextEquals("Could not select highlights. Your current summary is still available.")
  }

  @Test fun pendingRequestCannotPublishPreviousPlace() {
    val response = CompletableDeferred<WeatherSummaryResult>()
    show { response.await() }
    compose.onNodeWithTag("weather_summary_request").performScrollTo().performClick()
    compose.waitUntil { input != null }
    val old = input!!
    compose.runOnIdle { place.value = "Milano" }
    compose.onNodeWithTag("weather_summary_text").assertTextContains("Milano", substring = true)
    response.complete(WeatherSummaryResult(old.contextKey, WeatherSummaryStatus.READY, old.defaultFactIds))
    compose.waitForIdle()
    compose.onNodeWithTag("weather_summary_text").assertTextContains("Milano", substring = true)
    compose.onNodeWithTag("weather_summary_status").assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, calls) }
  }

  @Test fun italianAtDoubleFontWrapsAndSettingsRemainReachable() {
    show(language = "it", scale = 2f, configured = false)
    compose.onNodeWithText("Riepilogo rapido").assertExists()
    val layouts = mutableListOf<TextLayoutResult>()
    compose.onNodeWithTag("weather_summary_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertTrue(layouts.isNotEmpty())
    assertGlanceTextPaintedFits(layouts.single())
    compose.onNodeWithTag("weather_summary_settings").performScrollTo().assertIsDisplayed().assertTextEquals("Apri impostazioni IA")
    compose.runOnIdle { assertEquals(0, calls) }
  }

  @Test fun englishAtDoubleFontKeepsRequestReachable() {
    show(scale = 2f)
    compose.onNodeWithTag("weather_summary_request").performScrollTo().assertIsDisplayed()
    compose.runOnIdle { assertEquals(0, calls) }
  }
}
