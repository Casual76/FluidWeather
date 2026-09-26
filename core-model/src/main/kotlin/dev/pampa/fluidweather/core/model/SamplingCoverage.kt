package dev.pampa.fluidweather.core.model

/**
 * "Il campionamento in background sta girando davvero?", chiesto all'archivio invece che allo
 * scheduler.
 *
 * Nasce dal baco dei due Samsung (S24+ e S22, 2026-09-26): la storia si formava, dopo qualche
 * giorno tornava a "0 di 13 ore" e non si muoveva piu'. One UI mette "in sospensione" le app non
 * aperte da tre giorni, e "in sospensione profonda" non girano piu' in background nemmeno dopo
 * che le si apre. Lo scheduler, interrogato, risponde che il lavoro c'e' (e' ENQUEUED: rinviato,
 * non cancellato); l'archivio invece non mente — nelle ultime 24 ore non entra quasi niente.
 *
 * Si contano le **ore coperte**, non i campioni: la sorveglianza scrive una lettura al minuto per
 * due ore e gonfierebbe qualsiasi conteggio, mentre un'ora con almeno un campione e' un'ora in cui
 * il campionamento c'era. Un telefono sano ne copre quasi tutte e ventiquattro; di notte Doze
 * diluisce le passate (finestre di manutenzione fino a qualche ora), e anche li' resta ben sopra
 * la soglia.
 */
object SamplingCoverage {

  const val WINDOW_MILLIS: Long = 24 * 3_600_000L

  /** Sotto un terzo delle ore coperte non e' Doze che fa il suo mestiere: e' l'app ferma. */
  const val MIN_COVERED_HOURS: Int = 8

  /** Le ore (delle ultime 24) in cui l'archivio ha almeno un campione. */
  fun coveredHours(sampleTimestamps: Iterable<Long>, nowMillis: Long): Int =
    sampleTimestamps
      .map { nowMillis - it }
      .filter { it in 0 until WINDOW_MILLIS }
      .map { it / 3_600_000L }
      .toSet()
      .size

  /**
   * Vero quando l'archivio dice che il campionamento e' fermo.
   *
   * [oldestSampleMillis] e' il campione piu' vecchio di tutto l'archivio: un'installazione di
   * poche ore ha poche ore coperte per forza, e non e' bloccata — sta cominciando. Si giudica solo
   * quando l'archivio e' piu' vecchio della finestra.
   */
  fun isBlocked(sampleTimestamps: Iterable<Long>, nowMillis: Long, oldestSampleMillis: Long?): Boolean {
    val oldest = oldestSampleMillis ?: return false
    if (nowMillis - oldest < WINDOW_MILLIS) return false
    return coveredHours(sampleTimestamps, nowMillis) < MIN_COVERED_HOURS
  }
}
