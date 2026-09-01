package dev.pampa.fluidweather.core.ui

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * La riga che ogni rotta non ancora costruita mostra al suo posto, con il numero della fase che la
 * costruira'. Sparisce dal progetto quando l'ultima pagina segnaposto viene sostituita.
 */
@Composable
fun PlaceholderRow(phase: Int, subtitle: String) {
  FluidListRow(
    title = "In costruzione — fase $phase",
    subtitle = subtitle,
  )
}
