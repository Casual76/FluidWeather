package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.LocalClimatologyRecord
import dev.pampa.fluidweather.core.model.LocalClimatologyRecords
import dev.pampa.fluidweather.nowcast.climatology.LocalBaselines
import dev.pampa.fluidweather.nowcast.climatology.WindowClimatology
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** La climatologia di una cella, gia' decodificata: pronta per il registratore dei giri. */
data class LocalClimate(
  val cellKey: String,
  val climatology: WindowClimatology,
  val baselines: LocalBaselines,
)

/**
 * La climatologia locale della pioggia, scaricata una volta per cella e tenuta sei mesi.
 *
 * E' la riga "climatologia" della classifica — il primo avversario del barometro: chi non batte
 * "quanto piove di solito qui, in questa stagione e a quest'ora" non sa niente — e l'ancora della
 * classifica sui telefoni senza barometro. Si costruisce dalla **stessa verita'** che giudica le
 * righe (la mediana del [TruthPanel]) e con la stessa definizione dell'evento
 * ([WindowClimatology.build]), su due anni che finiscono una settimana fa: la settimana di
 * distacco tiene fuori le ore che l'archivio puo' ancora riscrivere.
 *
 * Insieme si costruiscono le baseline locali ([LocalBaselines]): la persistenza legge "piove
 * adesso" da best_match (l'opinione che il telefono vede nel contesto), la regola barometrica la
 * pressione di Météo-France. Oggi non entrano ancora in un verdetto: si tengono pronte per il
 * modello che le usera' come feature, dalla stessa richiesta.
 *
 * **Il costo.** Due anni orari di quattro modelli piu' uno di pressione sono qualche centinaio di
 * chiamate "pesate" dell'API gratuita: una volta per cella ogni [REFRESH_MILLIS]. Per questo le
 * celle sono larghe (0,25 gradi, circa 25 km: la climatologia non cambia da un quartiere
 * all'altro), un fallimento non si ritenta prima di [RETRY_MILLIS], e se ne tengono al piu'
 * [MAX_CELLS] — casa, lavoro, le vacanze — sfrattando quella letta meno di recente.
 *
 * Il registratore dei giri legge solo [cached], che non va mai in rete: la rete la fa [ensure],
 * dalla manutenzione oraria.
 */
