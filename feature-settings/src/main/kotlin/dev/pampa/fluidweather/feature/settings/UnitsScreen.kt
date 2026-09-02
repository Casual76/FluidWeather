package dev.pampa.fluidweather.feature.settings

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.data.UnitsStore
import dev.pampa.fluidweather.core.model.DistanceUnit
import dev.pampa.fluidweather.core.model.PrecipitationUnit
import dev.pampa.fluidweather.core.model.PressureUnit
import dev.pampa.fluidweather.core.model.TemperatureUnit
import dev.pampa.fluidweather.core.model.UnitOverrides
import dev.pampa.fluidweather.core.model.UnitPreferences
import dev.pampa.fluidweather.core.model.WindUnit
import dev.pampa.fluidweather.strings.R
import java.util.Locale
import kotlinx.coroutines.launch

class UnitsDependencies(val unitsStore: UnitsStore)

/**
 * Unita' (fase 17): cinque famiglie, ognuna con "come il paese del telefono" in testa e le
 * unita' una per una sotto. Il default dichiara cosa vale davvero ("Come il paese del telefono
 * (°C)"), cosi' chi non sceglie sa comunque cosa sta leggendo.
 */
@Composable
fun UnitsScreen(deps: UnitsDependencies, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  val overrides by deps.unitsStore.overrides.collectAsState(initial = UnitOverrides())
  val defaults = UnitPreferences.forCountry(Locale.getDefault().country)

  FluidScreen(title = stringResource(R.string.units_title), onBack = onBack) {
    item {
      Text(
        stringResource(R.string.units_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.units_temperature)) }
    item {
      UnitGroup(
        options = TemperatureUnit.entries,
        selected = overrides.temperature,
        default = defaults.temperature,
        name = { stringResource(it.nameRes()) },
        symbol = { stringResource(it.symbolRes()) },
        onSelect = { scope.launch { deps.unitsStore.setTemperature(it) } },
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.units_wind)) }
    item {
      UnitGroup(
        options = WindUnit.entries,
        selected = overrides.wind,
        default = defaults.wind,
        name = { stringResource(it.nameRes()) },
        symbol = { stringResource(it.symbolRes()) },
        onSelect = { scope.launch { deps.unitsStore.setWind(it) } },
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.units_pressure)) }
    item {
      UnitGroup(
        options = PressureUnit.entries,
        selected = overrides.pressure,
        default = defaults.pressure,
        name = { stringResource(it.nameRes()) },
        symbol = { stringResource(it.symbolRes()) },
        onSelect = { scope.launch { deps.unitsStore.setPressure(it) } },
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.units_precipitation)) }
    item {
      UnitGroup(
        options = PrecipitationUnit.entries,
        selected = overrides.precipitation,
        default = defaults.precipitation,
        name = { stringResource(it.nameRes()) },
        symbol = { stringResource(it.symbolRes()) },
        onSelect = { scope.launch { deps.unitsStore.setPrecipitation(it) } },
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.units_distance)) }
    item {
      UnitGroup(
        options = DistanceUnit.entries,
        selected = overrides.distance,
        default = defaults.distance,
        name = { stringResource(it.nameRes()) },
        symbol = { stringResource(it.symbolRes()) },
        onSelect = { scope.launch { deps.unitsStore.setDistance(it) } },
      )
    }
  }
}

/** Una famiglia: la riga "automatico" (null) e poi ogni unita'; il segno di spunta su quella attiva. */
@Composable
private fun <T : Enum<T>> UnitGroup(
  options: List<T>,
  selected: T?,
  default: T,
  name: @Composable (T) -> String,
  symbol: @Composable (T) -> String,
  onSelect: (T?) -> Unit,
) {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.units_default_name),
      subtitle = stringResource(R.string.units_auto, symbol(default)),
      badge = if (selected == null) {
        { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_chosen), tint = MaterialTheme.colorScheme.primary) }
      } else {
        null
      },
      onClick = { onSelect(null) },
    )
    options.forEach { option ->
      FluidListDivider()
      FluidListRow(
        title = name(option),
        subtitle = symbol(option),
        badge = if (selected == option) {
          { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_chosen), tint = MaterialTheme.colorScheme.primary) }
        } else {
          null
        },
        onClick = { onSelect(option) },
      )
    }
  }
}

private fun TemperatureUnit.nameRes(): Int = when (this) {
  TemperatureUnit.CELSIUS -> R.string.unit_name_celsius
  TemperatureUnit.FAHRENHEIT -> R.string.unit_name_fahrenheit
}

private fun TemperatureUnit.symbolRes(): Int = when (this) {
  TemperatureUnit.CELSIUS -> R.string.unit_celsius
  TemperatureUnit.FAHRENHEIT -> R.string.unit_fahrenheit
}

private fun WindUnit.nameRes(): Int = when (this) {
  WindUnit.KMH -> R.string.unit_name_kmh
  WindUnit.MS -> R.string.unit_name_ms
  WindUnit.MPH -> R.string.unit_name_mph
  WindUnit.KNOTS -> R.string.unit_name_knots
  WindUnit.BEAUFORT -> R.string.unit_name_beaufort
}

private fun WindUnit.symbolRes(): Int = when (this) {
  WindUnit.KMH -> R.string.unit_kmh
  WindUnit.MS -> R.string.unit_ms
  WindUnit.MPH -> R.string.unit_mph
  WindUnit.KNOTS -> R.string.unit_knots
  WindUnit.BEAUFORT -> R.string.unit_beaufort
}

private fun PressureUnit.nameRes(): Int = when (this) {
  PressureUnit.HPA -> R.string.unit_name_hpa
  PressureUnit.MBAR -> R.string.unit_name_mbar
  PressureUnit.MMHG -> R.string.unit_name_mmhg
  PressureUnit.INHG -> R.string.unit_name_inhg
}

private fun PressureUnit.symbolRes(): Int = when (this) {
  PressureUnit.HPA -> R.string.unit_hpa
  PressureUnit.MBAR -> R.string.unit_mbar
  PressureUnit.MMHG -> R.string.unit_mmhg
  PressureUnit.INHG -> R.string.unit_inhg
}

private fun PrecipitationUnit.nameRes(): Int = when (this) {
  PrecipitationUnit.MM -> R.string.unit_name_mm
  PrecipitationUnit.INCH -> R.string.unit_name_inch
}

private fun PrecipitationUnit.symbolRes(): Int = when (this) {
  PrecipitationUnit.MM -> R.string.unit_mm
  PrecipitationUnit.INCH -> R.string.unit_inch
}

private fun DistanceUnit.nameRes(): Int = when (this) {
  DistanceUnit.KM -> R.string.unit_name_km
  DistanceUnit.MILES -> R.string.unit_name_mi
}

private fun DistanceUnit.symbolRes(): Int = when (this) {
  DistanceUnit.KM -> R.string.unit_km
  DistanceUnit.MILES -> R.string.unit_mi
}
