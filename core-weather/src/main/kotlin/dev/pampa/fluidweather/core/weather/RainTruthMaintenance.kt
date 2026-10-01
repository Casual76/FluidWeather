package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.MaintenanceThrottle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La manutenzione della classifica pioggia: giudica le righe mature e tiene pronta la climatologia
 * del posto. Al piu' una volta l'ora.
 *
 * Vive fuori dal giro dei provider apposta: la verita' e' una richiesta a parte, e legarla al giro
 * voleva dire giudicare solo quando l'utente apriva l'app nel posto giusto. La chiamano il ciclo in
 * background (a ogni passata, e il freno la rende gratuita quasi sempre) e l'apertura dell'app.
 *
 * Il freno e' su disco, non in memoria: i worker nascono in processi nuovi, e un "ultima volta"
 * dimenticato a ogni processo non frenerebbe niente. Si segna **prima** del lavoro: un giudizio che
 * si pianta a meta' non deve ripartire a ogni passata del minuto dopo.
 */
class RainTruthMaintenance(
  private val settler: TruthPanelSettler,
  private val climatology: LocalClimatology,
  private val throttle: MaintenanceThrottle,
  /** Il punto dell'istantanea del telefono: e' li' che serve la climatologia. */
  private val homePoint: suspend () -> Pair<Double, Double>?,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private val mutex = Mutex()

  /**
   * Giudica e aggiorna se e' passata almeno un'ora dall'ultima volta (o se l'orologio e' tornato
   * indietro: un "ultima volta" nel futuro non deve bloccare per sempre). True se ha girato.
   */
  suspend fun runIfDue(): Boolean = mutex.withLock {
    val now = clock()
    val last = runCatching { throttle.lastRunMillis() }.getOrNull()
    if (last != null && now - last in 0 until INTERVAL_MILLIS) return@withLock false
    runCatching { throttle.markRun(now) }
    runCatching { settler.settle() }
    runCatching { homePoint() }.getOrNull()?.let { (latitude, longitude) ->
      runCatching { climatology.ensure(latitude, longitude) }
    }
    true
  }

  companion object {
    const val INTERVAL_MILLIS: Long = 3_600_000L
  }
}
