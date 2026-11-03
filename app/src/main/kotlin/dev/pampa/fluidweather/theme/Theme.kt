package dev.pampa.fluidweather.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.LocalFluidLabels
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.core.ui.rememberFluidWeatherLabels

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
  // Le parole dell'engine (il tasto indietro, l'occhio dei campi segreti, la X delle pagine intere...)
  // sono italiane di serie: l'app e' bilingue, quindi le dice nella sua lingua, una volta alla radice.
  CompositionLocalProvider(LocalFluidLabels provides rememberFluidWeatherLabels()) {
    FluidTheme(
      settings = settings,
      brand = brand ?: WeatherAccent.Amethyst,
      presets = WeatherAccent.palettes,
      content = content,
    )
  }
}
