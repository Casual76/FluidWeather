package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.LearningStore
import dev.pampa.fluidweather.nowcast.learning.PlattRefit
import dev.pampa.fluidweather.nowcast.learning.PlattRefitPolicy
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il tramite fra le regole pure ([PlattRefitPolicy]) e il disco: legge le emissioni e gli esiti,
 * chiede la ristima e la salva. Tutta la decisione sta nella politica; qui c'e' solo l'I/O.
 *
 * Un mutex proprio serializza le ristime: il ciclo in background e l'avvio dell'app possono
 * arrivare insieme, e due ristime parallele scriverebbero due volte la stessa cosa. La scrittura
 * passa dal [DataWipeGuard]: se nel frattempo l'utente ha cancellato i dati, la ristima fatta su
 * dati che non esistono piu' non deve riportarne le tracce sul disco.
 */
class PlattRefitter(
  private val learningRepository: LearningRepository,
  private val learningStore: LearningStore,
  /** Dopo un azzeramento o una ristima: chi ha in cache lo stato dell'apprendimento lo deve buttare. */
  private val onRefit: () -> Unit = {},
  private val wipeGuard: DataWipeGuard = DataWipeGuard(),
  private val clock: () -> Long = System::currentTimeMillis,
  /** Il calcolo (mille ricampionamenti per mappa) sta fuori dal thread principale. */
  private val computeContext: CoroutineContext = Dispatchers.Default,
) {

  private val mutex = Mutex()

  /**
   * All'avvio: se le mappe sul disco sono di un altro modello o di altre regole, si azzerano.
   * Vero se ha azzerato. Non aspetta la prima ristima: un'app appena aggiornata non deve correggere
   * i verdetti del modello nuovo con le mappe del vecchio nemmeno per sei ore.
   */
  suspend fun ensureVersion(): Boolean {
    val generation = wipeGuard.generation()
    val cleared = wipeGuard.writeIfUnchanged(generation) {
      learningStore.clearIfVersionMismatch(PlattRefitPolicy.VERSION)
    } ?: false
    if (cleared) onRefit()
    return cleared
  }

  /**
   * Ristima se e' ora ([PlattRefitPolicy.isDue]); null se non lo era o se i dati sono stati
   * cancellati mentre si calcolava. La ristima sostituisce TUTTE le mappe.
   */
  suspend fun runIfDue(nowMillis: Long = clock()): PlattRefit? = mutex.withLock {
    ensureVersion()
    val snapshot = learningStore.snapshot()
    if (!PlattRefitPolicy.isDue(nowMillis, snapshot.lastFitMillis, snapshot.version)) return@withLock null

    val generation = wipeGuard.generation()
    val since = nowMillis - LearningRepository.KEEP_MILLIS
    val issues = learningRepository.issuesSince(since)
    val outcomes = learningRepository.outcomesSince(since)
    val refit = withContext(computeContext) { PlattRefitPolicy.refit(issues, outcomes, nowMillis) }
    val saved = wipeGuard.writeIfUnchanged(generation) {
      learningStore.replaceAll(refit.version, refit.records(), nowMillis)
    }
    if (saved == null) return@withLock null
    onRefit()
    refit
  }
}