class LocalClimatology(
  private val http: ProviderHttp,
  private val records: LocalClimatologyRecords,
  /**
   * La mutua esclusione con "cancella tutti i dati": la chiave di una cella e' un dato di posizione,
   * e un download di due anni che finisce dopo la cancellazione non deve riscriverla. Prima
   * dell'orologio, che resta l'ultimo parametro perche' i test lo passano come lambda finale.
   */
  private val wipeGuard: DataWipeGuard = DataWipeGuard(),
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private val mutex = Mutex()

  /** L'ultima cella decodificata, con l'istante in cui era stata costruita: decodificare a ogni giro e' lavoro buttato. */
  @Volatile
  private var memo: Pair<Pair<String, Long>, LocalClimate>? = null

  /**
   * La climatologia della cella del punto, se c'e' su disco. Niente rete. L'uso si annota al piu'
   * una volta al giorno: decide lo sfratto, e scrivere a ogni giro consumerebbe il disco per niente.
   */
  suspend fun cached(latitude: Double, longitude: Double): LocalClimate? {
    val key = cellKeyOf(latitude, longitude)
    val record = records.get(key) ?: return null
    val now = clock()
    if (now - record.usedAtMillis !in 0 until TOUCH_INTERVAL_MILLIS) runCatching { records.touch(key, now) }
    memo?.let { (id, climate) -> if (id == key to record.builtAtMillis) return climate }
    return decode(record)?.also { memo = (key to record.builtAtMillis) to it }
  }

  private fun decode(record: LocalClimatologyRecord): LocalClimate? {
    val climatology = WindowClimatology.decode(record.climatology) ?: return null
    val baselines = LocalBaselines.decode(record.baselines, climatology) ?: return null
    return LocalClimate(record.cellKey, climatology, baselines)
  }

  /**
   * Scarica e costruisce la climatologia della cella del punto se manca o ha piu' di
   * [REFRESH_MILLIS]. Al piu' un tentativo al giorno per cella; tiene al piu' [MAX_CELLS] celle.
   * True se ne ha costruita una adesso; false se non serviva, se deve aspettare, o se e' fallita
   * (e allora il fallimento e' annotato).
   */
  suspend fun ensure(latitude: Double, longitude: Double): Boolean = mutex.withLock {
    val generation = wipeGuard.generation()
    val now = clock()
    val key = cellKeyOf(latitude, longitude)
    val existing = runCatching { records.get(key) }.getOrNull()
    // Una cella che non si sa piu' leggere (il formato e' cambiato con un aggiornamento) va rifatta
    // subito, non fra sei mesi: e' la promessa del prefisso di formato di [WindowClimatology].
    if (existing != null && now - existing.builtAtMillis in 0 until REFRESH_MILLIS && decode(existing) != null) {
      return@withLock false
    }
    val failedAt = runCatching { records.lastFailureMillis(key) }.getOrNull()
    if (failedAt != null && now - failedAt in 0 until RETRY_MILLIS) return@withLock false

    // Il tentativo si segna PRIMA di scaricare, come se fosse gia' fallito; la riuscita (`put`) lo
    // cancella. Segnato solo dopo, un download interrotto a meta' — il tetto di tempo del ciclo in
    // background, una rete lenta, il processo ucciso — non lasciava traccia, e ogni ora ripartivano
    // due anni di storia da capo, senza finire mai. Cosi' "al piu' uno al giorno" vale sempre.
    wipeGuard.writeIfUnchanged(generation) { runCatching { records.markFailure(key, now) } }
      ?: return@withLock false

    val (centerLatitude, centerLongitude) = cellCenterOf(latitude, longitude)
    // Una climatologia vecchia resta dov'e' finche' la nuova non e' pronta: meglio sei mesi e un
    // giorno che niente.
    val built = runCatching { build(key, centerLatitude, centerLongitude, now) }.getOrNull()
    // Dopo la rete, sotto il lucchetto della cancellazione: se i dati sono stati cancellati nel
    // frattempo non si scrive niente (nemmeno il segno del fallimento, che e' una chiave di cella).
    wipeGuard.writeIfUnchanged(generation) {
      if (built == null || runCatching { records.put(built) }.isFailure) {
        runCatching { records.markFailure(key, now) }
        false
      } else {
        evictBeyondLimit(keep = key)
        true
      }
    } ?: false
  }

  private suspend fun build(key: String, latitude: Double, longitude: Double, now: Long): LocalClimatologyRecord {
    val end = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().minusDays(LAG_DAYS.toLong())
    val start = end.minusDays(HISTORY_DAYS - 1L)

    val precipitationModels = TruthPanel.MODELS + BEST_MATCH
    val precipitationRoot = http.readJsonTimed(
      OpenMeteoArchive.url(latitude, longitude, start, end, PRECIPITATION, precipitationModels),
      maxAgeMillis = 0L,
      useCache = false,
    ).value
    val precipitation = OpenMeteoArchive.hourlySeries(precipitationRoot, PRECIPITATION, precipitationModels)

    val pressureRoot = http.readJsonTimed(
      OpenMeteoArchive.url(latitude, longitude, start, end, PRESSURE_MSL, listOf(PRESSURE_MODEL)),
      maxAgeMillis = 0L,
      useCache = false,
    ).value
    val pressure = OpenMeteoArchive.hourlySeries(pressureRoot, PRESSURE_MSL, listOf(PRESSURE_MODEL))[PRESSURE_MODEL]

    // Una baseline a meta' (senza "piove adesso" o senza pressione) resterebbe sei mesi a dire la
    // climatologia al posto della regola: meglio fallire e riprovare domani.
    val rainNow = precipitation[BEST_MATCH]?.takeIf { it.isNotEmpty() } ?: error("niente best_match nella risposta")
    val msl = pressure?.takeIf { it.isNotEmpty() } ?: error("niente pressione nella risposta")
    val truth = TruthPanel.combine(precipitation)
    val climatology = WindowClimatology.build(truth, longitude) ?: error("nessuna finestra giudicabile")
    val baselines = LocalBaselines.build(truth, climatology, rainNowMm = rainNow, mslHpa = msl)
    return LocalClimatologyRecord(
      cellKey = key,
      latitude = latitude,
      longitude = longitude,
      climatology = climatology.encode(),
      baselines = baselines.encode(),
      builtAtMillis = now,
      usedAtMillis = now,
    )
  }

  private suspend fun evictBeyondLimit(keep: String) {
    val all = runCatching { records.all() }.getOrDefault(emptyList())
    val excess = all.size - MAX_CELLS
    if (excess <= 0) return
    all.filter { it.cellKey != keep }
      .sortedBy { it.usedAtMillis }
      .take(excess)
      .forEach { runCatching { records.remove(it.cellKey) } }
  }

  companion object {
    const val CELL_DEGREES: Double = 0.25
    const val HISTORY_DAYS: Int = 730
    const val LAG_DAYS: Int = 7
    const val REFRESH_MILLIS: Long = 180L * 86_400_000L
    const val RETRY_MILLIS: Long = 86_400_000L
    const val MAX_CELLS: Int = 3
    const val BEST_MATCH: String = "best_match"
    const val PRESSURE_MODEL: String = "meteofrance_seamless"

    /** L'uso di una cella si riscrive al piu' una volta al giorno. */
    const val TOUCH_INTERVAL_MILLIS: Long = 86_400_000L

    private const val PRECIPITATION = "precipitation"
    private const val PRESSURE_MSL = "pressure_msl"

    /** `c<floor(lat/0,25)>_<floor(lon/0,25)>`: stabile, leggibile, senza virgole da localizzare. */
    fun cellKeyOf(latitude: Double, longitude: Double): String {
      val (i, j) = cellIndexOf(latitude, longitude)
      return "c${i}_$j"
    }

    /** Il centro della cella: e' li' che si scarica la storia, non nel punto esatto dell'utente. */
    fun cellCenterOf(latitude: Double, longitude: Double): Pair<Double, Double> {
      val (i, j) = cellIndexOf(latitude, longitude)
      return (i + 0.5) * CELL_DEGREES to (j + 0.5) * CELL_DEGREES
    }

    /** In millesimi di grado interi, come le celle della verita': niente sorprese della virgola mobile ai bordi. */
    private fun cellIndexOf(latitude: Double, longitude: Double): Pair<Long, Long> =
      cellIndex(latitude, CELL_MILLIDEGREES) to cellIndex(longitude, CELL_MILLIDEGREES)

    private const val CELL_MILLIDEGREES = 250L
  }
}
