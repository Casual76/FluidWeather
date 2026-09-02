package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * L'indice delle impostazioni (fase 15): ogni categoria e' una schermata separata, come vuole
 * il piano — Motore e accuratezza · Provider e chiavi · Notifiche · Aspetto · Diagnostica
 * barometro · Dati e privacy · Informazioni e aggiornamento.
 */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenEngine: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenNotifications: () -> Unit,
  onOpenAppearance: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  onOpenData: () -> Unit,
  onOpenAbout: () -> Unit,
) {
  FluidScreen(title = "Impostazioni", onBack = onBack) {
    item {
      FluidListGroup {
        FluidListRow(
          title = "Motore e accuratezza",
          subtitle = "Taratura, modalita' di campionamento, a che punto e' il barometro",
          onClick = onOpenEngine,
        )
        FluidListDivider()
        FluidListRow(
          title = "Provider e chiavi",
          subtitle = "La costellazione, le chiavi personali, l'override",
          onClick = onOpenProviders,
        )
        FluidListDivider()
        FluidListRow(
          title = "Notifiche",
          subtitle = "Allerta del barometro, pioggia in arrivo, allerte ufficiali, riepilogo",
          onClick = onOpenNotifications,
        )
        FluidListDivider()
        FluidListRow(
          title = "Aspetto",
          subtitle = "Colore, tema delle pagine, vetro adattivo",
          onClick = onOpenAppearance,
        )
      }
    }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Diagnostica barometro",
          subtitle = "Segnale grezzo e pulito, verdetto, raffica manuale, giro dei provider",
          onClick = onOpenDiagnostics,
        )
        FluidListDivider()
        FluidListRow(
          title = "Dati e privacy",
          subtitle = "Cosa c'e' sul telefono, esportazione CSV, cancellazione",
          onClick = onOpenData,
        )
        FluidListDivider()
        FluidListRow(
          title = "Informazioni e aggiornamento",
          subtitle = "Versioni, fonti dei dati, licenze, presentazione",
          onClick = onOpenAbout,
        )
      }
    }
  }
}
