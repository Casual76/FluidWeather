package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.fluidweather.core.ui.PlaceholderRow

/** Segnaposto: l'indice a categorie in schermate separate arriva con la fase 15. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
  FluidScreen(title = "Impostazioni", onBack = onBack) {
    item {
      FluidListGroup {
        PlaceholderRow(phase = 15, subtitle = "Motore, provider, notifiche, aspetto, diagnostica")
      }
    }
  }
}
