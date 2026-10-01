package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import dev.pampa.fluidweather.testbench.tiers.TierScenarios
import java.util.concurrent.ConcurrentHashMap

/** In che fase dell'addestramento del v3 siamo: decide da quali anni nascono le tabelle di baseline. */
enum class V3Stage {
  /** Scelta degli iperparametri: si impara su TRAIN, si sceglie su VALIDATION. TEST non esiste. */
  TUNE,

  /** Riaddestramento finale su TRAIN + VALIDATION con gli iperparametri congelati. TEST resta fuori. */
  REFIT,
}

/** L'anno a cui appartiene una riga (per `t0`): le tabelle di ogni anno nascono dagli altri. */
enum class TableRegion { Y1, Y2, Y3, TEST }

/**
 * Le date che dividono il banco in anni, e da li' le tabelle di baseline di ogni riga.
 *
 * Le feature di baseline del v3 (climatologia, persistenza, regola barometrica: colonne 30-38) si
 * leggono da tabelle costruite sulla storia, e **una riga di addestramento non puo' leggere tabelle che
 * contengono il proprio anno**: la sua etichetta sarebbe dentro la feature, e il modello imparerebbe a
 * fidarsene piu' di quanto il telefono (che le ha sempre costruite sul passato) possa permettersi. Da
 * qui il coltello a rotazione per anno ("jackknife"): ogni anno di addestramento prende le tabelle dagli
 * altri anni non di prova, con un margine di [bufferMillis] (30 ore: la finestra piu' lunga e la
 * finalita' dell'archivio) su ogni confine che tocca il proprio anno.
 *
 * - **Y1** = dall'inizio del pannello al primo settembre (2022-11-24..2023-08-31), **Y2** = l'anno dopo,
 *   **Y3** = VALIDATION, **TEST** = l'anno di prova (mai usato per addestrare ne' per costruire tabelle di un
 *   anno di addestramento).
 * - Le righe di VALIDATION e di TEST hanno le tabelle che il gate da' alle baseline: i due anni che le
 *   precedono, fino a [finalityMillis] ore prima ([TierPeriod.historyUntilMillis]).
 *
 * | righe | fase TUNE | fase REFIT |
 * |-------|-----------|------------|
 * | Y1 | Y2 dopo il margine, fino alla storia di VALIDATION | Y2 + Y3 dopo il margine, fino alla storia di TEST |
 * | Y2 | Y1 fino al margine | Y1 + Y3 dopo il margine, fino alla storia di TEST |
 * | Y3 | la storia del gate di VALIDATION | uguale |
 * | TEST | (non esiste) | la storia del gate di TEST |
 *
 * Fuori Europa il pannello comincia il 2023-12-27: nelle righe di Y2 della fase TUNE non c'e' un Y1 da cui
 * prendere le tabelle, e quelle localita' non hanno tabelle locali (vedi [JackknifeSlices]).
 */
