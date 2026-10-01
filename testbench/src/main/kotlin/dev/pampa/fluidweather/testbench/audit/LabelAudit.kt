package dev.pampa.fluidweather.testbench.audit

import dev.pampa.fluidweather.nowcast.climatology.LocalBaselines
import dev.pampa.fluidweather.nowcast.climatology.WindowClimatology
import dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary
import dev.pampa.fluidweather.nowcast.scoring.DayBlockBootstrap
import dev.pampa.fluidweather.nowcast.scoring.ProperScores
import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.data.BenchLocation
import dev.pampa.fluidweather.testbench.data.BenchLocations
import dev.pampa.fluidweather.testbench.data.ForecastArchive
import dev.pampa.fluidweather.testbench.data.HourlyRecord
import dev.pampa.fluidweather.testbench.data.HourlySeries
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.metrics.Contingency
import dev.pampa.fluidweather.testbench.train.TrainCommand
import java.io.File
import java.time.Instant
import java.util.Locale

private const val HOUR_MILLIS = RainWindows.HOUR_MILLIS

/**
 * La griglia oraria comune di tutte le serie dell'audit: [size] istanti a passo di un'ora da
 * [startMillis]. Serie diverse (ERA5, giudici, contesto) hanno inizi e fini diversi e buchi diversi;
 * su una griglia sola si confrontano per indice, senza una ricerca per istante a ogni emissione.
 */
class HourGrid(val startMillis: Long, val size: Int) {

  init {
    require(size > 0) { "griglia vuota" }
    require(Math.floorMod(startMillis, HOUR_MILLIS) == 0L) { "la griglia parte da un'ora piena" }
  }

  val endMillis: Long get() = timeAt(size - 1)

  fun timeAt(index: Int): Long = startMillis + index * HOUR_MILLIS

  /** L'indice dell'istante esatto, o -1 se non e' un'ora piena della griglia. */
  fun indexOf(millis: Long): Int {
    val offset = millis - startMillis
    if (offset < 0 || offset % HOUR_MILLIS != 0L) return -1
    val index = offset / HOUR_MILLIS
    return if (index < size) index.toInt() else -1
  }

  /** Il primo indice con istante >= [millis], ritagliato alla griglia. */
  fun ceilIndex(millis: Long): Int =
    (-Math.floorDiv(-(millis - startMillis), HOUR_MILLIS)).coerceIn(0L, (size - 1).toLong()).toInt()

  /** L'ultimo indice con istante <= [millis], ritagliato alla griglia. */
  fun floorIndex(millis: Long): Int =
    Math.floorDiv(millis - startMillis, HOUR_MILLIS).coerceIn(0L, (size - 1).toLong()).toInt()
}

/**
 * Una serie di millimetri (o hPa) sulla griglia: NaN = dato assente. Il significato dell'istante e'
 * quello degli archivi — lo slot orario T copre (T-1h, T] — e lo conserva [RainWindows].
 */
class DenseSeries(val grid: HourGrid, private val values: DoubleArray) {

  init {
    require(values.size == grid.size) { "serie di ${values.size} valori su una griglia di ${grid.size}" }
  }

  /** Il valore all'istante esatto; null se fuori griglia o assente. */
  fun at(millis: Long): Double? {
    val index = grid.indexOf(millis)
    return if (index < 0) null else valueAt(index)
  }

  fun valueAt(index: Int): Double? = values[index].takeUnless { it.isNaN() }

  /** I valori presenti fino a [untilMillis] compreso, come mappa istante -> valore (l'ingresso di climatologia e baseline). */
  fun toMap(untilMillis: Long = Long.MAX_VALUE): Map<Long, Double> {
    val map = HashMap<Long, Double>(values.size * 2)
    for (i in values.indices) {
      val t = grid.timeAt(i)
      if (t > untilMillis) break
      if (!values[i].isNaN()) map[t] = values[i]
    }
    return map
  }

  /** Quota di indici vuoti in [from, to] (estremi inclusi). */
  fun emptyShare(from: Int, to: Int): Double {
    if (to < from) return Double.NaN
    var empty = 0
    for (i in from..to) if (values[i].isNaN()) empty++
    return empty.toDouble() / (to - from + 1)
  }

  /** Il primo istante con dato in [from, to], o null. */
  fun firstPresentMillis(from: Int, to: Int): Long? {
    for (i in from..to) if (!values[i].isNaN()) return grid.timeAt(i)
    return null
  }

  companion object {

    fun fromHourly(grid: HourGrid, series: HourlySeries, variable: String): DenseSeries {
      val column = series.column(variable)
      val values = DoubleArray(grid.size) { Double.NaN }
      for (k in 0 until series.size) {
        val index = grid.indexOf(series.timeAt(k))
        if (index >= 0) values[index] = column[k]
      }
      return DenseSeries(grid, values)
    }

    /** La precipitazione di un dataset ERA5 del banco (un record per ora piena, chiude lo slot). */
    fun fromRecords(grid: HourGrid, records: List<HourlyRecord>): DenseSeries {
      val values = DoubleArray(grid.size) { Double.NaN }
      for (record in records) {
        val index = grid.indexOf(record.timestampMillis)
        val mm = record.precipitationMm
        if (index >= 0 && mm != null) values[index] = mm
      }
      return DenseSeries(grid, values)
    }

    fun fromMap(grid: HourGrid, points: Map<Long, Double>): DenseSeries {
      val values = DoubleArray(grid.size) { Double.NaN }
      for ((millis, value) in points) {
        val index = grid.indexOf(millis)
        if (index >= 0) values[index] = value
      }
      return DenseSeries(grid, values)
    }

    /**
     * La mediana delle serie presenti in ogni istante, ma solo se ne rispondono almeno [minimum]:
     * e' il "consenso" della vecchia classifica (il mediano dei provider che hanno risposto).
     */
    fun median(grid: HourGrid, series: List<DenseSeries>, minimum: Int): DenseSeries {
      val values = DoubleArray(grid.size) { i ->
        val present = ArrayList<Double>(series.size)
        for (s in series) s.valueAt(i)?.let { present += it }
        if (present.size >= minimum) TruthPanel.median(present) else Double.NaN
      }
      return DenseSeries(grid, values)
    }

    /** La verita' del pannello, slot per slot, dalle regole di [TruthPanel] (quorum = tutti i giudici). */
    fun panel(grid: HourGrid, members: Map<String, DenseSeries>): DenseSeries {
      val values = DoubleArray(grid.size) { i ->
        TruthPanel.slotValue(TruthPanel.MODELS.associateWith { members[it]?.valueAt(i) }) ?: Double.NaN
      }
      return DenseSeries(grid, values)
    }
  }
}

/**
 * Le etichette di una serie su ogni emissione oraria e ogni finestra, giudicate da [RainWindows]
 * (la definizione unica: somma >= 0,2 mm sugli slot chiusi, finestra coi buchi ingiudicabile).
 * Ogni indice della griglia e' un'emissione all'ora piena: l'ancora coincide con l'emissione.
 */
class LabelMatrix(val byWindow: Array<ByteArray>) {

  /** -1 = ingiudicabile, 0 = asciutta, 1 = bagnata. */
  fun at(windowIndex: Int, issueIndex: Int): Int = byWindow[windowIndex][issueIndex].toInt()

  companion object {
    private const val UNJUDGED: Byte = -1
    private const val DRY: Byte = 0
    private const val WET: Byte = 1

    fun of(series: DenseSeries, windows: List<RainWindow>): LabelMatrix {
      val grid = series.grid
      val lookup: (Long) -> Double? = series::at
      return LabelMatrix(
        Array(windows.size) { w ->
          ByteArray(grid.size) { i ->
            when (RainWindows.outcomeFromAnchor(grid.timeAt(i), windows[w], lookup)) {
              null -> UNJUDGED
              true -> WET
              false -> DRY
            }
          }
        },
      )
    }
  }
}

/** Quante volte, su quanti: un conteggio che si somma fra localita' senza perdere nulla. */
data class RateCount(val n: Int = 0, val wet: Int = 0) {
  operator fun plus(other: RateCount) = RateCount(n + other.n, wet + other.wet)
  val rate: Double get() = if (n == 0) Double.NaN else wet.toDouble() / n
}

private operator fun Contingency.plus(other: Contingency) = Contingency(
  hits + other.hits,
  misses + other.misses,
  falseAlarms + other.falseAlarms,
  correctNegatives + other.correctNegatives,
)

