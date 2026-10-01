package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.features.FeatureSubsets
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.TreeEnsemble
import dev.pampa.fluidweather.testbench.tiers.TierHalf
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Le leve del diario (vedi `reports/training-v3-tune.txt`, "DIARIO DELLE LEVE"): cio' che si allarga quando
 * una cella dura non passa il gate di sviluppo, nell'ordine dichiarato. Senza leve e' la griglia del piano.
 *
 * - [wideGrid] (leva 1): penalita' da 3e-5 a 1 e peso fuori Europa a passi di un quarto;
 * - [allRules] (leva 5): in NONE e NONE_NOCLIMA le regole barometriche di **tutte** e tre le finestre (e in
 *   NONE le tre climatologie), non solo quella della finestra.
 * - [gateCriterion] (leva 6, fuori dalla lista del piano ma dentro "regolarizzazione"): la scelta sulla griglia
 *   con la semantica del gate. Fra i punti il cui margine peggiore su VALIDATION A (per localita' europea,
 *   Brier del modello meno la migliore baseline della localita') e' negativo, quello con la log-loss piu'
 *   bassa; se nessuno lo e', quello col margine peggiore meno cattivo. Dove la log-loss sceglie gia' un punto
 *   che batte ogni localita', non cambia niente.
 */
data class V3Levers(val wideGrid: Boolean = false, val allRules: Boolean = false, val gateCriterion: Boolean = false) {

  val lambdas: List<Double> get() = if (wideGrid) listOf(3e-5, 1e-4, 3e-4, 1e-3, 3e-3, 1e-2, 3e-2, 0.1, 0.3, 1.0) else V3Trainer.LAMBDAS

  val nonEuropeWeights: List<Double> get() = if (wideGrid) listOf(0.0, 0.25, 0.5, 0.75, 1.0) else V3Trainer.NON_EUROPE_WEIGHTS

  /** Le colonne candidate di una cella (prima di togliere le degeneri). */
  fun columns(tier: ContextTier, window: Int): IntArray {
    val base = FeatureSubsets.columns(tier, window)
    if (!allRules || tier.hasContext) return base
    val extra = (0 until 3).map { FeatureExtractorV3.BAROMETRIC_RULE + it } +
      if (tier == ContextTier.NONE) (0 until 3).map { FeatureExtractorV3.CLIMATOLOGY + it } else emptyList()
    return (base.toList() + extra).distinct().sorted().toIntArray()
  }

  val label: String
    get() = listOfNotNull("griglia-larga".takeIf { wideGrid }, "regole-tutte".takeIf { allRules }, "criterio-gate".takeIf { gateCriterion })
      .joinToString("+").ifEmpty { "nessuna" }

  companion object {
    fun parse(label: String?): V3Levers {
      val parts = label?.split('+').orEmpty()
      return V3Levers(wideGrid = "griglia-larga" in parts, allRules = "regole-tutte" in parts, gateCriterion = "criterio-gate" in parts)
    }
  }
}

/** Gli iperparametri della logistica di una cella: l'ancora, la penalita', il peso delle localita' fuori Europa. */
data class LogisticChoice(val anchor: Int?, val lambda: Double, val nonEuropeWeight: Double) {
  val label: String
    get() = String.format(Locale.ROOT, "ancora %s, lambda %s, peso fuori Europa %.1f", anchorName(anchor), lambda, nonEuropeWeight)

  fun encode(): String = "anchor=${anchor ?: -1};lambda=$lambda;wne=$nonEuropeWeight"

  companion object {
    fun anchorName(anchor: Int?): String = anchor?.let { FeatureExtractorV3.names[it] } ?: "nessuna"

    fun decode(text: String): LogisticChoice {
      val map = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
      val anchor = map.getValue("anchor").toInt().takeIf { it >= 0 }
      return LogisticChoice(anchor, map.getValue("lambda").toDouble(), map.getValue("wne").toDouble())
    }
  }
}

/** Un punto della griglia logistica e come e' andato su VALIDATION A (Europa). */
class LogisticGridPoint(
  val choice: LogisticChoice,
  val validationLogLoss: Double,
  val converged: Boolean,
  val iterations: Int,
  /** Il margine peggiore su VALIDATION A fra le localita' europee: Brier(modello) - Brier(migliore baseline del posto). */
  val worstMargin: Double = Double.NaN,
  val worstLocation: String = "-",
)

/** Un punto della griglia degli alberi: giri migliori, log-loss di VALIDATION A e il file esportato. */
class GbmGridPoint(
  val config: GbmConfig,
  val rounds: Int,
  val roundsPlayed: Int,
  val validationLogLoss: Double,
  val ensemble: TreeEnsemble,
  val bytes: ByteArray,
  /** Guadagno per colonna globale (0..41). */
  val gain: DoubleArray,
)

/** Il risultato di una cella (livello, finestra). */
class CellResult(
  val tier: ContextTier,
  val window: Int,
  val columns: IntArray,
  val trainRows: Int,
  val trainPositives: Int,
  val logisticChoice: LogisticChoice,
  val logisticGrid: List<LogisticGridPoint>,
  /** I bag esportati: [intercetta, 42 coefficienti], ancora assorbita. */
  val bags: List<DoubleArray>,
  /** I bag non convergenti (|grad| >= 1e-6): si riportano. */
  val nonConverged: Int,
  val gbmGrid: List<GbmGridPoint>,
) {
  val key: String get() = "${tier.name}.${RainWindows.ALL[window].label}"
}

/**
 * L'addestratore del v3: per ogni (livello, finestra) la logistica (griglia di ancora x penalita' x peso
 * fuori Europa scelta sulla log-loss di VALIDATION A in Europa, poi cinque bag per blocchi di giorni) e
 * gli alberi (griglia della configurazione, arresto anticipato su VALIDATION A). Sulle righe del
 * riaddestramento finale ([refit]) gli iperparametri arrivano congelati e non si guarda nessuna validazione.
 */
class V3Trainer(
  private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 10),
  private val log: (String) -> Unit = {},
) {

  class TierTables(val standardization: Map<ContextTier, TierStandardization>, val cells: List<CellResult>)

  /** La scelta sulla griglia: [train] in modalita' addestramento, [validation] con la semantica del gate. */
  fun tune(
    train: V3Rows,
    validation: V3Rows,
    levers: V3Levers = V3Levers(),
    gbmGrid: List<GbmConfig> = GbmConfig.GRID,
    withGbm: Boolean = true,
  ): TierTables {
    val lambdas = levers.lambdas
    val nonEuropeWeights = levers.nonEuropeWeights
    val standardization = ContextTier.entries.associateWith { TierStandardization.fit(train, it) }
    val cells = ArrayList<CellResult>()
    val pool = Executors.newFixedThreadPool(threads)
    try {
      for (tier in ContextTier.entries) {
        val std = standardization.getValue(tier)
        for (w in RainWindows.ALL.indices) {
          val started = System.nanoTime()
          val columns = levers.columns(tier, w).filter { it !in std.degenerate }.toIntArray()
          val trainDesign = CellDesign.of(train, tier, w, columns, std, withHalves = false)
          val validationDesign = CellDesign.of(validation, tier, w, columns, std, withHalves = true)
          val validationA = (0 until validationDesign.n).filter { validationDesign.europe[it] && validationDesign.half!![it] == TierHalf.A }.toIntArray()
          val anchors = FeatureSubsets.anchorCandidates(tier, w).filter { it == null || it !in std.degenerate }
          val hasNonEurope = trainDesign.europe.any { !it }
          // Senza righe fuori Europa (NONE in TUNE) il peso non si sceglie: si registra zero, cioe' "solo Europa", che qui
          // e' identico e nel riaddestramento non fa entrare righe che la scelta non ha mai visto.
          val weightsGrid = if (hasNonEurope) nonEuropeWeights else listOf(0.0)

          val tasks = anchors.flatMap { anchor -> weightsGrid.flatMap { wne -> lambdas.map { LogisticChoice(anchor, it, wne) } } }
          val byLocation = validationA.groupBy { validationDesign.location[it].toInt() }.mapValues { it.value.toIntArray() }
          val baselineBrier = byLocation.mapValues { (_, rows) -> bestBaselineBrier(validationDesign, rows, tier, w) }
          val grid = runAll(pool, tasks.map { choice ->
            Callable {
              val fit = LogisticV3.fit(trainDesign, trainDesign.offsets(choice.anchor), trainDesign.weights(choice.nonEuropeWeight), choice.lambda)
              val offsets = validationDesign.offsets(choice.anchor)
              val loss = LogisticV3.logLoss(validationDesign, validationA, fit.beta, offsets)
              val margins = byLocation.map { (location, rows) ->
                var sum = 0.0
                for (i in rows) {
                  val p = LogisticV3.sigmoid(LogisticV3.score(validationDesign, i, fit.beta, offsets[i]))
                  sum += (p - validationDesign.y[i]) * (p - validationDesign.y[i])
                }
                location to sum / rows.size - baselineBrier.getValue(location)
              }
              val worst = margins.maxByOrNull { it.second }
              LogisticGridPoint(choice, loss, fit.converged, fit.iterations, worst?.second ?: Double.NaN, worst?.first?.let { train.locations[it] } ?: "-")
            }
          })
          val byLoss = compareBy<LogisticGridPoint>({ it.validationLogLoss }, { it.choice.lambda })
          val safe = grid.filter { it.worstMargin < 0 }
          val best = when {
            !levers.gateCriterion -> grid.minWithOrNull(byLoss)!!
            safe.isNotEmpty() -> safe.minWithOrNull(byLoss)!!
            else -> grid.minWithOrNull(compareBy<LogisticGridPoint>({ it.worstMargin }, { it.validationLogLoss }))!!
          }
          val (bags, nonConverged) = bag(pool, trainDesign, best.choice, std)

          val gbm = if (!withGbm) {
            emptyList()
          } else {
            val bins = GbmBins.of(trainDesign)
            val trainBins = bins.bin(trainDesign)
            val validationBins = bins.bin(validationDesign)
            val weights = trainDesign.weights(best.choice.nonEuropeWeight)
            runAll(pool, gbmGrid.map { config ->
              Callable {
                val result = GbmV3.train(trainDesign, trainBins, validationDesign, validationBins, validationA, weights, best.choice.anchor, config, bins.edgeCounts)
                gridPoint(result, tier, w, columns, bins)
              }
            })
          }
          val positives = trainDesign.y.count { it.toInt() == 1 }
          cells += CellResult(tier, w, columns, trainDesign.n, positives, best.choice, grid, bags, nonConverged, gbm)
          log(
            String.format(
              Locale.ROOT, "  %s %s: %d righe, logistica %s (VAL-A %.5f)%s — %.0f s",
              tier.name, RainWindows.ALL[w].label, trainDesign.n, best.choice.label, best.validationLogLoss,
              if (gbm.isEmpty()) "" else gbm.minByOrNull { it.validationLogLoss }!!.let { String.format(Locale.ROOT, ", alberi %s %d giri (VAL-A %.5f)", it.config.label, it.rounds, it.validationLogLoss) },
              (System.nanoTime() - started) / 1e9,
            ),
          )
        }
      }
    } finally {
      pool.shutdown()
    }
    return TierTables(standardization, cells)
  }

  /** Il riaddestramento finale: gli iperparametri di [frozen] (per cella), nessuna validazione. */
  fun refit(train: V3Rows, frozen: Map<String, FrozenCell>, withGbm: Boolean): TierTables {
    val standardization = ContextTier.entries.associateWith { TierStandardization.fit(train, it) }
    val cells = ArrayList<CellResult>()
    val pool = Executors.newFixedThreadPool(threads)
    try {
      for (tier in ContextTier.entries) {
        val std = standardization.getValue(tier)
        for (w in RainWindows.ALL.indices) {
          val started = System.nanoTime()
          val key = "${tier.name}.${RainWindows.ALL[w].label}"
          val cell = frozen[key] ?: error("nessun iperparametro congelato per $key")
          val columns = cell.columns.filter { it !in std.degenerate }.toIntArray()
          val design = CellDesign.of(train, tier, w, columns, std, withHalves = false)
          val choice = cell.logistic.let { if (it.anchor != null && it.anchor in std.degenerate) it.copy(anchor = null) else it }
          val (bags, nonConverged) = bag(pool, design, choice, std)
          val gbm = if (!withGbm || cell.gbm == null) {
            emptyList()
          } else {
            val bins = GbmBins.of(design)
            val binned = bins.bin(design)
            val result = GbmV3.trainFixed(design, binned, design.weights(choice.nonEuropeWeight), choice.anchor, cell.gbm, cell.gbmRounds, bins.edgeCounts)
            listOf(gridPoint(result, tier, w, columns, bins))
          }
          cells += CellResult(tier, w, columns, design.n, design.y.count { it.toInt() == 1 }, choice, emptyList(), bags, nonConverged, gbm)
          log(String.format(Locale.ROOT, "  %s %s: %d righe riaddestrate — %.0f s", tier.name, RainWindows.ALL[w].label, design.n, (System.nanoTime() - started) / 1e9))
        }
      }
    } finally {
      pool.shutdown()
    }
    return TierTables(standardization, cells)
  }

  /** La migliore baseline (Brier) sulle righe [rows] di una cella: climatologia, persistenza (con contesto), regola. */
  private fun bestBaselineBrier(design: CellDesign, rows: IntArray, tier: ContextTier, w: Int): Double {
    val columns = listOfNotNull(
      FeatureExtractorV3.CLIMATOLOGY + w,
      (FeatureExtractorV3.PERSISTENCE + w).takeIf { tier.hasContext },
      FeatureExtractorV3.BAROMETRIC_RULE + w,
    )
    return columns.minOf { column ->
      var sum = 0.0
      for (i in rows) {
        val p = LogisticV3.sigmoid(design.feature(i, column).toDouble())
        sum += (p - design.y[i]) * (p - design.y[i])
      }
      sum / rows.size
    }
  }

  /** Gli iperparametri congelati di una cella per il riaddestramento. */
  class FrozenCell(val logistic: LogisticChoice, val gbm: GbmConfig?, val gbmRounds: Int, val columns: IntArray)

  private fun bag(
    pool: java.util.concurrent.ExecutorService,
    design: CellDesign,
    choice: LogisticChoice,
    std: TierStandardization,
  ): Pair<List<DoubleArray>, Int> {
    val offsets = design.offsets(choice.anchor)
    val fits = runAll(pool, (0 until BAGS).map { b ->
      Callable {
        val multiplicity = LogisticV3.blockBootstrap(design, BAG_SEED + b)
        LogisticV3.fit(design, offsets, design.weights(choice.nonEuropeWeight, multiplicity), choice.lambda)
      }
    })
    return fits.map { LogisticV3.toFullWeights(it.beta, design.columns, choice.anchor, std) } to fits.count { !it.converged }
  }

  private fun gridPoint(result: GbmV3Result, tier: ContextTier, w: Int, columns: IntArray, bins: GbmBins): GbmGridPoint {
    val ensemble = GbmV3.export(result, tier.ordinal, w, columns, bins)
    val bytes = ensemble.encode()
    val gain = DoubleArray(FeatureExtractorV3.COUNT)
    for ((j, c) in columns.withIndex()) gain[c] = result.gainByColumn[j]
    return GbmGridPoint(result.config, result.bestRounds, result.roundsPlayed, result.bestValidationLogLoss, TreeEnsemble.parse(bytes, FeatureExtractorV3.COUNT), bytes, gain)
  }

  companion object {
    val LAMBDAS: List<Double> = listOf(1e-4, 3e-4, 1e-3, 3e-3, 1e-2, 3e-2)
    val NON_EUROPE_WEIGHTS: List<Double> = listOf(0.0, 0.5, 1.0)
    const val BAGS: Int = 5
    const val BAG_SEED: Long = 42L

    fun <T> runAll(pool: java.util.concurrent.ExecutorService, tasks: List<Callable<T>>): List<T> =
      tasks.map { pool.submit(it) }.map { future ->
        try {
          future.get()
        } catch (e: ExecutionException) {
          throw e.cause ?: e
        }
      }

    /**
     * La scelta degli alberi per cella sotto il limite di peso: per ogni cella la configurazione con la
     * log-loss di VALIDATION A piu' bassa; se il totale supera [maxBytes], si scende di configurazione nella
     * cella dove costa meno log-loss per byte risparmiato, finche' si sta sotto.
     */
    fun selectTrees(cells: List<CellResult>, maxBytes: Int): Map<String, GbmGridPoint> {
      val ranked = cells.associate { cell -> cell.key to cell.gbmGrid.sortedBy { it.validationLogLoss } }
      val position = ranked.mapValues { 0 }.toMutableMap()
      fun total() = ranked.entries.sumOf { (key, list) -> list[position.getValue(key)].bytes.size }
      while (total() > maxBytes) {
        var bestKey: String? = null
        var bestRatio = Double.POSITIVE_INFINITY
        for ((key, list) in ranked) {
          val here = list[position.getValue(key)]
          // Il prossimo candidato piu' leggero di questo, nell'ordine della log-loss.
          val next = list.drop(position.getValue(key) + 1).firstOrNull { it.bytes.size < here.bytes.size } ?: continue
          val ratio = (next.validationLogLoss - here.validationLogLoss) / (here.bytes.size - next.bytes.size)
          if (ratio < bestRatio) {
            bestRatio = ratio
            bestKey = key
          }
        }
        val key = bestKey ?: break
        val list = ranked.getValue(key)
        val here = list[position.getValue(key)]
        position[key] = list.indexOfFirst { it.bytes.size < here.bytes.size && list.indexOf(it) > position.getValue(key) }
      }
      return ranked.mapValues { (key, list) -> list[position.getValue(key)] }
    }
  }
}
