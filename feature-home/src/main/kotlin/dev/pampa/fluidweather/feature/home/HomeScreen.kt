package dev.pampa.fluidweather.feature.home

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * Scheletro della fase 0: la home vera (scena meteo, griglia di widget, barra flottante) arriva
 * con la fase 8. Per ora la pagina dimostra tema, componenti e navigazione verso le rotte
 * segnaposto.
 */
@Composable
fun HomeScreen(
  onOpenRadar: () -> Unit,
  onOpenBenchmark: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenReport: () -> Unit,
) {
  FluidScreen(title = "FluidWeather", subtitle = "Fondamenta — fase 0") {
    item { FluidSectionHeader(title = "Scheletro di navigazione") }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Radar",
          subtitle = "Mappa a schermo intero — fase 12",
          onClick = onOpenRadar,
        )
        FluidListDivider()
        FluidListRow(
          title = "Benchmark",
          subtitle = "La classifica dei provider — fase 13",
          onClick = onOpenBenchmark,
        )
        FluidListDivider()
        FluidListRow(
          title = "Segnala osservazione",
          subtitle = "Che tempo fa da te adesso? — fase 14",
          onClick = onOpenReport,
        )
        FluidListDivider()
        FluidListRow(
          title = "Impostazioni",
          subtitle = "Categorie in schermate separate — fase 15",
          onClick = onOpenSettings,
        )
      }
    }
  }
}
