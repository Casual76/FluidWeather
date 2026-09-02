package dev.pampa.fluidweather.core.ui

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

/**
 * La riga che ogni rotta non ancora costruita mostra al suo posto, con il numero della fase che la
 * costruira'. Sparisce dal progetto quando l'ultima pagina segnaposto viene sostituita.
 */
@Composable
fun PlaceholderRow(phase: Int, subtitle: String) {
  FluidListRow(
    title = stringResource(R.string.placeholder_phase, phase),
    subtitle = subtitle,
  )
}

/** Lo stesso segnaposto, dentro un foglio nero (Benchmark e Segnalazione ci vivono). */
@Composable
fun PlaceholderNote(phase: Int, subtitle: String) {
  BlackSheetSectionTitle(stringResource(R.string.placeholder_phase, phase))
  BlackSheetNote(subtitle)
}