private val Contingency.total: Int get() = hits + misses + falseAlarms + correctNegatives

/** Quante volte le due etichette dicono la stessa cosa. */
val Contingency.agreement: Double
  get() = if (total == 0) Double.NaN else (hits + correctNegatives).toDouble() / total

/** Un periodo di emissioni orarie (estremi inclusi) sul quale l'audit conta. */
class AuditPeriod(val name: String, val firstMillis: Long, val lastMillis: Long) {
  fun first(grid: HourGrid): Int = grid.ceilIndex(firstMillis)
  fun last(grid: HourGrid): Int = grid.floorIndex(lastMillis)

  val description: String get() = "emissioni orarie ${day(firstMillis)}T${hour(firstMillis)}Z .. ${day(lastMillis)}T${hour(lastMillis)}Z"

  private fun day(millis: Long) = Instant.ofEpochMilli(millis).toString().substring(0, 10)
  private fun hour(millis: Long) = Instant.ofEpochMilli(millis).toString().substring(11, 13)
}

/**
 * I conteggi di una finestra in un periodo, tutti sommabili fra localita'.
 *
 * L'**insieme comune** ([common]) e' l'insieme delle emissioni che tutte le etichette in
 * confronto (ERA5, PANNELLO, VECCHIO, dove esistono) riescono a giudicare: senza, un'etichetta
 * con piu' buchi (il pannello fuori dall'Europa prima del 2023-12-27) verrebbe confrontata su
 * giorni diversi e il confronto direbbe la copertura, non la pioggia.
 */
