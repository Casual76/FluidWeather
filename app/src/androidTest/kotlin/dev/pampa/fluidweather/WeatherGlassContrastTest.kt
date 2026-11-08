package dev.pampa.fluidweather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.fluidweather.core.ui.GlassTile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The reported failure was default black Text inherited from a light application theme. */
class WeatherGlassContrastTest {
  @get:Rule val compose = createComposeRule()

  @Test(timeout = 60_000) fun defaultAndExplicitTextArePaintedLightOverWeatherGlassInLightTheme() {
    compose.setContent {
      MaterialTheme(colorScheme = lightColorScheme()) {
        val backdrop = rememberGlassBackdrop()
        Box {
          Box(Modifier.fillMaxSize().background(Color(0xFF304860)).glassBackdropSource(backdrop))
          CompositionLocalProvider(LocalFluidCanvasBackdrop provides backdrop) {
            GlassTile {
              Text("Default weather text")
              Text("Explicit weather text", color = MaterialTheme.colorScheme.onSurface)
            }
          }
        }
      }
    }
    listOf("Default weather text", "Explicit weather text").forEach { label ->
      val pixels = compose.onNodeWithText(label).captureToImage().toPixelMap()
      var lightGlyphs = 0
      for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
        val p = pixels[x, y]
        if (p.alpha > 0.9f && p.red > 0.85f && p.green > 0.85f && p.blue > 0.85f) lightGlyphs++
      }
      assertTrue("$label lost its light foreground over the weather canvas", lightGlyphs > 20)
    }
  }
}
