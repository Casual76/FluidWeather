package dev.pampa.fluidweather.feature.benchmark

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.fluidweather.core.ui.PlaceholderRow

/** Segnaposto: la classifica dei provider per la zona arriva con la fase 13. */
@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
  FluidScreen(title = "Benchmark", onBack = onBack) {
    item {
      FluidListGroup {
        PlaceholderRow(phase = 13, subtitle = "Classifica dei provider, errore nel tempo, override")
      }
    }
  }
}
