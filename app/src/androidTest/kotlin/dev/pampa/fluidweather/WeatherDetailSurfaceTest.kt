package dev.pampa.fluidweather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.LocalFluidSurfaceSide
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.BlackSheetColor
import dev.pampa.fluidweather.core.ui.BlackSheetLazy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Real modal windows and Fluid controls, without weather, sensor or network dependencies. */
class WeatherDetailSurfaceTest {
  @get:Rule val compose = createComposeRule()
  private val sky = mutableStateOf(Color.Red)
  private var home: GlassBackdropState? = null
  private var sheetCanvas: GlassBackdropState? = null
  private var sheetChrome: GlassBackdropState? = null
  private var sheetDark: Boolean? = null
  private var sheetSurface = Color.Unspecified

  @Test(timeout = 60_000)
  fun regularSheetOwnsItsMaterialAndDoesNotRefractChangingHomeSky() = verifySurface(lazy = false)

  @Test(timeout = 60_000)
  fun lazySheetOwnsItsMaterialAndDoesNotRefractChangingHomeSky() = verifySurface(lazy = true)

  private fun verifySurface(lazy: Boolean) {
    compose.setContent {
      MaterialTheme(colorScheme = lightColorScheme()) {
        val homeMaterial = rememberGlassBackdrop()
        SideEffect { home = homeMaterial }
        Box {
          Box(Modifier.fillMaxSize().glassBackdropSource(homeMaterial).background(sky.value))
          CompositionLocalProvider(
            LocalFluidCanvasBackdrop provides homeMaterial,
            LocalGlassBackdrop provides homeMaterial,
          ) {
            if (lazy) BlackSheetLazy(title = "Pressure", onDismiss = {}) {
              item { Controls() }
            } else BlackSheet(title = "Pressure", onDismiss = {}) { Controls() }
          }
        }
      }
    }
    compose.mainClock.advanceTimeBy(1_000)
    compose.waitForIdle()
    compose.runOnIdle {
      assertNotNull(sheetCanvas)
      assertNotSame("The modal inherited Home's ambient recording", home, sheetCanvas)
      assertNotSame("The modal inherited Home's chrome recording", home, sheetChrome)
      assertSame("The two engine channels must refer to the same local scene", sheetCanvas, sheetChrome)
      assertEquals(true, sheetDark)
      assertEquals(BlackSheetColor, sheetSurface)
    }
    val selectedBefore = compose.onNodeWithText("12 h").captureToImage()
    val otherBefore = compose.onNodeWithText("24 h").captureToImage()
    assertLightGlyphs(selectedBefore)
    assertLightGlyphs(otherBefore)
    compose.runOnIdle { sky.value = Color.Green }
    compose.mainClock.advanceTimeBy(500)
    compose.waitForIdle()
    assertStable(selectedBefore, compose.onNodeWithText("12 h").captureToImage())
    assertStable(otherBefore, compose.onNodeWithText("24 h").captureToImage())
    compose.onNodeWithText("24 h").performClick().assertIsSelected()
  }

  @Composable
  private fun Controls() {
    val canvas = LocalFluidCanvasBackdrop.current
    val chrome = LocalGlassBackdrop.current
    val dark = LocalFluidSurfaceSide.current
    val surface = MaterialTheme.colorScheme.surface
    SideEffect {
      sheetCanvas = canvas
      sheetChrome = chrome
      sheetDark = dark
      sheetSurface = surface
    }
    var selected by remember { mutableStateOf("12 h") }
    Row {
      listOf("12 h", "24 h").forEach { label ->
        FluidChip(label, selected == label, onClick = { selected = label })
      }
    }
  }

  private fun assertLightGlyphs(image: ImageBitmap) {
    val pixels = image.toPixelMap()
    var light = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      val p = pixels[x, y]
      if (p.alpha > .95f && p.red > .85f && p.green > .85f && p.blue > .85f) light++
    }
    assertTrue("Fluid chip glyphs should remain opaque and readable on the dark sheet", light > 20)
  }

  private fun assertStable(before: ImageBitmap, after: ImageBitmap) {
    assertEquals(before.width, after.width)
    assertEquals(before.height, after.height)
    val a = before.toPixelMap()
    val b = after.toPixelMap()
    var change = 0.0
    for (y in 0 until a.height) for (x in 0 until a.width) {
      change += abs(a[x, y].red - b[x, y].red) +
        abs(a[x, y].green - b[x, y].green) + abs(a[x, y].blue - b[x, y].blue)
    }
    val mean = change / (a.width * a.height * 3)
    assertTrue("Changing the parent sky changed the sheet control (mean RGB delta $mean)", mean < .025)
  }
}
