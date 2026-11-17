package dev.pampa.fluidweather.feature.radar

import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import org.junit.Assert.*
import org.junit.Test

class RadarThemeSettingsTest {
  @Test fun bothMapPalettesRetainDisabledHapticsAndSubsequentPreferenceChanges() {
    for (dark in listOf(false, true)) {
      val disabled = EngineSettings(hapticsEnabled = false, amoledEnabled = true)
      val local = radarThemeSettings(disabled, dark)
      assertFalse(local.hapticsEnabled)
      assertTrue(local.amoledEnabled)
      assertEquals(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, local.themeMode)
      assertTrue(radarThemeSettings(disabled.copy(hapticsEnabled = true), dark).hapticsEnabled)
    }
  }
}
