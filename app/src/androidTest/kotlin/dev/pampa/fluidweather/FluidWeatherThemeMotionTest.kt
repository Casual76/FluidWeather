package dev.pampa.fluidweather

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.fluidweather.theme.FluidWeatherTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The app root must derive the engine policy from Compose's system duration scale. */
@OptIn(ExperimentalTestApi::class)
class FluidWeatherThemeMotionTest {
  @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
    override val scaleFactor = 0f
  })

  @Test fun disabledSystemMotionReachesTheAppContent() {
    var reduced = false
    var decorative = true
    compose.setContent {
      FluidWeatherTheme {
        reduced = LocalFluidMotionPolicy.current.reducedMotion
        decorative = LocalFluidMotionPolicy.current.allowDecorativeMotion
      }
    }
    compose.runOnIdle {
      assertTrue(reduced)
      assertFalse(decorative)
    }
  }
}
