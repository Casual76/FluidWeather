package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.model.ManualBurst
import dev.pampa.fluidweather.core.model.SampleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * La raffica manuale: 5 minuti a 1 Hz. Vive nello scope dell'applicazione, non della schermata:
 * ruotare il telefono o navigare altrove non butta via i minuti gia' raccolti — e annullarla
 * conserva comunque i campioni gia' archiviati, perche' il motore inserisce lettura per lettura.
 */
class ManualBurstController(
  private val engine: SamplingEngine,
  private val scope: CoroutineScope,
) {

  data class Progress(val completedSeconds: Int, val totalSeconds: Int)

  private val _progress = MutableStateFlow<Progress?>(null)

  /** null = nessuna raffica in corso. */
  val progress: StateFlow<Progress?> = _progress.asStateFlow()

  private var job: Job? = null

  fun start() {
    if (job?.isActive == true) return
    job = scope.launch {
      _progress.value = Progress(0, ManualBurst.DURATION_SECONDS)
      try {
        engine.collect(
          source = SampleSource.MANUAL_BURST,
          durationSeconds = ManualBurst.DURATION_SECONDS,
        ) { completed, total ->
          _progress.value = Progress(completed, total)
        }
      } finally {
        _progress.value = null
      }
    }
  }

  fun cancel() {
    job?.cancel()
  }
}
