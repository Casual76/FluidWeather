package dev.pampa.fluidweather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineCompatibility
import dev.antigravity.fluidengine.foundation.EngineConfig
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.fluidweather.feature.settings.AppUpdateRows
import dev.pampa.fluidweather.feature.settings.UpdateDependencies
import dev.pampa.fluidweather.strings.R

/**
 * Il cancello remoto (fase 18): quando il manifest dice che questa build non e' piu' da usare
 * (kill switch) o che l'engine che ha e' sotto il pavimento, l'app non apre la home ma questa
 * pagina, con la frase del manifest e le stesse righe di aggiornamento delle Informazioni. E'
 * l'ultima risorsa che il piano prevede; finche' il manifest tace, non esiste.
 */
sealed interface ReleaseGate {
  data class Suspended(val message: String?) : ReleaseGate
  data object UpdateRequired : ReleaseGate

  companion object {
    fun of(config: EngineConfig): ReleaseGate? = when {
      config.killSwitch.enabled -> Suspended(config.killSwitch.message)
      config.compatibility() == EngineCompatibility.UPDATE_REQUIRED -> UpdateRequired
      else -> null
    }
  }
}

@Composable
fun ReleaseGateScreen(gate: ReleaseGate, updates: UpdateDependencies) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = when (gate) {
        is ReleaseGate.Suspended -> stringResource(R.string.gate_kill_title)
        ReleaseGate.UpdateRequired -> stringResource(R.string.gate_update_required_title)
      },
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = when (gate) {
        is ReleaseGate.Suspended -> gate.message ?: stringResource(R.string.gate_default_kill)
        ReleaseGate.UpdateRequired -> stringResource(R.string.gate_update_required_text)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    FluidListGroup {
      AppUpdateRows(updates)
    }
  }
}
