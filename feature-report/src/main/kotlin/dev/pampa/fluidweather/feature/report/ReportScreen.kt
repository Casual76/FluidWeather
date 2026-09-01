package dev.pampa.fluidweather.feature.report

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.fluidweather.core.ui.PlaceholderRow

/** Segnaposto: l'osservazione in due tocchi arriva con la fase 14. */
@Composable
fun ReportScreen(onBack: () -> Unit) {
  FluidScreen(title = "Segnala osservazione", onBack = onBack) {
    item {
      FluidListGroup {
        PlaceholderRow(phase = 14, subtitle = "Che tempo fa da te adesso, in due tocchi")
      }
    }
  }
}
