package dev.pampa.fluidweather.testbench.data

import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Passo temporale di una serie: decide il parametro dell'API, la cartella e le righe attese. */
enum class HfResolution(val parameter: String, val directory: String, val rowsPerDay: Int) {
  HOURLY("hourly", "hf", 24),
  QUARTER_HOURLY("minutely_15", "hf15", 96),
}

/** Un modello e il primo giorno da cui ha dati utili (prima l'API risponde, ma con soli NaN). */
data class HfModelRange(val model: String, val from: LocalDate)

/**
 * Un prodotto scaricabile: un insieme di modelli x variabili x localita' x periodo, con un nome
 * con cui chiamarlo da riga di comando.
 */
data class HfProduct(
  val name: String,
  val models: List<HfModelRange>,
  val variables: List<String>,
  val locations: List<BenchLocation>,
  val to: LocalDate,
  val resolution: HfResolution = HfResolution.HOURLY,
  /** Giorni per richiesta: un anno per l'orario, meno per il quarto d'ora (quattro volte le righe). */
  val chunkDays: Int = 365,
)

/**
 * I prodotti (preset con nome) che il banco sa scaricare dall'historical-forecast-api.
 *
 * Fatti del 2026-09-30 che li hanno disegnati: l'API incolla "le prime ore di ogni corsa", quindi
 * e' la serie che un telefono avrebbe visto poco dopo l'emissione (per il contesto) e la verita'
 * del pannello dopo `T_final`; i tre modelli del pannello hanno precipitazione oraria non nulla
 * dal 2022-11-24 e non prima; ecmwf_ifs025 parte dal 2024-02-03.
 */
object HfProducts {

  /** Il primo giorno comune dei giudici: lo stesso istante di [TruthPanel.AVAILABLE_FROM_MILLIS]. */
  val FIRST_DAY: LocalDate =
    Instant.ofEpochMilli(TruthPanel.AVAILABLE_FROM_MILLIS).atOffset(ZoneOffset.UTC).toLocalDate()
  val LAST_DAY: LocalDate = LocalDate.of(2026, 9, 28)

  /**
   * I giudici fissi della verita': fuori dalla classifica pioggia, orari nativi. La giuria e'
   * quella di [TruthPanel.MODELS], non una sua copia: se il pannello cambia, il banco scarica il nuovo.
   */
  val PANEL = HfProduct(
    name = "panel",
    models = TruthPanel.MODELS.map { HfModelRange(it, FIRST_DAY) },
    variables = listOf("precipitation", "pressure_msl"),
    locations = BenchLocations,
    to = LAST_DAY,
  )

  /** Il contesto del presente come l'app lo legge (best_match stitched, come sul telefono). */
  val CONTEXT = HfProduct(
    name = "context",
    models = listOf(HfModelRange("best_match", FIRST_DAY)),
    variables = listOf(
      "temperature_2m", "relative_humidity_2m", "dew_point_2m", "pressure_msl", "precipitation",
      "cloud_cover", "wind_speed_10m", "wind_direction_10m", "precipitation_probability", "weather_code",
    ),
    locations = BenchLocations,
    to = LAST_DAY,
  )

  /** I modelli della classifica: per confrontarli, etichettati "con fuga" dove la PoP e' stitched. */
  val PROVIDERS = HfProduct(
    name = "providers",
    models = listOf(
      HfModelRange("icon_seamless", FIRST_DAY),
      HfModelRange("gfs_seamless", FIRST_DAY),
      HfModelRange("ecmwf_ifs025", LocalDate.of(2024, 2, 3)),
    ),
    variables = listOf("precipitation", "precipitation_probability", "pressure_msl"),
    locations = BenchLocations,
    to = LAST_DAY,
  )

  /** Il quarto d'ora di best_match: quattro localita', quelle dove il telefono puo' fare da verifica. */
  val MINUTELY = HfProduct(
    name = "minutely",
    models = listOf(HfModelRange("best_match", FIRST_DAY)),
    variables = listOf("precipitation"),
    locations = BenchLocations.filter { it.name in setOf("sesto-fiorentino", "milano", "genova", "innsbruck") },
    to = LAST_DAY,
    resolution = HfResolution.QUARTER_HOURLY,
    chunkDays = 92,
  )

  val ALL_HOURLY: List<HfProduct> = listOf(PANEL, CONTEXT, PROVIDERS)

  fun byName(name: String): List<HfProduct>? = when (name) {
    "panel" -> listOf(PANEL)
    "context" -> listOf(CONTEXT)
    "providers" -> listOf(PROVIDERS)
    "minutely" -> listOf(MINUTELY)
    "all" -> ALL_HOURLY
    else -> null
  }
}

/** Esito di un [HistoricalForecastFetcher.fetch]: serve a dire "rilancia" o "fatto". */
data class HfFetchSummary(val downloaded: Int, val alreadyPresent: Int, val failed: Int, val requests: Int)

