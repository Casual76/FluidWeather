package dev.pampa.fluidweather.feature.appwidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Cosa fa ridisegnare il widget.
 *
 * **La trappola dell'engine**: un widget si ridisegna quando cambiano i *dati*, non quando cambia
 * l'*aspetto*. Senza il ramo delle impostazioni, cambiare accento o tema nell'app non arriva mai
 * alla schermata Home e sembra che l'impostazione non faccia niente. Lo stesso vale per le unita':
 * chi passa da °C a °F si aspetta che il widget lo segua, e senza collettore resterebbe in Celsius
 * fino al prossimo giro del ciclo.
 *
 * Vive qui e non in `:app` perche' e' una cosa del widget; `:app` la chiama e basta.
 */
fun installAppWidgetUpdates(
  context: Context,
  scope: CoroutineScope,
  snapshotUpdates: Flow<Map<String, Long>>,
  engineSettings: Flow<*>,
  unitPreferences: Flow<*>,
) {
  scope.launch {
    merge(
      // I dati: il ciclo ha appena scritto un'istantanea nuova.
      snapshotUpdates.drop(1).map { },
      // L'aspetto.
      engineSettings.distinctUntilChanged().drop(1).map { },
      // Le unita'.
      unitPreferences.distinctUntilChanged().drop(1).map { },
    )
      // Tre sorgenti che possono muoversi insieme (un giro del ciclo mentre si cambia tema): un
      // ridisegno solo, non tre.
      .conflate()
      .collect { runCatching { FluidWeatherAppWidget().updateAll(context) } }
  }
}