class JackknifePlan(
  /** L'inizio di Y1: il primo istante con righe. */
  val startMillis: Long,
  /** Y1 finisce e Y2 comincia qui. */
  val y2StartMillis: Long,
  /** Y2 finisce e Y3 (VALIDATION) comincia qui. */
  val y3StartMillis: Long,
  /** Y3 finisce e TEST comincia qui. */
  val testStartMillis: Long,
  val bufferMillis: Long = BUFFER_MILLIS,
  /** Quanto prima dell'inizio di un periodo finisce la storia delle sue baseline (vedi [TierPeriod.historyUntilMillis]). */
  val finalityMillis: Long = TierScenarios.ISSUE_OFFSET_BOUND_MILLIS + TruthPanel.FINALITY_MILLIS,
) {

  init {
    require(startMillis < y2StartMillis && y2StartMillis < y3StartMillis && y3StartMillis < testStartMillis) { "anni fuori ordine" }
  }

  /** L'anno di una riga con ancora [t0Millis]; null prima dell'inizio. */
  fun regionOf(t0Millis: Long): TableRegion? = when {
    t0Millis < startMillis -> null
    t0Millis < y2StartMillis -> TableRegion.Y1
    t0Millis < y3StartMillis -> TableRegion.Y2
    t0Millis < testStartMillis -> TableRegion.Y3
    else -> TableRegion.TEST
  }

  /** Il primo istante dell'anno [region]. */
  fun startOf(region: TableRegion): Long = when (region) {
    TableRegion.Y1 -> startMillis
    TableRegion.Y2 -> y2StartMillis
    TableRegion.Y3 -> y3StartMillis
    TableRegion.TEST -> testStartMillis
  }

  /** La fine (esclusa) dell'anno [region]; per TEST, l'infinito. */
  fun endOf(region: TableRegion): Long = when (region) {
    TableRegion.Y1 -> y2StartMillis
    TableRegion.Y2 -> y3StartMillis
    TableRegion.Y3 -> testStartMillis
    TableRegion.TEST -> Long.MAX_VALUE
  }

  /**
   * Gli slot (fine dello slot, estremi inclusi) da cui nascono le tabelle delle righe di [region] nella
   * fase [stage]. Vuoto se l'anno non esiste in quella fase (TEST in TUNE).
   */
  fun ranges(stage: V3Stage, region: TableRegion): List<LongRange> {
    val y3History = y3StartMillis - finalityMillis
    val testHistory = testStartMillis - finalityMillis
    val all = when (stage) {
      V3Stage.TUNE -> when (region) {
        TableRegion.Y1 -> listOf((y2StartMillis + bufferMillis)..y3History)
        TableRegion.Y2 -> listOf(startMillis..(y2StartMillis - bufferMillis))
        TableRegion.Y3 -> listOf(startMillis..y3History)
        TableRegion.TEST -> emptyList()
      }

      V3Stage.REFIT -> when (region) {
        TableRegion.Y1 -> listOf((y2StartMillis + bufferMillis)..testHistory)
        TableRegion.Y2 -> listOf(startMillis..(y2StartMillis - bufferMillis), (y3StartMillis + bufferMillis)..testHistory)
        TableRegion.Y3 -> listOf(startMillis..y3History)
        TableRegion.TEST -> listOf(y2StartMillis..testHistory)
      }
    }
    return all.filter { !it.isEmpty() }
  }

  /**
   * Da quando un'etichetta non si puo' usare per addestrare le righe di [region] nella fase [stage]: una
   * finestra il cui ultimo slot si chiude dopo questo istante e' fuori. In TUNE le righe di addestramento
   * (Y1, Y2) non vedono VALIDATION; in REFIT nessuna riga vede TEST. Le righe di VALIDATION in TUNE non sono
   * di addestramento e non hanno limite.
   */
  fun labelCutoffMillis(stage: V3Stage, region: TableRegion): Long = when (stage) {
    V3Stage.TUNE -> if (region == TableRegion.Y1 || region == TableRegion.Y2) y3StartMillis else Long.MAX_VALUE
    V3Stage.REFIT -> testStartMillis
  }

  companion object {
    /** Margine ai confini che toccano il proprio anno: trenta ore. */
    const val BUFFER_MILLIS: Long = 30 * 3_600_000L

    /** Gli anni veri del piano: TRAIN 2022-11-24..2024-08-31 (Y1 fino al primo settembre 2023), VALIDATION, TEST. */
    fun real(): JackknifePlan = JackknifePlan(
      startMillis = TierPeriods.TRAIN.firstMillis,
      y2StartMillis = TierPeriods.TEST.historyFromMillis,
      y3StartMillis = TierPeriods.VALIDATION.firstMillis,
      testStartMillis = TierPeriods.TEST.firstMillis,
    )
  }
}

/**
 * Le tabelle di baseline di ogni (fase, anno), costruite una volta sola dalle localita' date, con il
 * riferimento di tutti i posti sommato sugli stessi slot ([HonestBaselines.buildFromRanges]).
 *
 * Il risultato di una fase e di un anno e' un [HonestBaselines] per localita': `priors(tier)` da' le tabelle
 * del posto (null se nella storia di quell'anno la localita' non ha pannello: le righe FRESH e STALE ripiegano
 * allora sul solo riferimento di tutti i posti, e le righe NONE si buttano) e `pooledOnly()` il solo
 * riferimento. Sicuro fra thread.
 */
class JackknifeSlices(
  val plan: JackknifePlan,
  private val inputs: List<LocationInputs>,
) {

  private val cache = ConcurrentHashMap<Pair<V3Stage, TableRegion>, Map<String, HonestBaselines>>()

  /** Le baseline delle righe di [region] nella fase [stage]. */
  fun honest(stage: V3Stage, region: TableRegion): Map<String, HonestBaselines> =
    cache.computeIfAbsent(stage to region) {
      val ranges = plan.ranges(stage, region)
      require(ranges.isNotEmpty()) { "nessuna tabella per $region in $stage" }
      HonestBaselines.buildFromRanges(ranges, inputs)
    }
}
