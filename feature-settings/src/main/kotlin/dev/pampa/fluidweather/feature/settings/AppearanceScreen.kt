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
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

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

  FluidScreen(title = stringResource(R.string.appear_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.appear_color)) }
    item {
      FluidListGroup {
        AccentRow(
          title = stringResource(R.string.appear_from_weather),
          subtitle = stringResource(R.string.appear_from_weather_desc),
          selected = engine.accentMode == AccentMode.BRAND,
          onClick = { scope.launch { deps.engineSettings.setAccentMode(AccentMode.BRAND) } },
        )
        FluidListDivider()
        AccentRow(
          title = "Material You",
          subtitle = stringResource(R.string.appear_material_you_desc),
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
          title = stringResource(R.string.appear_palette),
          subtitle = stringResource(R.string.appear_palette_desc),
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
                label = if (preset.name == WeatherAccent.Amethyst.name) stringResource(R.string.accent_amethyst) else preset.label,
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

    item { FluidSectionHeader(title = stringResource(R.string.appear_theme)) }
    item {
      FluidListGroup {
        listOf(
          ThemeMode.SYSTEM to stringResource(R.string.appear_theme_system),
          ThemeMode.LIGHT to stringResource(R.string.appear_theme_light),
          ThemeMode.DARK to stringResource(R.string.appear_theme_dark),
        ).forEachIndexed { index, (mode, label) ->
          if (index > 0) FluidListDivider()
          AccentRow(
            title = label,
            subtitle = if (mode == ThemeMode.SYSTEM) stringResource(R.string.appear_theme_system_desc) else "",
            selected = engine.themeMode == mode || (mode == ThemeMode.DARK && engine.themeMode == ThemeMode.AMOLED),
            onClick = { scope.launch { deps.engineSettings.setThemeMode(mode) } },
          )
        }
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.appear_pure_black),
          subtitle = stringResource(R.string.appear_pure_black_desc),
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
        stringResource(R.string.appear_home_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.appear_glass)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.appear_glass_auto),
          subtitle = stringResource(R.string.appear_glass_auto_desc),
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
              { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_chosen), tint = MaterialTheme.colorScheme.primary) }
            } else {
              null
            },
            onClick = if (appearance.autoGlass) null else ({ scope.launch { deps.appearanceStore.setManualLevel(level) } }),
          )
        }
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.appear_glass_power_save),
          subtitle = stringResource(R.string.appear_glass_power_save_desc),
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
      { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_chosen), tint = MaterialTheme.colorScheme.primary) }
    } else {
      null
    },
    onClick = onClick,
  )
}
