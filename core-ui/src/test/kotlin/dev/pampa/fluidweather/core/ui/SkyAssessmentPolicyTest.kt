package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SkyAssessmentPolicyTest {
  @Test fun onlyPresentWetAssessmentAddsRain() {
    val base = SkyState(DayPhase.DAY, WeatherKind.RAIN, null)
    assertEquals(base, base.forRendering()) // legacy widget/preview compatibility
    for (state in listOf(null, PrecipitationState.UNAVAILABLE, PrecipitationState.UNCERTAIN, PrecipitationState.DRY)) {
      assertEquals(WeatherKind.CLOUDY, base.copy(assessmentKnown = true, precipitation = state).forRendering().kind)
    }
    assertEquals(WeatherKind.RAIN, base.copy(kind = null, assessmentKnown = true, precipitation = PrecipitationState.WET).forRendering().kind)
  }
  @Test fun sceneAndColorMotionHaveTheSameVisibilityGate() {
    assertTrue(sceneMotionAllowed(SceneQuality.FULL, true, true, true))
    assertFalse(sceneMotionAllowed(SceneQuality.STATIC, true, true, true))
    assertFalse(sceneMotionAllowed(SceneQuality.REDUCED, false, true, true))
    assertFalse(sceneMotionAllowed(SceneQuality.FULL, true, false, true))
    assertFalse(sceneMotionAllowed(SceneQuality.FULL, true, true, false))
  }
}
