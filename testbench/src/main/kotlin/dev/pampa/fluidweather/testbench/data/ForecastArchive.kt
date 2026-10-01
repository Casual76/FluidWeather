package dev.pampa.fluidweather.testbench.data

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Una serie oraria (o al quarto d'ora) normalizzata, letta da `data/hf/...` o `data/hf15/...`:
 * istanti in millisecondi UTC in ordine crescente, una colonna di valori per variabile.
 *
 * La ricerca e' **solo esatta**: [at] risponde per l'istante richiesto o per niente. Nessuna
 * interpolazione, nessuno "slot piu' vicino": un banco che arrotonda in silenzio un istante mancante
 * sta misurando un'altra cosa da quella che dichiara.
 */
class HourlySeries(
  private val times: LongArray,
  private val columns: Map<String, DoubleArray>,
) {

  init {
    require(columns.values.all { it.size == times.size }) { "colonne di lunghezza diversa dagli istanti" }
    for (i in 1 until times.size) require(times[i] > times[i - 1]) { "istanti non crescenti all'indice $i" }
  }

  val size: Int get() = times.size
  val variables: Set<String> get() = columns.keys
  val firstMillis: Long get() = times.first()
  val lastMillis: Long get() = times.last()

  fun timeAt(index: Int): Long = times[index]

  /** L'indice dell'istante esatto, o -1. */
  fun indexOf(tMillis: Long): Int = times.binarySearch(tMillis).coerceAtLeast(-1)

  /** Il valore di [variable] a [tMillis] esatto; null se l'istante non c'e' o il modello non aveva il dato. */
  fun at(tMillis: Long, variable: String): Double? {
    val column = columns[variable] ?: error("variabile '$variable' assente; ci sono $variables")
    val index = indexOf(tMillis)
    if (index < 0) return null
    return column[index].takeUnless { it.isNaN() }
  }

  /** La colonna intera (NaN dove assente): per i cicli sul periodo intero senza una ricerca per riga. */
  fun column(variable: String): DoubleArray =
    columns[variable] ?: error("variabile '$variable' assente; ci sono $variables")

  companion object {

    fun load(file: File): HourlySeries = parse(file.readText(), file.path)

    /** Il formato normalizzato del fetcher: `time,<variabile>,...` in secondi unix, vuoto = assente. */
    fun parse(text: String, source: String = "serie"): HourlySeries {
      val csv = OpenMeteoCsv.parse(text)
      require(!csv.hasLocationId) { "$source: una serie normalizzata non ha location_id" }
      val rows = csv.rows.sortedBy { it.epochSeconds }
      val times = LongArray(rows.size) { rows[it].epochSeconds * 1_000L }
      val columns = csv.columns.withIndex().associate { (i, name) ->
        name to DoubleArray(rows.size) { rows[it].number(i) ?: Double.NaN }
      }
      return HourlySeries(times, columns)
    }
  }
}

/**
 * Le serie stitched scaricate da [HistoricalForecastFetcher]: `data/hf/<localita'>/<modello>.csv`
 * per l'orario, `data/hf15/...` per il quarto d'ora. Ritorna null se il file non c'e' (non ancora
 * scaricato): chi lo chiede deve saperlo, non ricevere una serie vuota.
 */
class ForecastArchive(private val root: File = File("data")) {

  fun hourly(location: BenchLocation, model: String): HourlySeries? = load("hf", location, model)

  fun quarterHourly(location: BenchLocation, model: String): HourlySeries? = load("hf15", location, model)

  fun runs(model: String): RunArchive = RunArchive(model, File(root, "runs"))

  private fun load(directory: String, location: BenchLocation, model: String): HourlySeries? {
    val file = File(root, "$directory/${location.name}/$model.csv")
    return if (file.exists()) HourlySeries.load(file) else null
  }
}

/**
 * Le corse passate di un modello scaricate da [SingleRunFetcher]: `data/runs/<modello>/<yyyy-MM>/<yyyyMMddHH>.csv`.
 *
 * L'elenco delle emissioni su disco e' una fotografia presa alla prima richiesta (un fetch che gira
 * nel frattempo non si vede: si ricrea l'archivio). Le corse si leggono a mesi: il primo accesso a
 * un mese ne parsa tutti i file, e se ne tengono in memoria al piu' due — un passaggio cronologico
 * sul periodo intero non tiene mai piu' di due mesi, e un accesso casuale ne paga il ricarico senza
 * gonfiare la memoria.
 */
class RunArchive(
  val model: String,
  root: File = File("data/runs"),
  private val locations: List<BenchLocation> = BenchLocations,
) {

  private val directory = File(root, model)

  /**
   * `location_id` e' solo un indice nell'ordine della richiesta: si legge come posizione in
   * [locations] solo se `locations.txt` (scritto dal fetcher) dichiara proprio quell'ordine. Se
   * BenchLocations cambiasse dopo il download, ogni valore finirebbe alla localita' sbagliata
   * senza un errore; cosi' invece ci si ferma alla prima lettura.
   */
  private val locationOrderChecked: Unit by lazy {
    val sidecar = File(root, SingleRunFetcher.LOCATIONS_FILE)
    if (sidecar.exists()) {
      check(sidecar.readText() == SingleRunFetcher.locationsSidecar(locations)) {
        "${sidecar.path} dichiara un ordine delle localita' diverso da quello con cui si legge l'archivio"
      }
    }
  }

  /** Le emissioni presenti su disco, in millisecondi UTC, crescenti. */
  val initTimesMillis: LongArray by lazy { scan() }

  /** Le emissioni che l'API ha detto di non avere (HTTP 400): diverse da "non ancora scaricate". */
  val missingInitMillis: Set<Long> by lazy {
    val file = File(directory, "missing.txt")
    if (!file.exists()) emptySet() else file.readLines().mapNotNull { parseStamp(it.trim()) }.toSet()
  }

  /**
   * L'emissione piu' recente fra quelle su disco che un osservatore in [tMillis] poteva gia'
   * avere: `init + delayMillis <= tMillis`. Il ritardo e' il tempo fra l'inizio della corsa e la sua
   * pubblicazione (misurato per modello): senza, il banco userebbe previsioni che al momento
   * non esistevano. Una corsa mancante si salta, e si cade su quella prima. Null se nessuna.
   *
   * @param maxAgeMillis scarta corse piu' vecchie di tanto (una corsa copre solo 24 ore).
   */
  fun latestRunAvailableAt(tMillis: Long, delayMillis: Long, maxAgeMillis: Long? = null): Long? =
    latestRun(initTimesMillis, tMillis, delayMillis, maxAgeMillis)

  /**
   * Il valore di [variable] della corsa [runInitMillis] per [location] a [tMillis] esatto;
   * null se la corsa non c'e', l'istante non e' nell'orizzonte o il dato e' nullo (l'ora 0 lo e').
   */
  fun valueAt(runInitMillis: Long, location: BenchLocation, tMillis: Long, variable: String): Double? {
    val index = locations.indexOfFirst { it.name == location.name }
    require(index >= 0) { "${location.name} non e' fra le localita' dell'archivio" }
    val run = month(monthKey(runInitMillis))[runInitMillis] ?: return null
    return run.getOrNull(index)?.at(tMillis, variable)
  }

  private class LocationRun(val times: LongArray, val values: Map<String, DoubleArray>) {
    fun at(tMillis: Long, variable: String): Double? {
      val column = values[variable] ?: return null
      val index = times.binarySearch(tMillis)
      return if (index < 0) null else column[index].takeUnless { it.isNaN() }
    }
  }

  private val cache = object : LinkedHashMap<String, Map<Long, List<LocationRun?>>>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Long, List<LocationRun?>>>): Boolean =
      size > MAX_MONTHS_IN_MEMORY
  }

  private fun month(key: String): Map<Long, List<LocationRun?>> = cache.getOrPut(key) {
    val folder = File(directory, key)
    val files = folder.listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
    files.mapNotNull { file ->
      val init = parseStamp(file.nameWithoutExtension) ?: return@mapNotNull null
      init to parseRun(file.readText())
    }.toMap()
  }

  private fun parseRun(text: String): List<LocationRun?> {
    locationOrderChecked
    val csv = OpenMeteoCsv.parse(text, model)
    require(csv.hasLocationId) { "corsa senza location_id" }
    val byLocation = csv.rows.groupBy { it.locationId }
    return locations.indices.map { id ->
      val rows = byLocation[id]?.sortedBy { it.epochSeconds } ?: return@map null
      LocationRun(
        LongArray(rows.size) { rows[it].epochSeconds * 1_000L },
        csv.columns.withIndex().associate { (i, name) ->
          name to DoubleArray(rows.size) { rows[it].number(i) ?: Double.NaN }
        },
      )
    }
  }

  private fun scan(): LongArray {
    val months = directory.listFiles { f -> f.isDirectory }.orEmpty()
    return months.flatMap { folder ->
      folder.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().mapNotNull { parseStamp(it.nameWithoutExtension) }
    }.sorted().toLongArray()
  }

  companion object {
    private const val MAX_MONTHS_IN_MEMORY = 2

    /** "2026040206" -> millisecondi UTC dell'emissione 2026-04-02 06:00; null se non e' un nome di corsa. */
    fun parseStamp(stamp: String): Long? {
      if (stamp.length != 10 || !stamp.all { it.isDigit() }) return null
      return runCatching {
        LocalDateTime.of(
          stamp.substring(0, 4).toInt(), stamp.substring(4, 6).toInt(),
          stamp.substring(6, 8).toInt(), stamp.substring(8, 10).toInt(), 0,
        ).toInstant(ZoneOffset.UTC).toEpochMilli()
      }.getOrNull()
    }

    private fun monthKey(initMillis: Long): String =
      SingleRunFetcher.MONTH.format(java.time.Instant.ofEpochMilli(initMillis))

    /**
     * La selezione pura: fra le emissioni [sortedInits] (crescenti), la piu' recente con
     * `init + delay <= t`. Mai una corsa che al momento [tMillis] non era ancora uscita.
     */
    fun latestRun(sortedInits: LongArray, tMillis: Long, delayMillis: Long, maxAgeMillis: Long? = null): Long? {
      require(delayMillis >= 0) { "ritardo negativo" }
      val limit = tMillis - delayMillis
      // Ultimo indice con init <= limit.
      var low = 0
      var high = sortedInits.size - 1
      var found = -1
      while (low <= high) {
        val mid = (low + high) ushr 1
        if (sortedInits[mid] <= limit) {
          found = mid
          low = mid + 1
        } else {
          high = mid - 1
        }
      }
      if (found < 0) return null
      val init = sortedInits[found]
      if (maxAgeMillis != null && tMillis - init > maxAgeMillis) return null
      return init
    }
  }
}