/**
 * Scarica le serie "stitched" dell'historical-forecast-api in `data/hf/<localita'>/<modello>.csv`
 * (o `data/hf15/...` per il quarto d'ora): un CSV normalizzato per coppia localita' + modello,
 * intestazione `time,<variabile>,...`, secondi unix, valori o vuoto.
 *
 * Una richiesta per (localita', modello, pezzo) — una localita' per volta, cosi' un errore non si
 * porta dietro le altre e il pezzo e' l'unita' di ripartenza: ogni pezzo scaricato resta su disco
 * in `<modello>.chunks/` finche' il file non e' completo, e un'interruzione (o un 429 lungo) al
 * rilancio riparte dal pezzo dopo invece che dall'inizio. Il file finale si scrive in `.part` e
 * si rinomina: un file che esiste e' completo.
 *
 * **Solo ore assestate.** L'archivio risponde fino a fine giornata, ma le ore recenti sono ancora
 * previsioni finche' le corse successive non le sostituiscono; e un file che esiste non si
 * riscarica piu'. Un prodotto il cui ultimo slot non ha ancora [TruthPanel.FINALITY_MILLIS] alle
 * spalle quindi non si scarica affatto: salvarlo vorrebbe dire congelare una previsione come verita'.
 *
 * Dati meteo di Open-Meteo.com (CC BY 4.0), uso non commerciale.
 */
