package dev.pampa.fluidweather.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme

/**
 * Ametista: il colore del marchio finche' il meteo non ne detta uno.
 *
 * Il piano prevede come default un accento derivato dalle condizioni attuali della posizione; quel
 * derivatore arriva con la scena meteo. Prima di allora — e come base della palette scelta a mano —
 * l'app e' color ametista, con la coppia chiaro/scuro separata perche' lo stesso viola non regge
 * su entrambi gli sfondi.
 */
private val AmethystBrand = AccentPreset(
  name = "amethyst",
  label = "Ametista",
  light = Color(0xFF8C52D9),
  dark = Color(0xFFB88CF2),
)

@Composable
fun FluidWeatherTheme(
  settings: EngineSettings = EngineSettings(),
  /** L'accento derivato dal meteo attuale (default del piano); null = marchio ametista. */
  brand: AccentPreset? = null,
  content: @Composable () -> Unit,
) {
  FluidTheme(
    settings = settings,
    brand = brand ?: AmethystBrand,
    content = content,
  )
}
