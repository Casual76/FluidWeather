package dev.pampa.fluidweather.feature.radar

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.fluidweather.core.ui.PlaceholderRow

/** Segnaposto: la mappa vera (livelli, legenda, pin) arriva con la fase 12. */
@Composable
fun RadarScreen(onBack: () -> Unit) {
  FluidScreen(title = "Radar", onBack = onBack) {
    item {
      FluidListGroup {
        PlaceholderRow(phase = 12, subtitle = "Mappa, livelli, legenda, pin delle localita'")
      }
    }
  }
}
