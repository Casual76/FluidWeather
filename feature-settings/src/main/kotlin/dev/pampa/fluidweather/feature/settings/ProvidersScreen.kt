package dev.pampa.fluidweather.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

/** Tutto quello che la categoria "Provider e chiavi" tocca. */
class ProvidersDependencies(
  val providerKeys: ProviderKeysStore,
  val fusionSettings: FusionSettingsStore,
)

/**
 * Provider e chiavi (fase 15): le chiavi dei servizi a registrazione si inseriscono QUI e
 * vivono solo nel DataStore del telefono — mai nel codice, mai nei log (regola dell'utente,
 * 2026-09-01). La costellazione keyless si vede con il motivo di ogni membro; l'override
 * "usa solo questo" si legge e si toglie, si sceglie dal Benchmark.
 */
@Composable
fun ProvidersScreen(deps: ProvidersDependencies, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  val keys by deps.providerKeys.keys.collectAsState(initial = emptyMap())
  val onlyProvider by deps.fusionSettings.onlyProviderId.collectAsState(initial = null)

  FluidScreen(title = stringResource(R.string.prov_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.prov_keys)) }
    item {
      TutorialSlot(screen = TutorialScreen.PROVIDERS)
      FluidListGroup(modifier = Modifier.fluidTutorialAnchor("providers_keys")) {
        ProviderRegistry.all.filter { it.requiresKey }.forEachIndexed { index, descriptor ->
          if (index > 0) FluidListDivider()
          KeyRow(
            label = descriptor.label,
            why = stringResource(descriptor.whyRes),
            saved = keys[descriptor.id] != null,
            onSave = { key -> scope.launch { deps.providerKeys.set(descriptor.id, key) } },
            onRemove = { scope.launch { deps.providerKeys.set(descriptor.id, null) } },
          )
        }
      }
    }
    item {
      Text(
        stringResource(R.string.prov_keys_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.prov_constellation)) }
    item {
      FluidListGroup {
        ProviderRegistry.all.forEachIndexed { index, descriptor ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = descriptor.label,
            subtitle = stringResource(descriptor.whyRes),
            meta = when {
              !descriptor.requiresKey -> stringResource(R.string.prov_keyless)
              keys[descriptor.id] != null -> stringResource(R.string.prov_key_ok)
              else -> stringResource(R.string.prov_key_needed)
            },
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.prov_override)) }
    item {
      FluidListGroup {
        val chosen = onlyProvider
        if (chosen == null) {
          FluidListRow(
            title = stringResource(R.string.prov_full_fusion),
            subtitle = stringResource(R.string.prov_full_fusion_desc),
          )
        } else {
          FluidListRow(
            title = stringResource(R.string.prov_only, ProviderRegistry.all.firstOrNull { it.id == chosen }?.label ?: chosen),
            subtitle = stringResource(R.string.prov_only_desc),
            badge = {
              FluidButton(
                text = stringResource(R.string.prov_back_to_fusion),
                style = FluidButtonStyle.Tinted,
                onClick = { scope.launch { deps.fusionSettings.setOnlyProvider(null) } },
              )
            },
          )
        }
      }
    }
  }
}

@Composable
private fun KeyRow(label: String, why: String, saved: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit) {
  var input by remember { mutableStateOf("") }
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.padding(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      FluidTextField(
        value = input,
        onValueChange = { input = it.trim() },
        placeholder = if (saved) stringResource(R.string.prov_key_saved) else stringResource(R.string.prov_paste_key),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      if (input.isNotBlank()) {
        FluidButton(
          text = stringResource(R.string.common_save),
          style = FluidButtonStyle.Tinted,
          onClick = {
            onSave(input)
            input = ""
          },
        )
      } else if (saved) {
        FluidButton(text = stringResource(R.string.common_remove), style = FluidButtonStyle.Plain, onClick = onRemove)
      }
    }
  }
}
