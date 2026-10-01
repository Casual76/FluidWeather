package dev.pampa.fluidweather.testbench.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Un modello e l'intervallo di emissioni (UTC, incluse) da scaricare, ogni 6 ore. */
data class RunPreset(val model: String, val firstInit: Instant, val lastInit: Instant)

/**
 * Le emissioni che la Single Runs API archivia davvero (verificato 2026-09-30): ECMWF IFS 9 km dal
 * 2024-03-14, gli altri dal 2026-04-02. Le emissioni non archiviate rispondono HTTP 400 e finiscono
 * in `missing.txt`: si chiedono comunque tutte (00/06/12/18) perche' non tutti i modelli hanno
 * tutte e quattro le corse, e saperlo e' un dato.
 */
object RunPresets {

  private fun utc(year: Int, month: Int, day: Int, hour: Int): Instant =
    LocalDateTime.of(year, month, day, hour, 0).toInstant(ZoneOffset.UTC)

  private val LAST = utc(2026, 9, 28, 18)
  private val SET_B = utc(2026, 4, 2, 0)

  /** La corsa che la sonda chiede: recente (archiviata da giorni) e delle 00, l'unica ora che tutti i modelli emettono. */
  val PROBE_INIT: Instant = utc(2026, 9, 28, 0)

  val ALL: List<RunPreset> = listOf(
    RunPreset("ecmwf_ifs", utc(2024, 3, 14, 0), LAST),
    RunPreset("best_match", SET_B, LAST),
    RunPreset("icon_seamless", SET_B, LAST),
    RunPreset("gfs_seamless", SET_B, LAST),
    RunPreset("ecmwf_ifs025", SET_B, LAST),
    RunPreset("meteofrance_seamless", SET_B, LAST),
  )
}

/** Esito di un [SingleRunFetcher.fetch]. */
data class RunFetchSummary(
  val downloaded: Int,
  val alreadyPresent: Int,
  val knownMissing: Int,
  val newlyMissing: Int,
  val tooRecent: Int,
  val failed: Int,
)

/**
 * Scarica le corse passate dei modelli, una richiesta per corsa su TUTTE le localita' del banco
 * (coordinate separate da virgola), 24 ore di previsione: `precipitation` e `pressure_msl`, le
 * uniche variabili deterministiche che servono al barometro potenziato. La PoP qui non esiste
 * (HTTP 400: viene dagli ensemble, che non si archiviano) e questo e' il punto: a differenza
 * dell'archivio "stitched", una corsa passata non contiene nulla emesso dopo di lei. Niente fuga.
 *
 * Su disco: `data/runs/<modello>/<yyyy-MM>/<yyyyMMddHH>.csv`, la risposta cosi' com'e' arrivata
 * (con `location_id`). L'ordine delle localita' e' l'ordine di [BenchLocations]: lo dichiara
 * `data/runs/locations.txt`, e un ordine diverso da quello gia' scritto ferma il fetch — mescolare
 * due ordinamenti in un archivio sarebbe un errore che nessun test vedrebbe.
 *
 * Riprendibile (le corse gia' su disco o in `missing.txt` non si rifanno), governato dal
 * [CallBudget] (peso 10 a richiesta: dieci localita'), e stampa l'avanzamento ogni 50 richieste.
 *
 * Dati meteo di Open-Meteo.com (CC BY 4.0), uso non commerciale.
 */
