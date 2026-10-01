package dev.pampa.fluidweather.testbench.data

import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * La sonda che mette alla prova `T_final`, cioe' dopo quante ore dalla fine di uno slot orario
 * i valori del pannello nell'historical-forecast-api smettono di cambiare.
 *
 * L'API incolla "le prime ore di ogni corsa": uno slot recente vale quello che la corsa di un
 * momento fa ne dice, e cambia quando la corsa successiva (4-12 ore dopo, a seconda del modello
 * e del suo ritardo) lo sostituisce. Se la verita' si scarica troppo presto, il banco e l'app
 * giudicano su un numero che poi si sposta. Il margine scelto e' 24 ore dopo la fine dell'ultimo
 * slot; questa sonda serve a dimostrarlo con i dati, non a crederci.
 *
 * Ogni lancio salva in `data/stability/<yyyyMMddHH>.csv` i valori attuali delle ultime 72 ore per
 * la localita' di casa (precipitazione e pressione dei tre modelli del pannello), e, se ci sono
 * istantanee precedenti, le confronta con la nuova: per ogni slot presente in entrambe, quanto e'
 * cambiato, raggruppato per **quanto era vecchio lo slot quando e' stata presa l'istantanea
 * precedente** (0-6, 6-12, 12-18, 18-24, 24-36, 36-72 ore dalla fine dello slot). Il numero che
 * interessa e' l'ultimo gruppo prima di 24 ore e il primo dopo: se oltre le 24 ore gli slot non
 * si spostano piu' (o quasi), `T_final` = 24 h regge.
 *
 * Gli slot sono quelli di [dev.pampa.fluidweather.nowcast.truth.RainWindows]: la riga delle 11:00
 * e' l'accumulo di (10:00, 11:00], chiuso alle 11:00. Contare l'eta' dall'inizio dello slot
 * spostava ogni slot nel gruppo di un'ora prima, e proprio lo slot di 24 ore esatte — quello che
 * decide `T_final` — finiva fra i "18-24". La mediana e' quella del pannello, col suo quorum
 * (tutti i giudici): con due voti sarebbe una media, e la sonda misurerebbe un'altra verita'.
 *
 * Va rilanciata a distanza di ore (e di giorni): una sola istantanea non ha nulla con cui
 * confrontarsi.
 */
