package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

/**
 * L'indice delle impostazioni (fase 15): ogni categoria e' una schermata separata, come vuole
 * il piano — Motore e accuratezza · Provider e chiavi · Notifiche · Aspetto · Diagnostica
 * barometro · Dati e privacy · Informazioni e aggiornamento. Unita' (fase 17) sta con Aspetto.
 */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenEngine: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenAi: () -> Unit,
  onOpenNotifications: () -> Unit,
  onOpenAppearance: () -> Unit,
  onOpenUnits: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  onOpenData: () -> Unit,
  onOpenAbout: () -> Unit,
) {
  FluidScreen(title = stringResource(R.string.settings_title), onBack = onBack) {
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.engine_title),
          subtitle = stringResource(R.string.settings_engine_desc),
          onClick = onOpenEngine,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.prov_title),
          subtitle = stringResource(R.string.settings_providers_desc),
          onClick = onOpenProviders,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.ai_title),
          subtitle = stringResource(R.string.ai_settings_desc),
          onClick = onOpenAi,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.notif_title),
          subtitle = stringResource(R.string.settings_notifications_desc),
          onClick = onOpenNotifications,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.appear_title),
          subtitle = stringResource(R.string.settings_appearance_desc),
          onClick = onOpenAppearance,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.units_title),
          subtitle = stringResource(R.string.settings_units_desc),
          onClick = onOpenUnits,
        )
      }
    }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.diag_title),
          subtitle = stringResource(R.string.settings_diagnostics_desc),
          onClick = onOpenDiagnostics,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.data_title),
          subtitle = stringResource(R.string.settings_data_desc),
          onClick = onOpenData,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_about),
          subtitle = stringResource(R.string.settings_about_desc),
          onClick = onOpenAbout,
        )
      }
    }
  }
}
