package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.ui.PlaceholderRow

/**
 * L'indice: ogni categoria e' una schermata separata. Esistono la diagnostica (fase 1) e le
 * notifiche (fase 11); le altre voci arrivano con la fase 15.
 */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  onOpenNotifications: () -> Unit,
) {
  FluidScreen(title = "Impostazioni", onBack = onBack) {
    item {
      FluidListGroup {
        FluidListRow(
          title = "Diagnostica barometro",
          subtitle = "Segnale grezzo, modalita' di campionamento, raffica manuale",
          onClick = onOpenDiagnostics,
        )
        FluidListDivider()
        FluidListRow(
          title = "Notifiche",
          subtitle = "Allerta del barometro, pioggia in arrivo, allerte ufficiali, riepilogo",
          onClick = onOpenNotifications,
        )
        FluidListDivider()
        PlaceholderRow(phase = 15, subtitle = "Motore, provider, aspetto, dati")
      }
    }
  }
}
