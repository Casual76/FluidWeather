package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidGlassButton
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuButton
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuState

/**
 * La barra flottante a tre isole: radar a sinistra, pillola della localita' al centro (lo
 * swipe e l'elenco arrivano con la fase 10), menu' a destra. Il menu' e' un morph di vetro:
 * il tocco lo apre, la pressione lunga pure — nessun gesto nascosto.
 */
@Composable
internal fun HomeFloatingBar(
  locationName: String?,
  backdrop: GlassBackdropState,
  menuState: FluidMorphMenuState,
  onOpenRadar: () -> Unit,
  onOpenBenchmark: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenReport: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val menuActions = {
    listOf(
      FluidContextAction("Benchmark", Icons.Rounded.Insights) { onOpenBenchmark() },
      FluidContextAction("Segnala osservazione", Icons.Rounded.RateReview) { onOpenReport() },
      FluidContextAction("Impostazioni", Icons.Rounded.Settings) { onOpenSettings() },
    )
  }

  Row(
    modifier = modifier.padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FluidGlassIconButton(onClick = onOpenRadar, backdrop = backdrop) {
      Icon(
        imageVector = Icons.Rounded.Radar,
        contentDescription = "Radar",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(20.dp),
      )
    }

    FluidGlassButton(
      text = locationName ?: "La mia posizione",
      onClick = { /* fase 10: espansione in elenco localita' */ },
      backdrop = backdrop,
      modifier = Modifier
        .padding(horizontal = 12.dp)
        .widthIn(min = 150.dp),
    )

    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { menuBounds = it.boundsInRoot() }) {
      FluidMorphMenuButton(
        state = menuState,
        actions = menuActions,
        onClick = {
          menuBounds?.let { bounds ->
            menuState.open(bounds, null, Icons.Rounded.Menu, menuActions())
          }
        },
        icon = Icons.Rounded.Menu,
        backdrop = backdrop,
      )
    }
  }
}