class HistoricalForecastFetcher(
  private val http: OpenMeteoHttp,
  private val root: File = File("data"),
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val log: (String) -> Unit = ::println,
) {

  fun fetch(products: List<HfProduct>): HfFetchSummary {
    var downloaded = 0
    var present = 0
    var failed = 0
    var requests = 0
    for (product in products) {
      val settledAt = lastSlotEndMillis(product.to) + TruthPanel.FINALITY_MILLIS
      if (nowMillis() < settledAt) {
        failed += product.locations.size * product.models.size
        log("=== ${product.name}: RIFIUTATO - l'ultimo slot (${product.to}) si assesta solo dal ${Instant.ofEpochMilli(settledAt)}: prima e' ancora una previsione")
        continue
      }
      val jobs = product.locations.flatMap { location -> product.models.map { location to it } }
      log("=== ${product.name}: ${jobs.size} serie (${product.locations.size} localita' x ${product.models.size} modelli), ${product.variables.size} variabili, fino a ${product.to}")
      for ((index, job) in jobs.withIndex()) {
        val (location, range) = job
        val target = targetFile(product, location, range.model)
        val label = "${location.name}/${range.model}"
        val tag = "[${index + 1}/${jobs.size}] ${product.name} $label"
        if (target.exists()) {
          present++
          log("$tag: gia' presente")
          continue
        }
        val started = System.currentTimeMillis()
        try {
          val outcome = fetchSeries(product, location, range, target)
          downloaded++
          requests += outcome.requests
          log("$tag: ${outcome.rows} righe, ${outcome.requests} richieste, ${(System.currentTimeMillis() - started) / 1000} s; vuoti ${outcome.emptyShare}")
        } catch (e: Exception) {
          failed++
          log("$tag: FALLITO (${e.message}) - rilancia per riprendere da qui")
        }
      }
    }
    log("riepilogo: $downloaded scaricate, $present gia' presenti, $failed fallite, $requests richieste")
    log(ATTRIBUTION)
    return HfFetchSummary(downloaded, present, failed, requests)
  }

  fun targetFile(product: HfProduct, location: BenchLocation, model: String): File =
    File(root, "${product.resolution.directory}/${location.name}/$model.csv")

  private class SeriesOutcome(val rows: Int, val requests: Int, val emptyShare: String)

  private fun fetchSeries(product: HfProduct, location: BenchLocation, range: HfModelRange, target: File): SeriesOutcome {
    target.parentFile.mkdirs()
    val chunkDir = File(target.parentFile, "${range.model}.chunks")
    val lines = sortedMapOf<Long, String>()
    var requests = 0
    for ((start, end) in chunks(range.from, product.to, product.chunkDays)) {
      // Il nome dice inizio e fine: se `to` si sposta, l'ultimo pezzo vecchio (piu' corto) non si
      // riusa per sbaglio come se coprisse il nuovo intervallo.
      val chunkFile = File(chunkDir, "${compact(start)}-${compact(end)}.chunk")
      val chunkLines = if (chunkFile.exists()) {
        chunkFile.readLines()
      } else {
        val downloaded = downloadChunk(product, location, range.model, start, end)
        requests++
        chunkDir.mkdirs()
        writeAtomically(chunkFile, downloaded.joinToString("") { it + "\n" })
        downloaded
      }
      for (line in chunkLines) lines[line.substringBefore(',').toLong()] = line
    }
    check(lines.isNotEmpty()) { "nessuna riga per ${location.name}/${range.model}" }

    val text = buildString {
      append("time,").appendLine(product.variables.joinToString(","))
      for (line in lines.values) appendLine(line)
    }
    writeAtomically(target, text)
    chunkDir.deleteRecursively()
    if (product.resolution == HfResolution.QUARTER_HOURLY) writeQuarterHourlyMeta(target)
    return SeriesOutcome(lines.size, requests, emptyShare(lines.values, product.variables))
  }

  /** Un pezzo gia' normalizzato: righe "time,v1,v2..." con "" dove il modello non ha il dato. */
  private fun downloadChunk(product: HfProduct, location: BenchLocation, model: String, start: LocalDate, end: LocalDate): List<String> {
    val days = ChronoUnit.DAYS.between(start, end).toInt() + 1
    val url = "https://historical-forecast-api.open-meteo.com/v1/forecast" +
      "?latitude=${location.latitude}&longitude=${location.longitude}" +
      "&start_date=$start&end_date=$end" +
      "&${product.resolution.parameter}=${product.variables.joinToString(",")}" +
      "&models=$model&timeformat=unixtime&timezone=UTC&format=csv"
    val weight = CallBudget.weightOf(product.variables.size, days)
    val label = "${location.name}/$model $start"
    val reply = http.get(url, weight, label)
    check(reply.status == 200) { "$label: HTTP ${reply.status} (${OpenMeteoHttp.reason(reply.body)})" }

    val csv = OpenMeteoCsv.parse(reply.body, model)
    val indices = product.variables.map { csv.columnIndex(it, model) }
    val expected = days * product.resolution.rowsPerDay
    // Una serie corta non e' "meno dati": e' un pezzo perso. Meglio fermarsi che salvarla.
    check(csv.rows.size == expected) { "$label: ${csv.rows.size} righe invece di $expected" }
    return csv.rows.map { row ->
      buildString {
        append(row.epochSeconds)
        for (i in indices) append(',').append(row.cells.getOrElse(i) { "" })
      }
    }
  }

  private fun emptyShare(lines: Collection<String>, variables: List<String>): String {
    val empty = IntArray(variables.size)
    for (line in lines) {
      val cells = line.split(",")
      for (i in variables.indices) if (cells.getOrNull(i + 1).isNullOrEmpty()) empty[i]++
    }
    return variables.indices.joinToString(" ") { i ->
      "${variables[i]}=${String.format(Locale.ROOT, "%.1f", 100.0 * empty[i] / lines.size)}%"
    }
  }

  /**
   * Il quarto d'ora storico esiste molto indietro, ma non e' detto che sia "vero": dove il modello
   * non ha un passo di 15 minuti l'API interpola l'orario. Si annota il primo istante non nullo e
   * quanto spesso i quattro quarti di un'ora bagnata sono identici (un'ora interpolata li ha
   * tutti uguali) accanto al file, cosi' chi lo usa sa cosa sta leggendo.
   */
  private fun writeQuarterHourlyMeta(target: File) {
    val rows = target.readLines().drop(1).map { it.split(",") }
    val firstNonNull = rows.firstOrNull { it.getOrNull(1)?.isNotEmpty() == true }?.get(0)?.toLong()
    val byHour = rows.filter { it[1].isNotEmpty() }.groupBy { it[0].toLong() / 3_600L }
    val wetHours = byHour.values.filter { quarters -> quarters.size == 4 && quarters.any { it[1].toDouble() > 0.0 } }
    val flat = wetHours.count { quarters -> quarters.map { it[1] }.distinct().size == 1 }
    val meta = buildString {
      appendLine("righe,${rows.size}")
      appendLine("righe non nulle,${rows.count { it[1].isNotEmpty() }}")
      appendLine("primo istante non nullo,${firstNonNull?.let { java.time.Instant.ofEpochSecond(it) } ?: "-"}")
      appendLine("ore bagnate,${wetHours.size}")
      appendLine("ore bagnate con i quattro quarti uguali,$flat")
    }
    File(target.parentFile, target.nameWithoutExtension + ".meta.txt").writeText(meta)
    log("  $meta".trimEnd().replace("\n", " | "))
  }

  private fun writeAtomically(target: File, text: String) {
    val part = File(target.path + ".part")
    part.writeText(text)
    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  private fun compact(day: LocalDate): String = day.toString().replace("-", "")

  companion object {
    const val ATTRIBUTION = "Weather data by Open-Meteo.com (CC BY 4.0) - uso non commerciale."

    /**
     * Un limite superiore alla fine dell'ultimo slot di una serie che arriva a [to] compreso: la
     * mezzanotte UTC successiva. L'ultima riga e' quella delle 23:00 (orario) o delle 23:45 (quarto
     * d'ora): contare da mezzanotte e' prudente per entrambe.
     */
    fun lastSlotEndMillis(to: LocalDate): Long =
      to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** I pezzi [da, a] (estremi inclusi) da [chunkDays] giorni che coprono [from, to]. */
    fun chunks(from: LocalDate, to: LocalDate, chunkDays: Int): List<Pair<LocalDate, LocalDate>> {
      require(chunkDays >= 1)
      val result = ArrayList<Pair<LocalDate, LocalDate>>()
      var start = from
      while (!start.isAfter(to)) {
        val end = minOf(start.plusDays(chunkDays - 1L), to)
        result += start to end
        start = end.plusDays(1)
      }
      return result
    }
  }
}
