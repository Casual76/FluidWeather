package dev.pampa.fluidweather.feature.radar

import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode

/** A local map palette must not restore preferences disabled in the application theme. */
internal fun radarThemeSettings(settings: EngineSettings, dark: Boolean): EngineSettings =
  settings.copy(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColorEnabled = false)
