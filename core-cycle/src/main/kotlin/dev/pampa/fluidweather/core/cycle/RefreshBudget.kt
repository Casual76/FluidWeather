package dev.pampa.fluidweather.core.cycle

/**
 * Quanto puo' essere vecchia l'istantanea prima che valga la pena andare in rete.
 *
 * **Perche' non una guardia sul trigger.** C'era, e non ha mai protetto niente: la sorveglianza
 * chiama il ciclo ogni minuto e arriva come `SAMPLING_PASS`, quindi le due guardie scritte per
 * `SURVEILLANCE_TICK` erano irraggiungibili e per due ore di sorveglianza partivano 120 giri di
 * rete. Ma il difetto vero non era l'etichetta sbagliata: e' che **una guardia basata su chi ha
 * chiesto non protegge**, perche' l'insieme dei chiamanti e' aperto — `SAMPLING_PASS` arriva gia'
 * da tre schedulatori diversi, `MANUAL` esiste, e chi ne aggiunge un quarto salterebbe il
 * controllo senza accorgersene.
 *
 * L'invariante che si vuole e' un fatto sui **dati**, non su chi chiama: non si va in rete per un
 * posto la cui istantanea e' abbastanza giovane. Cosi' vale per chiunque, anche per chi arrivera'
 * domani.
 *
 * Oggetto puro come [AlertPolicy], e per la stessa ragione: la decisione si puo' chiedere a un
 * test senza costruire ventuno dipendenze.
 */
internal object RefreshBudget {

  /**
   * Dieci minuti, il pavimento sotto cui non si scende mai.
   *
   * In sorveglianza il sensore parla ogni minuto perche' la pressione cambia in fretta; i modelli
   * meteo no — girano a ore intere e i provider li pubblicano al piu' ogni ora. Chiedere piu'
   * spesso di dieci minuti spende batteria e traffico per riottenere lo stesso JSON.
   */
  const val MIN_REFRESH_INTERVAL_MILLIS: Long = 10 * 60_000L

  /** Il riepilogo di domattina merita dati di mezz'ora, non di ieri sera. */
  const val SUMMARY_FRESH_MILLIS: Long = 30 * 60_000L

  /**
   * Quanto puo' essere vecchia l'istantanea perche' vada ancora bene, per questo [trigger].
   *
   * Il passo del barometro fa da riferimento — chiedere il meteo piu' spesso di quanto si legge il
   * sensore non ha senso — ma non si scende mai sotto il pavimento.
   */
  fun enoughMillis(trigger: CycleTrigger, cadenceMillis: Long): Long = when (trigger) {
    // Chi tocca "esegui adesso" nelle impostazioni vuole un giro vero, non una cache.
    CycleTrigger.MANUAL -> 0L
    CycleTrigger.DAILY_SUMMARY -> SUMMARY_FRESH_MILLIS
    else -> maxOf(cadenceMillis, MIN_REFRESH_INTERVAL_MILLIS)
  }
}
