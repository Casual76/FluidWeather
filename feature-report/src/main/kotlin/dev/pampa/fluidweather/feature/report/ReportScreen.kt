package dev.pampa.fluidweather.feature.report

import androidx.compose.runtime.Composable
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.PlaceholderNote

/**
 * Segnaposto: l'osservazione in due tocchi arriva con la fase 14. Gia' nella sua forma
 * definitiva pero': un foglio nero a tutta altezza dal basso (decisione del 2026-09-02).
 */
@Composable
fun ReportSheet(open: Boolean, onDismiss: () -> Unit) {
  if (!open) return
  BlackSheet(title = "Segnala osservazione", onDismiss = onDismiss) {
    PlaceholderNote(
      phase = 14,
      subtitle = "Che tempo fa da te adesso, in due tocchi: geolocalizzata, con orario, " +
        "alimenta la calibrazione del motore.",
    )
  }
}
