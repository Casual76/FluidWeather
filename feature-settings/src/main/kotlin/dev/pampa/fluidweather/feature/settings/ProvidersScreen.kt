package dev.pampa.fluidweather.feature.settings

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
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import kotlinx.coroutines.launch

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

  FluidScreen(title = "Provider e chiavi", onBack = onBack) {
    item { FluidSectionHeader(title = "Chiavi personali") }
    item {
      FluidListGroup {
        ProviderRegistry.all.filter { it.requiresKey }.forEachIndexed { index, descriptor ->
          if (index > 0) FluidListDivider()
          KeyRow(
            label = descriptor.label,
            why = descriptor.why,
            saved = keys[descriptor.id] != null,
            onSave = { key -> scope.launch { deps.providerKeys.set(descriptor.id, key) } },
            onRemove = { scope.launch { deps.providerKeys.set(descriptor.id, null) } },
          )
        }
      }
    }
    item {
      Text(
        "Le chiavi restano nel telefono e viaggiano solo verso il servizio a cui appartengono. " +
          "Con la chiave OpenWeatherMap il radar guadagna i livelli temperatura e vento.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    item { FluidSectionHeader(title = "La costellazione") }
    item {
      FluidListGroup {
        ProviderRegistry.all.forEachIndexed { index, descriptor ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = descriptor.label,
            subtitle = descriptor.why,
            meta = when {
              !descriptor.requiresKey -> "senza chiave"
              keys[descriptor.id] != null -> "chiave ok"
              else -> "serve chiave"
            },
          )
        }
      }
    }

    item { FluidSectionHeader(title = "Override") }
    item {
      FluidListGroup {
        val chosen = onlyProvider
        if (chosen == null) {
          FluidListRow(
            title = "Fusione completa",
            subtitle = "Ogni valore e' la media pesata di chi copre il punto. \"Usa solo questo\" si sceglie dal Benchmark.",
          )
        } else {
          FluidListRow(
            title = "Solo ${ProviderRegistry.all.firstOrNull { it.id == chosen }?.label ?: chosen}",
            subtitle = "Dove arriva; dove non copre, la cascata riprende il volante.",
            badge = {
              FluidButton(
                text = "Torna alla fusione",
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
        placeholder = if (saved) "Chiave salvata · incolla per sostituirla" else "Incolla la chiave",
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      if (input.isNotBlank()) {
        FluidButton(
          text = "Salva",
          style = FluidButtonStyle.Tinted,
          onClick = {
            onSave(input)
            input = ""
          },
        )
      } else if (saved) {
        FluidButton(text = "Rimuovi", style = FluidButtonStyle.Plain, onClick = onRemove)
      }
    }
  }
}