class StabilityProbe(
  private val http: OpenMeteoHttp,
  private val root: File = File("data/stability"),
  private val location: BenchLocation = BenchLocations.first(),
  private val models: List<String> = HfProducts.PANEL.models.map { it.model },
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val log: (String) -> Unit = ::println,
) {

  /** Una istantanea letta dal disco: serie -> (fine dello slot in ms -> valore); i nulli non ci sono. */
  class Snapshot(val stampMillis: Long, val values: Map<String, Map<Long, Double>>)

  fun run() {
    val now = nowMillis()
    val stamp = floorToHour(now)
    val text = download(now, stamp)
    root.mkdirs()
    val file = File(root, "${SingleRunFetcher.STAMP.format(Instant.ofEpochMilli(stamp))}.csv")
    val replaced = file.exists()
    // Scritta in .part e rinominata: un'istantanea a meta' sarebbe un confronto con dati inventati.
    val part = File(file.path + ".part")
    part.writeText(text)
    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    log("istantanea salvata: ${file.path} (${text.lines().count { it.isNotBlank() } - 1} slot, ${models.size} modelli)${if (replaced) " - sostituisce quella della stessa ora" else ""}")

    val latest = parseSnapshot(stamp, text)
    val previous = root.listFiles { f -> f.name.matches(Regex("""\d{10}\.csv""")) }.orEmpty()
      .mapNotNull { f -> RunArchive.parseStamp(f.nameWithoutExtension)?.let { parseSnapshot(it, f.readText()) } }
      .filter { it.stampMillis < stamp }
      .sortedBy { it.stampMillis }
    if (previous.isEmpty()) {
      log("nessuna istantanea precedente: rilancia fra qualche ora (meglio dopo 6, 12, 24 ore) per vedere la stabilita'.")
    } else {
      log(report(latest, previous))
    }
    log(HistoricalForecastFetcher.ATTRIBUTION)
  }

  /** Il CSV da salvare: le 72 ore chiuse, colonne `<variabile>_<modello>` cosi' come le manda l'API. */
  private fun download(now: Long, stamp: Long): String {
    val firstDay = Instant.ofEpochMilli(stamp - WINDOW_HOURS * HOUR).atZone(ZoneOffset.UTC).toLocalDate()
    val lastDay = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    val url = "https://historical-forecast-api.open-meteo.com/v1/forecast" +
      "?latitude=${location.latitude}&longitude=${location.longitude}" +
      "&start_date=$firstDay&end_date=$lastDay" +
      "&hourly=precipitation,pressure_msl&models=${models.joinToString(",")}" +
      "&timeformat=unixtime&timezone=UTC&format=csv"
    val label = "stabilita' ${location.name}"
    val reply = http.get(url, CallBudget.weightOf(2, 4, 1, models.size), label)
    check(reply.status == 200) { "$label: HTTP ${reply.status} (${OpenMeteoHttp.reason(reply.body)})" }

    val csv = OpenMeteoCsv.parse(reply.body)
    for (model in models) csv.columnIndex("precipitation", model)
    // Solo gli slot chiusi (la riga T chiude lo slot (T-1h, T]): uno slot che finisce dopo
    // "adesso" e' ancora una previsione. Le 72 ore chiuse sono le righe in (stamp-72h, stamp].
    val from = stamp - WINDOW_HOURS * HOUR
    val closed = csv.rows.filter { it.epochSeconds * 1_000L in (from + 1)..stamp }
    check(closed.isNotEmpty()) { "$label: nessuno slot chiuso nella risposta" }
    return buildString {
      append("time,").appendLine(csv.columns.joinToString(","))
      for (row in closed) append(row.epochSeconds).append(',').appendLine(row.cells.joinToString(","))
    }
  }

  fun parseSnapshot(stampMillis: Long, text: String): Snapshot {
    val csv = OpenMeteoCsv.parse(text)
    val byModel = models.associateWith { model ->
      val index = csv.columnIndex("precipitation", model)
      csv.rows.mapNotNull { row -> row.number(index)?.let { row.epochSeconds * 1_000L to it } }.toMap()
    }
    // La mediana del pannello con il quorum del pannello: tutti i giudici, come [TruthPanel.slotValue].
    val slots = csv.rows.map { it.epochSeconds * 1_000L }
    val median = slots.mapNotNull { slot ->
      val votes = models.mapNotNull { byModel.getValue(it)[slot] }
      if (votes.size < models.size) null else slot to TruthPanel.median(votes)
    }.toMap()
    return Snapshot(stampMillis, byModel + (PANEL_MEDIAN to median))
  }

  /** La tabella per bucket di ritardo: ogni istantanea precedente contro la piu' recente. */
  fun report(latest: Snapshot, previous: List<Snapshot>): String {
    val observations = previous.flatMap { observe(it, latest) }
    return buildString {
      appendLine("=== Stabilita' del pannello a ${location.name}: istantanea ${stampLabel(latest.stampMillis)} contro ${previous.size} precedenti (${previous.joinToString(", ") { stampLabel(it.stampMillis) }})")
      appendLine("    ritardo = ore fra la fine dello slot e l'istantanea PRECEDENTE; cambio = |valore nuovo - valore vecchio| (mm/h); flip = attraversa ${FLIP_THRESHOLD_MM} mm")
      appendLine(String.format(Locale.ROOT, "  %-8s %-22s %6s %10s %8s %8s", "ritardo", "serie", "slot", "media |d|", "max |d|", "flip"))
      val series = listOf(PANEL_MEDIAN) + models
      for ((label, range) in BUCKETS) {
        for (name in series) {
          val inBucket = observations.filter { it.series == name && it.lagHours in range }
          if (inBucket.isEmpty()) {
            appendLine(String.format(Locale.ROOT, "  %-8s %-22s %6d %10s %8s %8s", label, name, 0, "-", "-", "-"))
            continue
          }
          val changes = inBucket.map { Math.abs(it.after - it.before) }
          val flips = inBucket.count { (it.before >= FLIP_THRESHOLD_MM - EPS) != (it.after >= FLIP_THRESHOLD_MM - EPS) }
          appendLine(
            String.format(
              Locale.ROOT, "  %-8s %-22s %6d %10.3f %8.2f %7.1f%%",
              label, name, inBucket.size, changes.average(), changes.max(), 100.0 * flips / inBucket.size,
            ),
          )
        }
      }
    }.trimEnd()
  }

  class Observation(val series: String, val lagHours: Int, val before: Double, val after: Double)

  /**
   * Gli slot presenti (non nulli) in entrambe le istantanee, col ritardo che avevano in [earlier]:
   * ore intere fra la fine dello slot e l'ora piena dell'istantanea (per difetto, quindi prudente).
   */
  fun observe(earlier: Snapshot, later: Snapshot): List<Observation> {
    val result = ArrayList<Observation>()
    for ((series, before) in earlier.values) {
      val after = later.values[series] ?: continue
      for ((slotEnd, oldValue) in before) {
        val newValue = after[slotEnd] ?: continue
        val lag = (earlier.stampMillis - slotEnd) / HOUR
        if (lag < 0) continue
        result += Observation(series, lag.toInt(), oldValue, newValue)
      }
    }
    return result
  }

  private fun stampLabel(stampMillis: Long): String = SingleRunFetcher.STAMP.format(Instant.ofEpochMilli(stampMillis))

  companion object {
    const val PANEL_MEDIAN = "mediana"
    const val FLIP_THRESHOLD_MM = 0.1
    private const val EPS = 1e-9
    private const val HOUR = 3_600_000L
    private const val WINDOW_HOURS = 72L

    /** I bucket di ritardo, in ore dalla fine dello slot. */
    val BUCKETS: List<Pair<String, IntRange>> = listOf(
      "0-6" to 0..5,
      "6-12" to 6..11,
      "12-18" to 12..17,
      "18-24" to 18..23,
      "24-36" to 24..35,
      "36-72" to 36..71,
    )

    fun floorToHour(millis: Long): Long = Math.floorDiv(millis, HOUR) * HOUR
  }
}