class SingleRunFetcher(
  private val http: OpenMeteoHttp,
  private val root: File = File("data/runs"),
  private val locations: List<BenchLocation> = BenchLocations,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val log: (String) -> Unit = ::println,
) {

  private val weight = CallBudget.weightOf(VARIABLES.size, 1, locations.size, 1)

  fun runFile(model: String, init: Instant): File =
    File(root, "$model/${MONTH.format(init)}/${STAMP.format(init)}.csv")

  /**
   * @param from/to restringono l'intervallo del preset (prove e riprese mirate).
   * @param limit si ferma dopo tanti download o "mancanti" nuovi (prove); null = tutto.
   */
  fun fetch(preset: RunPreset, from: Instant? = null, to: Instant? = null, limit: Int? = null): RunFetchSummary {
    writeLocationsSidecar()
    val first = maxOf(preset.firstInit, from ?: preset.firstInit)
    val last = minOf(preset.lastInit, to ?: preset.lastInit)
    val inits = generateSequence(first) { it.plusSeconds(RUN_STEP_SECONDS) }.takeWhile { !it.isAfter(last) }.toList()

    val missingFile = File(root, "${preset.model}/missing.txt")
    val missing = readMissing(missingFile)
    var downloaded = 0
    var present = 0
    var known = 0
    var newly = 0
    var recent = 0
    var failed = 0
    var consecutiveFailures = 0
    var requests = 0
    log("=== ${preset.model}: ${inits.size} emissioni, da ${STAMP.format(first)} a ${STAMP.format(last)}")

    for (init in inits) {
      if (limit != null && downloaded + newly >= limit) break
      val file = runFile(preset.model, init)
      val stamp = STAMP.format(init)
      when {
        file.exists() -> { present++; continue }
        stamp in missing -> { known++; continue }
      }
      // Una corsa appena emessa non e' "non disponibile per sempre": e' solo in ritardo. Non la
      // si marchia, o il fetch successivo non la riproverebbe piu'.
      if (nowMillis() - init.toEpochMilli() < TOO_RECENT_MILLIS) { recent++; continue }

      requests++
      try {
        when (val outcome = downloadRun(preset.model, init, file)) {
          RunOutcome.SAVED -> { downloaded++; consecutiveFailures = 0 }
          RunOutcome.NOT_AVAILABLE -> {
            newly++
            consecutiveFailures = 0
            missingFile.parentFile.mkdirs()
            Files.write(
              missingFile.toPath(), "$stamp\n".toByteArray(),
              java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
            )
            missing += stamp
          }
        }
      } catch (e: Exception) {
        failed++
        consecutiveFailures++
        log("  ${preset.model} $stamp: FALLITA (${e.message})")
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
          log("  $MAX_CONSECUTIVE_FAILURES fallimenti di fila: mi fermo (rilancia per riprendere)")
          break
        }
      }
      if (requests % PROGRESS_EVERY == 0) {
        log("  ${preset.model}: $requests richieste (ultima $stamp) - $downloaded salvate, $newly non disponibili, $failed fallite; budget ultime 24 h: ${http.budgetUsedToday()}/${CallBudget.PER_DAY}")
      }
    }
    log("  ${preset.model}: $downloaded salvate, $present gia' presenti, $known note come mancanti, $newly mancanti nuove, $recent troppo recenti, $failed fallite")
    return RunFetchSummary(downloaded, present, known, newly, recent, failed)
  }

  private enum class RunOutcome { SAVED, NOT_AVAILABLE }

  private fun downloadRun(model: String, init: Instant, file: File): RunOutcome {
    val reply = http.get(runUrl(model, init), weight, "$model ${STAMP.format(init)}")
    if (reply.status == 400 && "not available" in OpenMeteoHttp.reason(reply.body)) return RunOutcome.NOT_AVAILABLE
    if (reply.status == 200 && OpenMeteoHttp.isRunUnavailable(reply.body)) return RunOutcome.NOT_AVAILABLE
    check(reply.status == 200) { "HTTP ${reply.status} (${OpenMeteoHttp.reason(reply.body)})" }

    val csv = OpenMeteoCsv.parse(reply.body, model)
    check(csv.hasLocationId) { "risposta senza location_id: le localita' non sono state lette tutte" }
    val ids = csv.rows.mapNotNull { it.locationId }.toSet()
    check(ids == locations.indices.toSet()) { "localita' nella risposta: $ids, attese ${locations.size}" }
    // Una risposta troncata a meta' (il server risponde in streaming) ha l'ultima localita' corta.
    val rowCounts = csv.rows.groupBy { it.locationId }.values.map { it.size }.toSet()
    check(rowCounts.size == 1) { "righe diverse fra le localita' ($rowCounts): risposta troncata?" }
    VARIABLES.forEach { csv.columnIndex(it, model) }

    file.parentFile.mkdirs()
    val part = File(file.path + ".part")
    part.writeText(reply.body)
    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    return RunOutcome.SAVED
  }

  fun runUrl(model: String, init: Instant): String =
    "https://single-runs-api.open-meteo.com/v1/forecast" +
      "?latitude=${locations.joinToString(",") { it.latitude.toString() }}" +
      "&longitude=${locations.joinToString(",") { it.longitude.toString() }}" +
      "&hourly=${VARIABLES.joinToString(",")}" +
      "&models=$model&run=${RUN_PARAM.format(init)}" +
      "&forecast_hours=$FORECAST_HOURS&timeformat=unixtime&timezone=UTC&format=csv"

  /**
   * La sonda: UNA corsa recente per modello, stampata e buttata. Serve a vedere, prima di spendere
   * giorni di budget, che il formato sia quello che il lettore si aspetta e che `forecast_hours`
   * faccia quello che dice.
   */
  fun probe(presets: List<RunPreset>, init: Instant) {
    log("=== sonda Single Runs: corsa ${RUN_PARAM.format(init)}Z, ${locations.size} localita', forecast_hours=$FORECAST_HOURS (niente viene salvato)")
    for (preset in presets) {
      log("")
      log("[${preset.model}]")
      val reply = http.get(runUrl(preset.model, init), weight, "sonda ${preset.model}")
      log("  HTTP ${reply.status}, ${reply.body.length} byte")
      if (reply.status != 200) {
        log("  risposta: ${OpenMeteoHttp.reason(reply.body)}")
        continue
      }
      val csv = OpenMeteoCsv.parse(reply.body, preset.model)
      log("  colonne: ${(if (csv.hasLocationId) listOf("location_id", "time") else listOf("time")) + csv.columns} - location_id ${if (csv.hasLocationId) "presente" else "ASSENTE"}")
      val byLocation = csv.rows.groupBy { it.locationId }
      val counts = byLocation.values.map { it.size }.toSet()
      val worked = counts == setOf(FORECAST_HOURS)
      log("  localita': ${byLocation.size}, righe per localita': ${counts.sorted()} - forecast_hours ${if (worked) "ha funzionato ($FORECAST_HOURS righe)" else "NON ha dato $FORECAST_HOURS righe"}")
      val times = csv.rows.map { it.epochSeconds }
      log("  primo istante ${Instant.ofEpochSecond(times.min())}, ultimo ${Instant.ofEpochSecond(times.max())} (emissione ${RUN_PARAM.format(init)}Z)")
      for (variable in VARIABLES) {
        val index = csv.columnIndex(variable, preset.model)
        val nulls = csv.rows.count { it.cells.getOrElse(index) { "" }.isEmpty() }
        val hourZero = byLocation.values.count { rows ->
          rows.minByOrNull { it.epochSeconds }?.cells?.getOrElse(index) { "" }.isNullOrEmpty()
        }
        log("  $variable: nulli ${nulls}/${csv.rows.size}, di cui ora 0 nulla in $hourZero/${byLocation.size} localita'")
      }
      val firstRows = csv.rows.take(3).joinToString(" | ") { row ->
        "${row.locationId}," + row.epochSeconds + "," + row.cells.joinToString(",")
      }
      log("  prime righe: $firstRows")
    }
    log("")
    log(HistoricalForecastFetcher.ATTRIBUTION)
  }

  private fun writeLocationsSidecar() {
    val text = locationsSidecar(locations)
    val sidecar = File(root, LOCATIONS_FILE)
    if (sidecar.exists()) {
      check(sidecar.readText() == text) {
        "${sidecar.path} dichiara un ordine delle localita' diverso da BenchLocations: " +
          "non si mescolano due ordinamenti in un archivio"
      }
      return
    }
    root.mkdirs()
    sidecar.writeText(text)
  }

  private fun readMissing(file: File): MutableSet<String> =
    if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()

  companion object {
    val VARIABLES = listOf("precipitation", "pressure_msl")
    const val FORECAST_HOURS = 24
    const val LOCATIONS_FILE = "locations.txt"
    const val RUN_STEP_SECONDS = 6 * 3_600L
    private const val PROGRESS_EVERY = 50
    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val TOO_RECENT_MILLIS = 36 * 3_600_000L

    /** Il contenuto di `locations.txt`: `location_id,nome,lat,lon` per riga, nell'ordine della richiesta. */
    fun locationsSidecar(locations: List<BenchLocation>): String =
      locations.withIndex().joinToString("") { (id, l) -> "$id,${l.name},${l.latitude},${l.longitude}\n" }

    val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH", Locale.ROOT).withZone(ZoneOffset.UTC)
    val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val RUN_PARAM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)
  }
}
