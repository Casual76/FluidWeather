package dev.pampa.fluidweather.feature.benchmark

import androidx.compose.runtime.Composable
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.PlaceholderNote

/**
 * Segnaposto: la classifica dei provider per la zona arriva con la fase 13. Gia' nella sua
 * forma definitiva pero': un foglio nero a tutta altezza dal basso (decisione del 2026-09-02),
 * non una pagina laterale.
 */
@Composable
fun BenchmarkSheet(open: Boolean, onDismiss: () -> Unit) {
  if (!open) return
  BlackSheet(title = "Benchmark", onDismiss = onDismiss) {
    PlaceholderNote(
      phase = 13,
      subtitle = "Classifica dei provider per la zona, errore nel tempo, ripartizione per variabile, " +
        "il barometro locale in classifica alla pari, override da ogni riga.",
    )
  }
}