data class WindowStats(
  /** Emissioni del periodo. */
  val issues: Int,
  /** Sorgente -> emissioni che la sorgente riesce a giudicare da sola (la sua copertura). */
  val judged: Map<String, Int>,
  val common: Int,
  /** Sorgente -> bagnate / giudicate, sull'insieme comune. */
  val base: Map<String, RateCount>,
  val panelVsEra: Contingency?,
  val oldVsEra: Contingency?,
  /** Stagione (DJF, MAM, JJA, SON) -> tasso del pannello, sull'insieme comune. */
  val seasonPanel: List<RateCount>,
  val seasonEra: List<RateCount>,
) {
  operator fun plus(other: WindowStats) = WindowStats(
    issues = issues + other.issues,
    judged = sumInts(judged, other.judged),
    common = common + other.common,
    base = (base.keys + other.base.keys).associateWith { (base[it] ?: RateCount()) + (other.base[it] ?: RateCount()) },
    panelVsEra = plusNullable(panelVsEra, other.panelVsEra),
    oldVsEra = plusNullable(oldVsEra, other.oldVsEra),
    seasonPanel = sumCounts(seasonPanel, other.seasonPanel),
    seasonEra = sumCounts(seasonEra, other.seasonEra),
  )

  private fun sumInts(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> =
    (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }

  private fun plusNullable(a: Contingency?, b: Contingency?): Contingency? = when {
    a == null -> b
    b == null -> a
    else -> a + b
  }

  private fun sumCounts(a: List<RateCount>, b: List<RateCount>): List<RateCount> = when {
    a.isEmpty() -> b
    b.isEmpty() -> a
    else -> a.indices.map { a[it] + b[it] }
  }
}

/** Le finestre di un periodo, piu' quando il pannello e' completo su tutte e tre insieme. */
data class PeriodStats(
  val windows: Map<String, WindowStats>,
  /** Emissioni con tutte e tre le finestre del pannello giudicabili. */
  val completeIssues: Int,
  val firstComplete: Long?,
  val lastComplete: Long?,
) {
  operator fun plus(other: PeriodStats) = PeriodStats(
    windows = (windows.keys + other.windows.keys).associateWith {
      val a = windows[it]
      val b = other.windows[it]
      if (a == null) b!! else if (b == null) a else a + b
    },
    completeIssues = completeIssues + other.completeIssues,
    firstComplete = listOfNotNull(firstComplete, other.firstComplete).minOrNull(),
    lastComplete = listOfNotNull(lastComplete, other.lastComplete).maxOrNull(),
  )
}

/**
 * I Brier caso per caso di piu' previsori sugli stessi casi, con il giorno di ciascuno: serve a
 * mediare, a sommare fra localita' (si concatena) e a dare l'intervallo della differenza fra due
 * previsori con il bootstrap a blocchi di giorni.
 */
class ScoreLog(val names: List<String>) {

  private val days = ArrayList<Long>()
  private val scores: List<ArrayList<Double>> = names.map { ArrayList() }

  var wet: Int = 0
    private set

  val n: Int get() = days.size

  fun add(epochDay: Long, occurred: Boolean, probabilities: DoubleArray) {
    require(probabilities.size == names.size) { "${probabilities.size} probabilita' per ${names.size} previsori" }
    days += epochDay
    if (occurred) wet++
    for (k in names.indices) scores[k] += ProperScores.brier(probabilities[k], occurred)
  }

  val baseRate: Double get() = if (n == 0) Double.NaN else wet.toDouble() / n

  fun brier(name: String): Double {
    val list = scores[indexOf(name)]
    return if (list.isEmpty()) Double.NaN else list.sum() / list.size
  }

  /** Brier([a]) - Brier([b]) appaiato, con l'intervallo del bootstrap a blocchi di giorni. */
  fun delta(a: String, b: String, bootstrap: DayBlockBootstrap = DayBlockBootstrap()): BootstrapSummary =
    bootstrap.paired(scores[indexOf(a)], scores[indexOf(b)], days)

  private fun indexOf(name: String): Int {
    val index = names.indexOf(name)
    require(index >= 0) { "previsore '$name' assente; ci sono $names" }
    return index
  }

  companion object {
    fun merge(logs: List<ScoreLog>): ScoreLog? {
      if (logs.isEmpty()) return null
      val merged = ScoreLog(logs.first().names)
      for (log in logs) {
        require(log.names == merged.names) { "previsori diversi: ${log.names} vs ${merged.names}" }
        merged.days += log.days
        for (k in merged.names.indices) merged.scores[k] += log.scores[k]
        merged.wet += log.wet
      }
      return merged
    }
  }
}

/** Le serie di una localita', gia' sulla griglia. Tutto tranne il pannello puo' mancare. */
class LocationInput(
  val location: BenchLocation,
  val grid: HourGrid,
  /** Precipitazione ERA5 (la "pioggia vera" del banco). */
  val era5: DenseSeries?,
  /** Precipitazione dei giudici, per modello (il pannello vuole tutti e tre). */
  val members: Map<String, DenseSeries>,
  /** Pressione al mare del giudice Meteo-France (la regola barometrica legge la sua tendenza a 3 ore). */
  val memberMsl: DenseSeries?,
  /** Precipitazione di best_match stitched: il "contesto" del presente com'e' sul telefono. */
  val context: DenseSeries?,
  /** Precipitazione degli altri provider del vecchio consenso, per modello. */
  val providers: Map<String, DenseSeries>,
)

/** Quanto e' bucato un giudice nel periodo intero, e da quando ha dati. */
data class MemberGap(val emptyShare: Double, val firstPresentMillis: Long?)

/** Il Brier della climatologia del pannello contro un tasso costante, per etichetta di verita' e finestra. */
class ClimateResult(
  /** Finestra -> emissioni giudicate nella storia di addestramento. */
  val trainingSamples: Map<String, Int>,
  /** Finestra -> tasso complessivo della storia di addestramento (il "costante"). */
  val constantRate: Map<String, Double?>,
  /** Sorgente dell'etichetta (PANNELLO, ERA5) -> finestra -> Brier di clima, costante, sempre-0. */
  val logs: Map<String, Map<String, ScoreLog>>,
)

/** Il Brier delle baseline locali sul test, per finestra, sugli stessi casi. */
class BaselineResult(
  /** Finestra -> emissioni del test con etichetta del pannello (prima di chiedere gli ingressi). */
  val judged: Map<String, Int>,
  /** Finestra -> Brier di clima, persistenza, barometrica, sempre-0 sui casi con entrambi gli ingressi. */
  val logs: Map<String, ScoreLog>,
)

/**
 * Quante ore "bagnate" (ERA5 o la sorgente segnano almeno [LabelAudit.IDENTITY_MIN_MM]) hanno lo
 * stesso valore in ERA5 e nella sorgente. Un modello indipendente da ERA5 ha valori diversi ora
 * per ora; una quota vicina a 1 dice che la sorgente, in quella localita', e' ERA5 stessa (o ne
 * discende), e che ogni confronto fra le due e' circolare.
 */
data class ValueIdentity(val wetHours: Int = 0, val identical: Int = 0) {
  operator fun plus(other: ValueIdentity) = ValueIdentity(wetHours + other.wetHours, identical + other.identical)
  val share: Double get() = if (wetHours == 0) Double.NaN else identical.toDouble() / wetHours
}

class LocationResult(
  val location: BenchLocation,
  /** Le sorgenti presenti, nell'ordine di visualizzazione. */
  val sources: List<String>,
  /** Nome del periodo -> conteggi. */
  val periods: Map<String, PeriodStats>,
  /** Giudice -> buchi nel periodo intero; "msl" e' la pressione del giudice Meteo-France. */
  val gaps: Map<String, MemberGap>,
  /** Sorgente (giudici e membri del vecchio consenso) -> identita' oraria con ERA5, nel periodo intero. */
  val identity: Map<String, ValueIdentity>,
  /** Anno -> identita' oraria del contesto best_match con ERA5: dice da quando l'archivio stitched e' una previsione vera. */
  val contextIdentityByYear: Map<Int, ValueIdentity>,
  val climate: ClimateResult?,
  val baselines: BaselineResult?,
)

/** Un gruppo di localita' che si somma: una sola, l'Europa dell'app, o tutte. */
class AuditGroup(val name: String, val results: List<LocationResult>) {

  fun stats(period: String): PeriodStats? =
    results.mapNotNull { it.periods[period] }.reduceOrNull { a, b -> a + b }

  fun window(period: String, window: String): WindowStats? =
    results.mapNotNull { it.periods[period]?.windows?.get(window) }.reduceOrNull { a, b -> a + b }

  fun climate(label: String, window: String): ScoreLog? =
    ScoreLog.merge(results.mapNotNull { it.climate?.logs?.get(label)?.get(window) })

  fun trainingSamples(window: String): Int = results.sumOf { it.climate?.trainingSamples?.get(window) ?: 0 }

  fun baselines(window: String): ScoreLog? =
    ScoreLog.merge(results.mapNotNull { it.baselines?.logs?.get(window) })

  fun baselineJudged(window: String): Int = results.sumOf { it.baselines?.judged?.get(window) ?: 0 }

  fun identity(source: String): ValueIdentity? =
    results.mapNotNull { it.identity[source] }.reduceOrNull { a, b -> a + b }
}

/**
 * `label-audit`: l'etichetta "bagnato" messa alla prova sui dati veri, prima di congelare il pannello.
 *
 * **Perche'.** Il pannello dei giudici (meteofrance, ukmo, gem) e' stato scelto con una pre-verifica
 * a Sesto; prima di farne la verita' di tutte le righe della classifica bisogna sapere, su tutte le
 * localita' e non solo a casa, quanto e' piu' asciutto di ERA5, quanto si fida del vecchio consenso
 * (da cui arriva il difetto "verita' circolare"), dove ha buchi e se una climatologia locale
 * costruita su di lui, fuori campione, e' un avversario sensato per il barometro (D1).
 *
 * **Come.** Ogni etichetta e' giudicata da [RainWindows], la definizione unica, su ogni emissione
 * oraria: ERA5, ciascun giudice, la mediana del [TruthPanel] e il vecchio consenso dell'app
 * (mediana di best_match, icon, ecmwf 0,25, gfs e meteofrance dove rispondono, almeno tre). I confronti
 * fra etichette usano solo le emissioni che tutte sanno giudicare (l'insieme comune), i conteggi
 * si sommano fra localita' come conteggi (le localita' con piu' emissioni pesano di piu').
 *
 * **Fuori campione.** La climatologia e le baseline locali si costruiscono con la sola storia del
 * pannello precedente al periodo di test ([TrainCommand.CUTOFF_MILLIS], 2025-09-01): le finestre che
 * sconfinano nel test restano incomplete e non entrano nei conteggi, quindi nessun millimetro del
 * test arriva alla costruzione.
 *
 * Dati meteo di Open-Meteo.com (CC BY 4.0).
 */
object LabelAudit {

  const val ERA5 = "ERA5"
  const val PANEL = "PANNELLO"
  const val OLD = "VECCHIO"
  const val BEST_MATCH = "best_match"
  const val ICON = "icon_seamless"
  const val GFS = "gfs_seamless"
  const val ECMWF = "ecmwf_ifs025"
  const val METEOFRANCE = "meteofrance_seamless"

  /** Il vecchio consenso dell'app: chi votava nella classifica pioggia (meteofrance e' AROME). */
  val OLD_MEMBERS: List<String> = listOf(BEST_MATCH, ICON, ECMWF, GFS, METEOFRANCE)

  /** Per il vecchio consenso servono almeno tre voti: con meno la "mediana" e' un'opinione sola. */
  const val OLD_QUORUM = 3

  /** Sorgenti nell'ordine delle colonne. */
  private val SOURCE_ORDER = listOf(ERA5, PANEL, OLD) + TruthPanel.MODELS + listOf(BEST_MATCH, ICON, GFS, ECMWF)

  private val SHORT_NAMES = mapOf(
    METEOFRANCE to "mf", "ukmo_seamless" to "ukmo", "gem_seamless" to "gem",
    BEST_MATCH to "best", ICON to "icon", GFS to "gfs", ECMWF to "ecmwf025",
  )

  const val MSL = "msl"
  const val TEST = "TEST"
  const val FULL = "INTERO"

  private val EUROPE = setOf("sesto-fiorentino", "milano", "genova", "innsbruck", "bergen", "reykjavik")
  private val SEASONS = WindowClimatology.SEASON_NAMES

  /** Primo istante degli archivi ERA5 del banco: l'inizio della griglia. */
  private const val GRID_START_MILLIS = 1_661_990_400_000L // 2022-09-01T00:00Z

  /** Quanto si e' tenuto fuori dalla storia per il test: l'ultima emissione dell'anno di test. */
  private val TEST_LAST_ISSUE_MILLIS = Instant.parse("2026-08-31T23:00:00Z").toEpochMilli()

  private val WINDOWS = RainWindows.ALL

  private val ATTRIBUTION = TruthPanel.ATTRIBUTION

  // ------------------------------------------------------------------ esecuzione sui dati veri

  /**
   * Legge `<dataRoot>/` (ERA5 di `<loc>.csv`, serie stitched di `hf/`), fa l'audit e ritorna il
   * testo del rapporto. Le sorgenti assenti (contesto, provider non ancora scaricati) non fermano
   * l'audit: la loro colonna manca e un avviso lo dice.
   */
  fun run(dataRoot: File = File("data"), log: (String) -> Unit = ::println): String {
    val archive = ForecastArchive(dataRoot)
    // Cosa manca -> in quali localita': un file assente per dieci localita' e' una riga, non dieci.
    val missing = LinkedHashMap<String, MutableList<String>>()

    val panelEnd = BenchLocations.firstNotNullOfOrNull { location ->
      TruthPanel.MODELS.mapNotNull { archive.hourly(location, it)?.lastMillis }.maxOrNull()
    } ?: return "label-audit: nessuna serie del pannello in ${dataRoot.path}/hf - prima `fetch-hf panel`"
    val grid = HourGrid(GRID_START_MILLIS, ((panelEnd - GRID_START_MILLIS) / HOUR_MILLIS).toInt() + 1)

    val test = AuditPeriod(TEST, TrainCommand.CUTOFF_MILLIS, TEST_LAST_ISSUE_MILLIS)
    // L'ultima emissione del periodo intero e' quella la cui finestra piu' lunga (6 h) chiude
    // sull'ultimo slot del pannello: oltre, l'assenza di etichetta sarebbe solo la fine dei dati.
    val maxHours = WINDOWS.maxOf { it.toHours }
    val full = AuditPeriod(FULL, TruthPanel.AVAILABLE_FROM_MILLIS, grid.endMillis - maxHours * HOUR_MILLIS)

    val results = BenchLocations.map { location ->
      log("  audit ${location.name}...")
      auditLocation(load(location, archive, dataRoot, grid, missing), test, full)
    }
    val warnings = missing.map { (what, places) -> "MANCA $what per ${places.joinToString(", ")}" }
    return render(AuditData(grid, test, full, results, warnings))
  }

  private fun load(
    location: BenchLocation,
    archive: ForecastArchive,
    dataRoot: File,
    grid: HourGrid,
    missing: MutableMap<String, MutableList<String>>,
  ): LocationInput {
    fun lack(what: String) {
      missing.getOrPut(what) { ArrayList() } += location.name
    }

    fun precipitation(model: String): DenseSeries? =
      archive.hourly(location, model)?.let { DenseSeries.fromHourly(grid, it, "precipitation") }

    val era5File = File(dataRoot, "${location.name}.csv")
    val era5 = if (era5File.exists()) {
      DenseSeries.fromRecords(grid, StationDataset.parse(era5File.readLines(), location).records)
    } else {
      lack("l'archivio ERA5 data/<loc>.csv (niente confronto con la pioggia vera)")
      null
    }
    val members = TruthPanel.MODELS.mapNotNull { model -> precipitation(model)?.let { model to it } }.toMap()
    for (model in TruthPanel.MODELS - members.keys) lack("il giudice $model (fetch-hf panel: il pannello non si forma)")
    val context = precipitation(BEST_MATCH)
    if (context == null) lack("il contesto best_match (fetch-hf context: niente persistenza ne' vecchio consenso completo)")
    val providers = listOf(ICON, GFS, ECMWF).mapNotNull { model -> precipitation(model)?.let { model to it } }.toMap()
    for (model in listOf(ICON, GFS, ECMWF) - providers.keys) lack("$model (fetch-hf providers: il vecchio consenso ne fa a meno)")
    val memberMsl = archive.hourly(location, METEOFRANCE)
      ?.let { DenseSeries.fromHourly(grid, it, "pressure_msl") }
    return LocationInput(location, grid, era5, members, memberMsl, context, providers)
  }

  // ------------------------------------------------------------------ l'audit di una localita'

  /**
   * Tutti i conteggi di una localita': per ogni periodo in [periods] le statistiche di etichetta,
   * per il periodo di [test] la climatologia e le baseline costruite sulla storia che lo precede.
   */
  fun auditLocation(
    input: LocationInput,
    test: AuditPeriod,
    full: AuditPeriod,
    windows: List<RainWindow> = WINDOWS,
  ): LocationResult {
    val grid = input.grid
    val panel = DenseSeries.panel(grid, input.members)

    // Il vecchio consenso: chi votava prima, dove ha risposto (best_match e' il contesto, meteofrance e' anche un giudice).
    val oldMembers = OLD_MEMBERS.mapNotNull { name ->
      if (name == BEST_MATCH) input.context else input.providers[name] ?: input.members[name]
    }
    val oldSeries = oldMembers.takeIf { it.size >= OLD_QUORUM }?.let { DenseSeries.median(grid, it, OLD_QUORUM) }

    val series = LinkedHashMap<String, DenseSeries>()
    input.era5?.let { series[ERA5] = it }
    series[PANEL] = panel
    oldSeries?.let { series[OLD] = it }
    for (model in TruthPanel.MODELS) input.members[model]?.let { series[model] = it }
    input.context?.let { series[BEST_MATCH] = it }
    for (model in listOf(ICON, GFS, ECMWF)) input.providers[model]?.let { series[model] = it }
    val ordered = SOURCE_ORDER.filter { it in series }
    val labels = ordered.associateWith { LabelMatrix.of(series.getValue(it), windows) }

    val seasonAt = ByteArray(grid.size) { WindowClimatology.seasonOf(grid.timeAt(it)).toByte() }
    val periods = listOf(test, full).associate { it.name to periodStats(grid, labels, windows, it, seasonAt) }

    val climatology = climatologyOf(input, panel, test, windows)
    return LocationResult(
      location = input.location,
      sources = ordered,
      periods = periods,
      gaps = gapsOf(input, full),
      identity = identityOf(input.era5, series.filterKeys { it != ERA5 && it != PANEL && it != OLD }, full, grid),
      contextIdentityByYear = contextIdentityByYear(input.era5, input.context, full, grid),
      climate = climatology?.let { evaluateClimate(input, it, labels, windows, test) },
      baselines = climatology?.let { evaluateBaselines(input, panel, it, labels, windows, test) },
    )
  }

  private fun periodStats(
    grid: HourGrid,
    labels: Map<String, LabelMatrix>,
    windows: List<RainWindow>,
    period: AuditPeriod,
    seasonAt: ByteArray,
  ): PeriodStats {
    val first = period.first(grid)
    val last = period.last(grid)
    val names = labels.keys.toList()
    val panel = labels.getValue(PANEL)
    val era = labels[ERA5]
    val old = labels[OLD]

    val byWindow = LinkedHashMap<String, WindowStats>()
    for ((w, window) in windows.withIndex()) {
      val rows = names.map { labels.getValue(it).byWindow[w] }
      val panelRow = panel.byWindow[w]
      val eraRow = era?.byWindow?.get(w)
      val oldRow = old?.byWindow?.get(w)

      val judged = IntArray(names.size)
      val counted = IntArray(names.size)
      val wet = IntArray(names.size)
      val panelHits = IntArray(4)
      val oldHits = IntArray(4)
      val seasonPanel = Array(SEASONS.size) { IntArray(2) }
      val seasonEra = Array(SEASONS.size) { IntArray(2) }
      var common = 0

      for (i in first..last) {
        for (s in names.indices) if (rows[s][i] >= 0) judged[s]++
        if (panelRow[i] < 0) continue
        if (eraRow != null && eraRow[i] < 0) continue
        if (oldRow != null && oldRow[i] < 0) continue
        common++
        for (s in names.indices) {
          val value = rows[s][i].toInt()
          if (value >= 0) {
            counted[s]++
            wet[s] += value
          }
        }
        val season = seasonAt[i].toInt()
        seasonPanel[season][0]++
        seasonPanel[season][1] += panelRow[i].toInt()
        if (eraRow != null) {
          val truth = eraRow[i].toInt() == 1
          tally(panelHits, panelRow[i].toInt() == 1, truth)
          if (oldRow != null) tally(oldHits, oldRow[i].toInt() == 1, truth)
          seasonEra[season][0]++
          seasonEra[season][1] += eraRow[i].toInt()
        }
      }

      byWindow[window.label] = WindowStats(
        issues = last - first + 1,
        judged = names.indices.associate { names[it] to judged[it] },
        common = common,
        base = names.indices.associate { names[it] to RateCount(counted[it], wet[it]) },
        panelVsEra = if (eraRow != null) Contingency(panelHits[0], panelHits[1], panelHits[2], panelHits[3]) else null,
        oldVsEra = if (eraRow != null && oldRow != null) Contingency(oldHits[0], oldHits[1], oldHits[2], oldHits[3]) else null,
        seasonPanel = seasonPanel.map { RateCount(it[0], it[1]) },
        seasonEra = if (eraRow != null) seasonEra.map { RateCount(it[0], it[1]) } else emptyList(),
      )
    }

    var complete = 0
    var firstComplete: Int? = null
    var lastComplete: Int? = null
    for (i in first..last) {
      if (windows.indices.all { panel.byWindow[it][i] >= 0 }) {
        complete++
        if (firstComplete == null) firstComplete = i
        lastComplete = i
      }
    }
    return PeriodStats(
      windows = byWindow,
      completeIssues = complete,
      firstComplete = firstComplete?.let { grid.timeAt(it) },
      lastComplete = lastComplete?.let { grid.timeAt(it) },
    )
  }

  /** hits, misses, falseAlarms, correctNegatives. */
  private fun tally(counts: IntArray, predicted: Boolean, occurred: Boolean) {
    val index = when {
      predicted && occurred -> 0
      !predicted && occurred -> 1
      predicted -> 2
      else -> 3
    }
    counts[index]++
  }

  private fun identityOf(
    era5: DenseSeries?,
    others: Map<String, DenseSeries>,
    full: AuditPeriod,
    grid: HourGrid,
  ): Map<String, ValueIdentity> {
    if (era5 == null) return emptyMap()
    val from = full.first(grid)
    val to = full.last(grid)
    return others.mapValues { (_, series) ->
      var wetHours = 0
      var identical = 0
      for (i in from..to) {
        val a = era5.valueAt(i) ?: continue
        val b = series.valueAt(i) ?: continue
        if (a < IDENTITY_MIN_MM && b < IDENTITY_MIN_MM) continue
        wetHours++
        if (Math.abs(a - b) < IDENTITY_TOLERANCE_MM) identical++
      }
      ValueIdentity(wetHours, identical)
    }
  }

  /**
   * L'identita' del contesto con ERA5 anno per anno: dove l'archivio stitched non ha una corsa
   * di modello per quel posto e quel periodo, Open-Meteo risponde con la rianalisi, e il
   * "contesto" della persistenza sarebbe ERA5 stessa. Lo si vede dal passaggio da ~1 a ~0 fra un anno e l'altro.
   */
  private fun contextIdentityByYear(
    era5: DenseSeries?,
    context: DenseSeries?,
    full: AuditPeriod,
    grid: HourGrid,
  ): Map<Int, ValueIdentity> {
    if (era5 == null || context == null) return emptyMap()
    val byYear = java.util.TreeMap<Int, ValueIdentity>()
    for (i in full.first(grid)..full.last(grid)) {
      val a = era5.valueAt(i) ?: continue
      val b = context.valueAt(i) ?: continue
      if (a < IDENTITY_MIN_MM && b < IDENTITY_MIN_MM) continue
      val year = java.time.LocalDate.ofEpochDay(Math.floorDiv(grid.timeAt(i), 86_400_000L)).year
      val same = if (Math.abs(a - b) < IDENTITY_TOLERANCE_MM) 1 else 0
      byYear[year] = (byYear[year] ?: ValueIdentity()) + ValueIdentity(1, same)
    }
    return byYear
  }

  /** Sotto mezzo decimo di millimetro un'ora e' asciutta per entrambe: non dice nulla sull'identita'. */
  const val IDENTITY_MIN_MM = 0.05

  /** Gli archivi scrivono al centesimo: due valori che differiscono meno di cosi' sono lo stesso valore. */
  private const val IDENTITY_TOLERANCE_MM = 0.005

  private fun gapsOf(input: LocationInput, full: AuditPeriod): Map<String, MemberGap> {
    val grid = input.grid
    val from = full.first(grid)
    val to = grid.size - 1
    val gaps = LinkedHashMap<String, MemberGap>()
    for ((model, series) in input.members) {
      gaps[model] = MemberGap(series.emptyShare(from, to), series.firstPresentMillis(from, to))
    }
    input.memberMsl?.let { gaps[MSL] = MemberGap(it.emptyShare(from, to), it.firstPresentMillis(from, to)) }
    return gaps
  }

  // ------------------------------------------------------------------ climatologia e baseline fuori campione

  /** La climatologia del pannello dalla sola storia che precede il test; null se non c'e' storia. */
  private fun climatologyOf(
    input: LocationInput,
    panel: DenseSeries,
    test: AuditPeriod,
    windows: List<RainWindow>,
  ): WindowClimatology? =
    WindowClimatology.build(panel.toMap(untilMillis = test.firstMillis), input.location.longitude, windows)

  private fun evaluateClimate(
    input: LocationInput,
    climatology: WindowClimatology,
    labels: Map<String, LabelMatrix>,
    windows: List<RainWindow>,
    test: AuditPeriod,
  ): ClimateResult {
    val grid = input.grid
    val first = test.first(grid)
    val last = test.last(grid)
    val logs = LinkedHashMap<String, Map<String, ScoreLog>>()
    for (source in listOf(PANEL, ERA5)) {
      val matrix = labels[source] ?: continue
      val byWindow = LinkedHashMap<String, ScoreLog>()
      for ((w, window) in windows.withIndex()) {
        val log = ScoreLog(listOf("clima", "costante", "sempre-0"))
        val constant = climatology.overallRate(window.label)
        for (i in first..last) {
          val label = matrix.at(w, i)
          if (label < 0 || constant == null) continue
          val issue = grid.timeAt(i)
          val clima = climatology.rate(window.label, issue) ?: continue
          log.add(DayBlockBootstrap.epochDayOf(issue), label == 1, doubleArrayOf(clima, constant, 0.0))
        }
        byWindow[window.label] = log
      }
      logs[source] = byWindow
    }
    return ClimateResult(
      trainingSamples = windows.associate { it.label to climatology.samples(it.label) },
      constantRate = windows.associate { it.label to climatology.overallRate(it.label) },
      logs = logs,
    )
  }

  /**
   * Persistenza (dal contesto best_match: "piove adesso" = l'ultimo slot chiuso) e regola barometrica
   * (dalla tendenza MSL a tre ore del giudice Meteo-France), tarate sulla storia pre-test e provate
   * sul test. Si confrontano sugli stessi casi: quelli col pannello giudicabile e con entrambi gli
   * ingressi, cosi' nessuna baseline vince perche' ha giudicato meno giorni difficili.
   */
  private fun evaluateBaselines(
    input: LocationInput,
    panel: DenseSeries,
    climatology: WindowClimatology,
    labels: Map<String, LabelMatrix>,
    windows: List<RainWindow>,
    test: AuditPeriod,
  ): BaselineResult? {
    val context = input.context ?: return null
    val msl = input.memberMsl ?: return null
    val grid = input.grid
    val first = test.first(grid)
    val last = test.last(grid)
    val matrix = labels.getValue(PANEL)

    val baselines = LocalBaselines.build(
      truthMm = panel.toMap(untilMillis = test.firstMillis),
      climatology = climatology,
      rainNowMm = context.toMap(untilMillis = test.firstMillis),
      mslHpa = msl.toMap(untilMillis = test.firstMillis),
      windows = windows,
    )
    val mslAll = msl.toMap()

    val judged = LinkedHashMap<String, Int>()
    val logs = LinkedHashMap<String, ScoreLog>()
    for ((w, window) in windows.withIndex()) {
      val log = ScoreLog(listOf("clima", "persistenza", "barometrica", "sempre-0"))
      var count = 0
      for (i in first..last) {
        val label = matrix.at(w, i)
        if (label < 0) continue
        count++
        val issue = grid.timeAt(i)
        val clima = climatology.rate(window.label, issue) ?: continue
        // L'emissione dell'audit e' a un'ora piena: lo slot "adesso" e' quello che si chiude in quell'istante,
        // quindi ritardo zero (prima fascia di LB2). Con LB2 la persistenza e' leggermente piu' severa di
        // quella di LB1 (che contava ritardo zero su tutti i casi): i numeri dell'audit si spostano di poco.
        val slotNow = RainWindows.lastClosedSlotEnd(issue)
        val persistence = baselines.persistence(window.label, issue, context.at(slotNow), slotNow) ?: continue
        val barometric = baselines.barometric(window.label, issue, LocalBaselines.trendAt(mslAll, issue)) ?: continue
        log.add(DayBlockBootstrap.epochDayOf(issue), label == 1, doubleArrayOf(clima, persistence, barometric, 0.0))
      }
      judged[window.label] = count
      logs[window.label] = log
    }
    return BaselineResult(judged, logs)
  }

  // ------------------------------------------------------------------ il rapporto

  class AuditData(
    val grid: HourGrid,
    val test: AuditPeriod,
    val full: AuditPeriod,
    val results: List<LocationResult>,
    val warnings: List<String>,
  )

  fun groupsOf(results: List<LocationResult>): List<AuditGroup> {
    val groups = results.map { AuditGroup(it.location.name, listOf(it)) }.toMutableList()
    val europe = results.filter { it.location.name in EUROPE }
    if (europe.size > 1 && europe.size < results.size) groups += AuditGroup("EUROPA (${europe.size})", europe)
    if (results.size > 1) groups += AuditGroup("TUTTE (${results.size})", results)
    // Dove il contesto best_match coincide con ERA5 il vecchio consenso e la persistenza giudicano con la
    // verita' stessa: un gruppo a parte, senza quelle localita', da' il confronto pulito.
    val independent = results.filterNot(::isCircular)
    if (independent.size > 1 && independent.size < results.size) {
      groups += AuditGroup("INDIPENDENTI (${independent.size})", independent)
    }
    return groups
  }

  fun render(data: AuditData): String {
    val groups = groupsOf(data.results)
    val pooled = groups.filter { it.results.size > 1 }
    val sources = SOURCE_ORDER.filter { source -> data.results.any { source in it.sources } }
    val periods = listOf(data.test, data.full)
    val out = StringBuilder()

    out.appendLine("=== LABEL AUDIT - l'etichetta \"bagnato\": pannello, ERA5 e vecchio consenso, sui dati veri ===")
    out.appendLine()
    out.appendLine("Generato da `label-audit`. $ATTRIBUTION.")
    out.appendLine()
    out.appendLine("Definizione (RainWindows, identica per ogni sorgente): una finestra e' bagnata se la somma degli slot orari")
    out.appendLine("chiusi e' >= ${RainWindows.WET_THRESHOLD_MM} mm; 0-1h / 1-3h / 3-6h contano dall'ora piena dell'emissione (qui: ogni ora piena);")
    out.appendLine("uno slot mancante rende la finestra ingiudicabile. Sorgenti:")
    out.appendLine("  ERA5      precipitazione della rianalisi (data/<loc>.csv): la pioggia \"vera\" del banco")
    out.appendLine("  mf/ukmo/gem  i giudici del pannello (meteofrance/ukmo/gem _seamless, historical-forecast stitched)")
    out.appendLine("  PANNELLO  mediana dei tre giudici, quorum = tutti e tre (${TruthPanel.VERSION}); e' la verita' proposta")
    out.appendLine("  VECCHIO   consenso dell'app di prima: mediana di best_match, icon, ecmwf025, gfs, meteofrance dove rispondono (>= $OLD_QUORUM)")
    out.appendLine("  best/icon/gfs/ecmwf025  i membri del vecchio consenso da soli")
    out.appendLine("Periodi:")
    for (period in periods) out.appendLine("  ${period.name.padEnd(9)} ${period.description}")
    out.appendLine("  (INTERO = periodo intero dei giudici, fino all'ultima emissione la cui finestra 3-6h cade nei dati; l'ERA5 del banco finisce il 2026-08-31T23Z)")
    out.appendLine("Insieme comune: i confronti fra etichette usano solo le emissioni che ERA5, PANNELLO e VECCHIO (dove esistono)")
    out.appendLine("sanno giudicare tutte, cosi' il confronto dice la pioggia e non la copertura. Fra localita' si sommano i conteggi.")
    out.appendLine("EUROPA = sesto-fiorentino, milano, genova, innsbruck, bergen, reykjavik (dove vive l'app).")
    out.appendLine("INDIPENDENTI = TUTTE senza le localita' CIRCOLARI, dove il contesto best_match coincide con ERA5 (vedi IDENTITA'): lo")
    out.appendLine("stesso best_match e' nel vecchio consenso e nella persistenza, che li' giudicano con la verita' stessa. Compare solo se esiste.")
    out.appendLine()

    summary(out, data, pooled)
    for (period in periods) coverage(out, data, groups, period)
    gaps(out, data)
    identity(out, data, groups)
    for (period in periods) baseRates(out, data, groups, sources, period)
    for (period in periods) agreement(out, data, groups, period)
    for (period in periods) seasons(out, data, groups, period)
    climate(out, data, groups)
    baselines(out, data, groups, pooled)
    notes(out, data, pooled)

    out.appendLine()
    out.appendLine(ATTRIBUTION)
    return out.toString()
  }

  private fun summary(out: StringBuilder, data: AuditData, pooled: List<AuditGroup>) {
    out.appendLine("--- SINTESI (periodo ${TEST}, ${data.test.description})")
    for (group in pooled) {
      out.appendLine("  ${group.name}")
      for (window in WINDOWS) {
        val ws = group.window(TEST, window.label) ?: continue
        val panelVs = ws.panelVsEra
        val oldVs = ws.oldVsEra
        out.appendLine(
          String.format(
            Locale.ROOT, "    %-5s base ERA5 %s  PANNELLO %s  VECCHIO %s | CSI vs ERA5: PANNELLO %s  VECCHIO %s | accordo: PANNELLO %s  VECCHIO %s",
            window.label, f3(ws.base[ERA5]?.rate), f3(ws.base[PANEL]?.rate), f3(ws.base[OLD]?.rate),
            f3(panelVs?.csi), f3(oldVs?.csi), f3(panelVs?.agreement), f3(oldVs?.agreement),
          ),
        )
      }
      val clima = WINDOWS.joinToString("  ") { w -> "${w.label} ${f3(group.climate(PANEL, w.label)?.brier("clima"))}" }
      out.appendLine("    Brier climatologia del pannello (etichetta PANNELLO, fuori campione): $clima")
      val best = WINDOWS.joinToString("  ") { w ->
        val log = group.baselines(w.label)
        val top = log?.let { l -> listOf("clima", "persistenza", "barometrica").minByOrNull { l.brier(it) } }
        "${w.label} ${if (log == null || top == null) "-" else "${f3(log.brier(top))} ($top)"}"
      }
      out.appendLine("    Brier della migliore fra clima, persistenza e barometrica (stessi casi): $best")
    }
    out.appendLine()
  }

  private fun coverage(out: StringBuilder, data: AuditData, groups: List<AuditGroup>, period: AuditPeriod) {
    out.appendLine("--- COPERTURA, periodo ${period.name}: quota di emissioni con etichetta giudicabile (${period.description})")
    out.appendLine("    PANNELLO completo = tutte e tre le finestre giudicabili insieme; primo/ultimo = prima/ultima emissione cosi'.")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %7s | %-20s | %-20s | %-20s | %-7s %-10s %-10s",
        "localita'", "emiss.", "PANNELLO 0-1 1-3 3-6", "ERA5     0-1 1-3 3-6", "VECCHIO  0-1 1-3 3-6", "compl.", "primo", "ultimo",
      ),
    )
    for (group in groups) {
      val stats = group.stats(period.name) ?: continue
      val any = stats.windows.values.first()
      fun shares(source: String): String = WINDOWS.joinToString(" ") { w ->
        val ws = stats.windows.getValue(w.label)
        val count = ws.judged[source]
        String.format(Locale.ROOT, "%6s", if (count == null) "-" else pct(count.toDouble() / ws.issues))
      }
      out.appendLine(
        String.format(
          Locale.ROOT, "  %-18s %7d | %-20s | %-20s | %-20s | %-7s %-10s %-10s",
          group.name, any.issues, shares(PANEL), shares(ERA5), shares(OLD),
          pct(stats.completeIssues.toDouble() / any.issues), date(stats.firstComplete), date(stats.lastComplete),
        ),
      )
    }
    out.appendLine()
  }

  private fun gaps(out: StringBuilder, data: AuditData) {
    out.appendLine("--- BUCHI DEI GIUDICI nel periodo ${FULL}: ore senza dato e primo istante con dato")
    out.appendLine("    (un giudice senza dato toglie l'ora al pannello: quorum = tutti; msl = pressione di meteofrance, serve alla regola barometrica)")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-18s | %-19s | %-19s | %-19s | %-19s",
        "localita'", "meteofrance vuoti/da", "ukmo vuoti/da", "gem vuoti/da", "msl-mf vuoti/da",
      ),
    )
    for (result in data.results) {
      fun cell(key: String): String {
        val gap = result.gaps[key] ?: return "-"
        return String.format(Locale.ROOT, "%6s %-11s", pct(gap.emptyShare), date(gap.firstPresentMillis))
      }
      out.appendLine(
        String.format(
          Locale.ROOT, "  %-18s | %-19s | %-19s | %-19s | %-19s",
          result.location.name, cell(TruthPanel.MODELS[0]), cell(TruthPanel.MODELS[1]), cell(TruthPanel.MODELS[2]), cell(MSL),
        ),
      )
    }
    out.appendLine()
  }

  private fun identity(out: StringBuilder, data: AuditData, groups: List<AuditGroup>) {
    val sources = SOURCE_ORDER.filter { source -> data.results.any { source in it.identity } }
    if (sources.isEmpty()) return
    out.appendLine("--- IDENTITA' CON ERA5, periodo $FULL: fra le ore in cui ERA5 o la sorgente segnano almeno $IDENTITY_MIN_MM mm, quota con lo stesso valore")
    out.appendLine("    Una sorgente indipendente da ERA5 sta vicino a 0; vicino a 1 vuol dire che in quella localita' la sorgente coincide con ERA5")
    out.appendLine("    e ogni confronto fra le due (e la persistenza tarata sul suo contesto) e' circolare.")
    out.appendLine(
      "  " + String.format(Locale.ROOT, "%-18s %9s", "localita'", "ore bagn.") +
        sources.joinToString("") { String.format(Locale.ROOT, " %8s", SHORT_NAMES[it] ?: it) },
    )
    for (group in groups) {
      val hours = group.identity(sources.first())?.wetHours
      out.appendLine(
        "  " + String.format(Locale.ROOT, "%-18s %9s", group.name, hours?.toString() ?: "-") +
          sources.joinToString("") { String.format(Locale.ROOT, " %8s", f3(group.identity(it)?.share)) },
      )
    }
    out.appendLine("    (ore bagn. = ore con ERA5 o la prima sorgente >= $IDENTITY_MIN_MM mm; per le altre colonne il conteggio cambia di poco)")
    out.appendLine()

    val years = data.results.flatMap { it.contextIdentityByYear.keys }.toSortedSet()
    if (years.isEmpty()) return
    out.appendLine("--- IDENTITA' DEL CONTESTO best_match CON ERA5, per anno (quota di ore bagnate con lo stesso valore; fra parentesi le ore)")
    out.appendLine("    Dove passa da ~1 a ~0 l'archivio stitched diventa una previsione vera: prima di quella data il contesto e' ERA5 stessa.")
    out.appendLine("  " + String.format(Locale.ROOT, "%-18s", "localita'") + years.joinToString("") { String.format(Locale.ROOT, " %14s", it) })
    for (result in data.results) {
      out.appendLine(
        "  " + String.format(Locale.ROOT, "%-18s", result.location.name) + years.joinToString("") { year ->
          val cell = result.contextIdentityByYear[year]
          String.format(Locale.ROOT, " %14s", if (cell == null) "-" else "${f3(cell.share)} (${cell.wetHours})")
        },
      )
    }
    out.appendLine()
  }

  private fun baseRates(out: StringBuilder, data: AuditData, groups: List<AuditGroup>, sources: List<String>, period: AuditPeriod) {
    for (window in WINDOWS) {
      out.appendLine("--- TASSI BASE, finestra ${window.label}, periodo ${period.name}: bagnate / giudicate sull'insieme comune")
      out.appendLine(
        "  " + String.format(Locale.ROOT, "%-18s %7s", "localita'", "n comune") +
          sources.joinToString("") { String.format(Locale.ROOT, " %8s", SHORT_NAMES[it] ?: it) },
      )
      for (group in groups) {
        val ws = group.window(period.name, window.label) ?: continue
        out.appendLine(
          "  " + String.format(Locale.ROOT, "%-18s %7d", group.name, ws.common) +
            sources.joinToString("") { String.format(Locale.ROOT, " %8s", f3(ws.base[it]?.rate)) },
        )
      }
      out.appendLine()
    }
  }

  private fun agreement(out: StringBuilder, data: AuditData, groups: List<AuditGroup>, period: AuditPeriod) {
    for (window in WINDOWS) {
      out.appendLine("--- ACCORDO E POD/FAR/CSI CONTRO ERA5, finestra ${window.label}, periodo ${period.name} (insieme comune)")
      out.appendLine(
        String.format(
          Locale.ROOT, "  %-18s %7s | %-39s | %-39s",
          "localita'", "n", "PANNELLO  accordo   POD   FAR   CSI  bias", "VECCHIO   accordo   POD   FAR   CSI  bias",
        ),
      )
      for (group in groups) {
        val ws = group.window(period.name, window.label) ?: continue
        out.appendLine(
          String.format(
            Locale.ROOT, "  %-18s %7d | %-39s | %-39s",
            group.name, ws.common, scoreCells(ws.panelVsEra), scoreCells(ws.oldVsEra),
          ),
        )
      }
      out.appendLine()
    }
  }

  private fun scoreCells(c: Contingency?): String =
    if (c == null) "-" else String.format(
      Locale.ROOT, "%9s %7s %5s %5s %5s", f3(c.agreement), f3(c.pod), f3(c.far), f3(c.csi), f2(c.frequencyBias),
    )

  private fun seasons(out: StringBuilder, data: AuditData, groups: List<AuditGroup>, period: AuditPeriod) {
    out.appendLine("--- STAGIONI, periodo ${period.name}: tasso base per stagione dell'ancora (insieme comune); PANNELLO | ERA5 sulle stesse emissioni")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %-5s %6s | %s | %s",
        "localita'", "fin.", "n.min",
        SEASONS.joinToString(" ") { String.format(Locale.ROOT, "%6s", it) },
        SEASONS.joinToString(" ") { String.format(Locale.ROOT, "%6s", it) },
      ),
    )
    for (group in groups) {
      for (window in WINDOWS) {
        val ws = group.window(period.name, window.label) ?: continue
        val nMin = ws.seasonPanel.minOfOrNull { it.n } ?: 0
        val panel = SEASONS.indices.joinToString(" ") { String.format(Locale.ROOT, "%6s", f3(ws.seasonPanel.getOrNull(it)?.rate)) }
        val era = SEASONS.indices.joinToString(" ") { String.format(Locale.ROOT, "%6s", f3(ws.seasonEra.getOrNull(it)?.rate)) }
        out.appendLine(String.format(Locale.ROOT, "  %-18s %-5s %6d | %s | %s", group.name, window.label, nMin, panel, era))
      }
    }
    out.appendLine()
  }

  private fun climate(out: StringBuilder, data: AuditData, groups: List<AuditGroup>) {
    for (label in listOf(PANEL, ERA5)) {
      if (data.results.none { it.climate?.logs?.containsKey(label) == true }) continue
      out.appendLine("--- CLIMATOLOGIA del pannello costruita PRIMA del test, Brier sul test, etichetta di verita' $label")
      out.appendLine("    clima = cella stagione x ora solare (16 celle) ristretta; costante = tasso complessivo della storia pre-test; sempre-0 = p 0.")
      out.appendLine("    BSS = 1 - Brier(clima) / Brier(costante). delta = Brier(clima) - Brier(costante) appaiato, IC95% dal bootstrap a blocchi di giorni (negativo = la cella aiuta).")
      out.appendLine(
        String.format(
          Locale.ROOT, "  %-18s %-5s %9s %7s %7s %8s %7s %8s %8s %7s  %s",
          "localita'", "fin.", "n.storia", "n.test", "base", "p.cost.", "clima", "costante", "sempre-0", "BSS", "delta clima-costante [IC95%]",
        ),
      )
      for (group in groups) {
        for (window in WINDOWS) {
          val log = group.climate(label, window.label) ?: continue
          val constant = if (group.results.size == 1) group.results[0].climate?.constantRate?.get(window.label) else null
          val delta = if (log.n > 0) log.delta("clima", "costante") else null
          out.appendLine(
            String.format(
              Locale.ROOT, "  %-18s %-5s %9d %7d %7s %8s %7s %8s %8s %7s  %s",
              group.name, window.label, group.trainingSamples(window.label), log.n, f3(log.baseRate), f3(constant),
              f4(log.brier("clima")), f4(log.brier("costante")), f4(log.brier("sempre-0")),
              f3(ProperScores.bss(log.brier("clima"), log.brier("costante"))), interval(delta),
            ),
          )
        }
      }
      out.appendLine()
    }
  }

  private fun baselines(out: StringBuilder, data: AuditData, groups: List<AuditGroup>, pooled: List<AuditGroup>) {
    if (data.results.none { it.baselines != null }) {
      out.appendLine("--- BASELINE LOCALI: non calcolabili (servono il contesto best_match e la pressione del giudice meteofrance)")
      out.appendLine()
      return
    }
    out.appendLine("--- BASELINE LOCALI costruite PRIMA del test, Brier sul test, etichetta PANNELLO, sugli stessi casi")
    out.appendLine("    persistenza = P(bagnata | classe della pioggia dell'ultima ora chiusa di best_match); barometrica = P(bagnata | classe della")
    out.appendLine("    tendenza MSL a 3 h del giudice meteofrance); entrambe ristrette verso la climatologia (k = ${LocalBaselines.SHRINKAGE_PSEUDO_COUNTS}).")
    out.appendLine("    La persistenza e' quella tarata sul ritardo (LocalBaselines LB2): qui l'emissione e' a un'ora piena e lo slot 'adesso' e' quello che si")
    out.appendLine("    chiude in quell'istante, cioe' ritardo zero = prima fascia (ritardi 1-2 h in costruzione). Rispetto a LB1 la baseline si sposta di poco.")
    out.appendLine("    n = casi con entrambi gli ingressi; su.giud. = emissioni del test col pannello giudicabile; migliore = minimo fra clima, pers., baro.")
    out.appendLine("    BSS = 1 - Brier(migliore) / Brier(clima).")
    out.appendLine(
      String.format(
        Locale.ROOT, "  %-18s %-5s %7s %8s %7s | %7s %7s %7s %8s | %-7s %7s %7s",
        "localita'", "fin.", "n", "su.giud.", "base", "clima", "pers.", "baro.", "sempre-0", "miglior", "Brier", "BSS",
      ),
    )
    for (group in groups) {
      for (window in WINDOWS) {
        val log = group.baselines(window.label) ?: continue
        val best = listOf("clima", "persistenza", "barometrica").minByOrNull { log.brier(it).let { b -> if (b.isNaN()) Double.MAX_VALUE else b } }
        val bestBrier = best?.let { log.brier(it) } ?: Double.NaN
        out.appendLine(
          String.format(
            Locale.ROOT, "  %-18s %-5s %7d %8d %7s | %7s %7s %7s %8s | %-7s %7s %7s",
            group.name, window.label, log.n, group.baselineJudged(window.label), f3(log.baseRate),
            f4(log.brier("clima")), f4(log.brier("persistenza")), f4(log.brier("barometrica")), f4(log.brier("sempre-0")),
            (best ?: "-").take(7), f4(bestBrier), f3(ProperScores.bss(bestBrier, log.brier("clima"))),
          ),
        )
      }
    }
    out.appendLine()
    out.appendLine("--- DIFFERENZE APPAIATE DI BRIER sul test (bootstrap a blocchi di giorni, IC95%): negativo = la prima e' meglio della seconda")
    for (group in pooled) {
      for (window in WINDOWS) {
        val log = group.baselines(window.label) ?: continue
        if (log.n == 0) continue
        out.appendLine(
          String.format(
            Locale.ROOT, "  %-12s %-5s persistenza-clima %-36s barometrica-clima %s",
            group.name, window.label, interval(log.delta("persistenza", "clima")), interval(log.delta("barometrica", "clima")),
          ),
        )
      }
    }
    out.appendLine()
  }

  /** Gli avvisi di copertura e le letture che il lettore non deve ricostruire a mano. */
  private fun notes(out: StringBuilder, data: AuditData, pooled: List<AuditGroup>) {
    val lines = ArrayList<String>()
    lines += data.warnings
    lines += coverageWarnings(data)
    for (result in data.results) {
      for ((source, identity) in result.identity) {
        if (identity.wetHours >= CIRCULAR_MIN_HOURS && identity.share > CIRCULAR_SHARE) {
          val verdict = if (source == BEST_MATCH) " (e il vecchio consenso e la persistenza che lo leggono giudicano con ERA5)" else ""
          lines += "CIRCOLARE ${result.location.name}: ${SHORT_NAMES[source] ?: source} ha lo stesso valore di ERA5 nel ${pct(identity.share)} delle ore bagnate " +
            "(${identity.identical}/${identity.wetHours}): in questa localita' non e' indipendente da ERA5$verdict"
        }
      }
      // Sul periodo intero una localita' puo' restare sotto soglia e avere comunque anni in cui il
      // contesto e' ERA5 (Reykjavik fino al 2023): la persistenza tarata su quegli anni ha imparato
      // dalla pioggia vera e sul test legge una previsione. Resta nel gruppo, ma va detto.
      if (!isCircular(result)) {
        val years = result.contextIdentityByYear.filter { (_, identity) ->
          identity.wetHours >= CIRCULAR_MIN_HOURS && identity.share > CIRCULAR_SHARE
        }
        if (years.isNotEmpty()) {
          lines += "CIRCOLARE IN PARTE ${result.location.name}: il contesto best_match coincide con ERA5 negli anni " +
            years.entries.joinToString(", ") { (year, identity) -> "$year (${pct(identity.share)})" } +
            ": la persistenza tarata su quegli anni ha imparato da ERA5, sul test legge una previsione"
        }
      }
    }
    for (group in pooled) {
      for (window in WINDOWS) {
        val ws = group.window(TEST, window.label) ?: continue
        val era = ws.base[ERA5]?.rate ?: continue
        val panel = ws.base[PANEL]?.rate
        val old = ws.base[OLD]?.rate
        if (panel != null && !panel.isNaN()) {
          lines += "NOTA ${group.name} ${window.label} (test): il pannello e' al ${pct(panel / era)} di ERA5 (${f3(panel)} contro ${f3(era)})" +
            (old?.takeUnless { it.isNaN() }?.let { "; il vecchio consenso al ${pct(it / era)} (${f3(it)})" } ?: "")
        }
      }
    }
    out.appendLine("--- AVVISI E NOTE")
    if (lines.isEmpty()) out.appendLine("  nessuno")
    for (line in lines) out.appendLine("  $line")
  }

  /**
   * Gli avvisi di copertura: una localita' il cui pannello non giudica quasi tutte le emissioni
   * non e' "peggio", e' giudicata su meno giorni (e su una stagionalita' diversa); chi legge i
   * numeri deve saperlo.
   */
  fun coverageWarnings(data: AuditData): List<String> {
    val warnings = ArrayList<String>()
    // L'archivio ERA5 finisce per tutte le localita' nello stesso giorno: una riga per periodo.
    val eraShort = LinkedHashMap<String, MutableList<Double>>()
    for (result in data.results) {
      for (period in listOf(data.test, data.full)) {
        val stats = result.periods[period.name] ?: continue
        val issues = stats.windows.values.first().issues
        val share = stats.completeIssues.toDouble() / issues
        if (share < COVERAGE_WARNING_SHARE) {
          val culprits = result.gaps.filter { (key, gap) -> key != MSL && gap.emptyShare > 0.005 }
            .entries.joinToString(", ") { (key, gap) ->
              "$key vuoto al ${pct(gap.emptyShare)} delle ore, primo dato ${date(gap.firstPresentMillis)}"
            }
          warnings += "COPERTURA ${result.location.name} ${period.name}: etichetta del pannello completa sul ${pct(share)} delle emissioni " +
            "(primo ${date(stats.firstComplete)}, ultimo ${date(stats.lastComplete)})" +
            if (culprits.isNotEmpty()) "; $culprits" else ""
        }
        val era = stats.windows.values.first().judged[ERA5]
        if (era != null && era.toDouble() / issues < COVERAGE_WARNING_SHARE) {
          eraShort.getOrPut(period.name) { ArrayList() } += era.toDouble() / issues
        }
      }
      if (result.climate == null) warnings += "${result.location.name}: climatologia non costruibile (nessuna emissione giudicabile prima del test)"
      if (result.baselines == null) warnings += "${result.location.name}: baseline locali non calcolate (contesto best_match o pressione meteofrance assenti)"
    }
    for ((period, shares) in eraShort) {
      warnings += "COPERTURA ERA5 $period: giudica il ${pct(shares.min())}-${pct(shares.max())} delle emissioni in ${shares.size} localita' " +
        "(l'archivio del banco finisce prima della fine del periodo: i confronti con ERA5 usano solo l'insieme comune)"
    }
    return warnings
  }

  private const val COVERAGE_WARNING_SHARE = 0.995

  /** Oltre la meta' delle ore bagnate identiche a ERA5 (su almeno 100 ore) una sorgente non e' indipendente. */
  private const val CIRCULAR_SHARE = 0.5
  private const val CIRCULAR_MIN_HOURS = 100

  /** Il contesto best_match di questa localita' e' ERA5 stessa (nel periodo intero)? */
  private fun isCircular(result: LocationResult): Boolean {
    val identity = result.identity[BEST_MATCH] ?: return false
    return identity.wetHours >= CIRCULAR_MIN_HOURS && identity.share > CIRCULAR_SHARE
  }

  // ------------------------------------------------------------------ formati

  private fun f2(value: Double?): String =
    if (value == null || value.isNaN()) "-" else String.format(Locale.ROOT, "%.2f", value)

  private fun f3(value: Double?): String =
    if (value == null || value.isNaN()) "-" else String.format(Locale.ROOT, "%.3f", value)

  private fun f4(value: Double?): String =
    if (value == null || value.isNaN()) "-" else String.format(Locale.ROOT, "%.4f", value)

  private fun pct(fraction: Double): String =
    if (fraction.isNaN()) "-" else String.format(Locale.ROOT, "%.1f%%", fraction * 100.0)

  private fun date(millis: Long?): String = if (millis == null) "-" else Instant.ofEpochMilli(millis).toString().substring(0, 10)

  private fun interval(summary: BootstrapSummary?): String =
    if (summary == null || summary.mean.isNaN()) "-"
    else String.format(Locale.ROOT, "%+.4f [%+.4f, %+.4f]", summary.mean, summary.low, summary.high)
}
