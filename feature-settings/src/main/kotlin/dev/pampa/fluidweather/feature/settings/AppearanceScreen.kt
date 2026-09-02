package dev.pampa.fluidweather.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.data.AppearanceSettingsStore
import dev.pampa.fluidweather.core.model.AppearanceSettings
import dev.pampa.fluidweather.core.model.GlassLevel
import dev.pampa.fluidweather.core.ui.WeatherAccent
import kotlinx.coroutines.launch

/** Tutto quello che la categoria "Aspetto" tocca. */
class AppearanceDependencies(
  val engineSettings: EngineSettingsStore,
  val appearanceStore: AppearanceSettingsStore,
)

/**
 * Aspetto (fase 15): il colore (accento dal meteo, Material You, o una palette con ametista
 * come base — il piano), il tema delle pagine (la home la comanda il cielo), e il vetro
 * adattivo con le tre scelte dell'onboarding.
 */
@Composable
fun AppearanceScreen(deps: AppearanceDependencies, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  val engine by deps.engineSettings.settings.collectAsState(initial = EngineSettings())
  val appearance by deps.appearanceStore.settings.collectAsState(initial = AppearanceSettings())

  FluidScreen(title = "Aspetto", onBack = onBack) {
    item { FluidSectionHeader(title = "Colore") }
    item {
      FluidListGroup {
        AccentRow(
          title = "Dal meteo",
          subtitle = "L'accento segue le condizioni attuali della posizione (il default del piano)",
          selected = engine.accentMode == AccentMode.BRAND,
          onClick = { scope.launch { deps.engineSettings.setAccentMode(AccentMode.BRAND) } },
        )
        FluidListDivider()
        AccentRow(
          title = "Material You",
          subtitle = "I colori dello sfondo del telefono (Android 12 e oltre)",
          selected = engine.accentMode == AccentMode.DYNAMIC,
          onClick = {
            scope.launch {
              deps.engineSettings.setDynamicColorEnabled(true)
              deps.engineSettings.setAccentMode(AccentMode.DYNAMIC)
            }
          },
        )
        FluidListDivider()
        AccentRow(
          title = "Una palette",
          subtitle = "Un colore scelto da te, ametista come base",
          selected = engine.accentMode == AccentMode.CUSTOM_PRESET,
          onClick = { scope.launch { deps.engineSettings.setAccentMode(AccentMode.CUSTOM_PRESET) } },
        )
        if (engine.accentMode == AccentMode.CUSTOM_PRESET) {
          FluidListDivider()
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 10.dp),
          ) {
            WeatherAccent.palettes.forEach { preset ->
              FluidChip(
                label = preset.label,
                selected = engine.customAccentName == preset.name,
                onClick = { scope.launch { deps.engineSettings.setCustomAccent(preset.name) } },
                leading = {
                  Box(
                    Modifier
                      .size(14.dp)
                      .background(preset.dark, FluidCapsuleShape),
                  )
                },
              )
            }
          }
        }
      }
    }

    item { FluidSectionHeader(title = "Tema delle pagine") }
    item {
      FluidListGroup {
        listOf(
          ThemeMode.SYSTEM to "Come il sistema",
          ThemeMode.LIGHT to "Chiaro",
          ThemeMode.DARK to "Scuro",
        ).forEachIndexed { index, (mode, label) ->
          if (index > 0) FluidListDivider()
          AccentRow(
            title = label,
            subtitle = if (mode == ThemeMode.SYSTEM) "Impostazioni, radar e fogli seguono il telefono" else "",
            selected = engine.themeMode == mode || (mode == ThemeMode.DARK && engine.themeMode == ThemeMode.AMOLED),
            onClick = { scope.launch { deps.engineSettings.setThemeMode(mode) } },
          )
        }
        FluidListDivider()
        FluidListRow(
          title = "Nero puro",
          subtitle = "Superfici nere nel tema scuro (AMOLED)",
          badge = {
            FluidSwitch(
              checked = engine.amoledEnabled,
              onCheckedChange = { scope.launch { deps.engineSettings.setAmoledEnabled(it) } },
            )
          },
        )
      }
    }
    item {
      Text(
        "La home la comanda il cielo: e' sempre in tema scuro sopra la scena, qualunque tema scelga il telefono.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    item { FluidSectionHeader(title = "Vetro adattivo") }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Decidi tu automaticamente",
          subtitle = "Il livello lo sceglie il telefono in base alla sua potenza",
          badge = {
            FluidSwitch(
              checked = appearance.autoGlass,
              onCheckedChange = { scope.launch { deps.appearanceStore.setAutoGlass(it) } },
            )
          },
        )
        GlassLevel.entries.forEach { level ->
          FluidListDivider()
          FluidListRow(
            title = level.label(),
            subtitle = level.description(),
            badge = if (!appearance.autoGlass && appearance.manualLevel == level) {
              { Icon(Icons.Rounded.Check, contentDescription = "Scelto", tint = MaterialTheme.colorScheme.primary) }
            } else {
              null
            },
            onClick = if (appearance.autoGlass) null else ({ scope.launch { deps.appearanceStore.setManualLevel(level) } }),
          )
        }
        FluidListDivider()
        FluidListRow(
          title = "Riduci in risparmio energia",
          subtitle = "Un gradino in meno quando la batteria chiede aiuto",
          badge = {
            FluidSwitch(
              checked = appearance.reduceOnPowerSave,
              onCheckedChange = { scope.launch { deps.appearanceStore.setReduceOnPowerSave(it) } },
            )
          },
        )
      }
    }
  }
}

@Composable
private fun AccentRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    badge = if (selected) {
      { Icon(Icons.Rounded.Check, contentDescription = "Scelto", tint = MaterialTheme.colorScheme.primary) }
    } else {
      null
    },
    onClick = onClick,
  )
}
