package dev.pampa.fluidweather.theme

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.ui.WeatherAccent

/**
 * Il tema dell'app: le impostazioni persistite dell'engine (tema delle pagine, modo dell'accento,
 * Material You, palette) e il marchio del momento — l'accento derivato dal meteo quando c'e',
 * ametista altrimenti. Le palette scelte a mano sono quelle di [WeatherAccent.palettes].
 */
@Composable
fun FluidWeatherTheme(
  settings: EngineSettings = EngineSettings(),
  /** L'accento derivato dal meteo attuale (default del piano); null = marchio ametista. */
  brand: AccentPreset? = null,
  content: @Composable () -> Unit,
) {
  FluidTheme(
    settings = settings,
    brand = brand ?: WeatherAccent.Amethyst,
    presets = WeatherAccent.palettes,
    content = content,
  )
}
