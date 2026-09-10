package dev.pampa.fluidweather.feature.settings

import kotlinx.coroutines.delay

/**
 * Quando l'app guarda se c'e' una versione nuova, e quanto insiste.
 *
 * Prima il controllo partiva una volta sola, alla creazione dell'Activity, e con un solo tentativo.
 * Due modi di non vedere mai un aggiornamento, entrambi capitati davvero (1.4.1 e 1.4.2 installate
 * a mano dallo store il 2026-09-10):
 *
 *  - a un avvio a freddo la rete spesso non e' ancora pronta (la radio si sveglia, il GPS e i
 *    provider partono nello stesso istante): la richiesta fallisce in un attimo e il controllo,
 *    che e' silenzioso di proposito, tace per sempre;
 *  - con `launchMode="singleTask"` (dalla 1.4.0) l'Activity non viene piu' ricreata a ogni tocco
 *    sul widget: un'app viva da giorni in background, riaperta dal launcher, **riprende** e non
 *    ricontrolla niente. Prima la seconda home — che era un baco — faceva anche un secondo
 *    controllo, e nessuno se n'era accorto.
 *
 * Da qui: si ricontrolla a ogni ritorno in primo piano, al massimo una volta ogni
 * [recheckAfterMillis]; un tentativo fallito si ripete dopo [retryDelaysMillis]; "piu' tardi" vale
 * per quella versione finche' vive il processo (al prossimo avvio torna), senza che il controllo
 * ripetuto la riproponga ogni sei ore. Pura, cosi' si collauda senza Activity ne' rete.
 */
internal class UpdateCheckPolicy(
  private val recheckAfterMillis: Long = RECHECK_AFTER_MILLIS,
  private val retryDelaysMillis: LongArray = RETRY_DELAYS_MILLIS,
) {
  private var lastAttemptMillis: Long? = null
  private var deferredVersion: String? = null

  /** Vero se, tornando in primo piano adesso, e' ora di guardare di nuovo il manifest. */
  fun shouldCheck(nowMillis: Long): Boolean =
    lastAttemptMillis?.let { nowMillis - it >= recheckAfterMillis } ?: true

  fun markAttempt(nowMillis: Long) {
    lastAttemptMillis = nowMillis
  }

  /** "Piu' tardi": quella versione non si ripropone finche' il processo vive. */
  fun defer(version: String) {
    deferredVersion = version
  }

  fun isDeferred(version: String): Boolean = version == deferredVersion

  /**
   * Esegue [check] finche' non riesce, aspettando fra un tentativo e l'altro. L'ultimo fallimento
   * resta un null: era in sottofondo, e chi apre l'app voleva il meteo.
   */
  suspend fun <T> retrying(check: suspend () -> Result<T?>): T? {
    for (attempt in 0..retryDelaysMillis.size) {
      check().onSuccess { return it }
      if (attempt < retryDelaysMillis.size) delay(retryDelaysMillis[attempt])
    }
    return null
  }

  companion object {
    /** Sei ore: un'app tenuta viva per giorni vede comunque una release entro la giornata. */
    const val RECHECK_AFTER_MILLIS: Long = 6 * 3_600_000L

    /** Tre tentativi in tutto: subito, dopo tre secondi, dopo altri quindici. */
    val RETRY_DELAYS_MILLIS: LongArray = longArrayOf(3_000L, 15_000L)
  }
}
