package dev.pampa.fluidweather.core.model

/**
 * La climatologia locale di una cella, come sta su disco: due stringhe gia' codificate
 * (`WindowClimatology.encode()` e `LocalBaselines.encode()`) e i tempi che servono a decidere
 * quando rifarla. Stringhe e non oggetti perche' core-model non vede :nowcast: chi sa leggerle e'
 * chi le ha scritte.
 */
data class LocalClimatologyRecord(
  /** La cella da 0,25 gradi: `c<i>_<j>`. */
  val cellKey: String,
  /** Il centro della cella chiesta: e' li' che si e' scaricata la storia, e non dove sta l'utente. */
  val latitude: Double,
  val longitude: Double,
  /** `WindowClimatology.encode()`. */
  val climatology: String,
  /** `LocalBaselines.encode()`. */
  val baselines: String,
  val builtAtMillis: Long,
  /** L'ultima volta che qualcuno l'ha letta: decide quale cella sfrattare quando sono troppe. */
  val usedAtMillis: Long,
)

/**
 * Le climatologie locali conservate, al massimo una manciata: ognuna costa due anni di storia da
 * scaricare, quindi si tiene e si rifa' ogni sei mesi, non a ogni giro.
 */
interface LocalClimatologyRecords {
  suspend fun get(cellKey: String): LocalClimatologyRecord?
  suspend fun all(): List<LocalClimatologyRecord>
  suspend fun put(record: LocalClimatologyRecord)
  suspend fun remove(cellKey: String)
  suspend fun touch(cellKey: String, usedAtMillis: Long)

  /**
   * Quando e' cominciato l'ultimo tentativo non riuscito per questa cella: serve a non riprovare a
   * ogni giro. Chi scarica lo segna prima di cominciare e [put] lo cancella, cosi' anche un
   * tentativo interrotto a meta' conta.
   */
  suspend fun lastFailureMillis(cellKey: String): Long?
  suspend fun markFailure(cellKey: String, atMillis: Long)

  /** Dati e privacy: via tutto. */
  suspend fun clear()
}

/**
 * Il freno della manutenzione della pioggia (giudizi e climatologia): al piu' una volta l'ora.
 * Persistito, perche' i worker nascono in processi nuovi e una variabile in memoria non frena nessuno.
 */
interface MaintenanceThrottle {
  suspend fun lastRunMillis(): Long?
  suspend fun markRun(atMillis: Long)
}
