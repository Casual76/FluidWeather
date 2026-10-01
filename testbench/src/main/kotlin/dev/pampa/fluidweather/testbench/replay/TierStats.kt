package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary
import dev.pampa.fluidweather.nowcast.scoring.DayBlockBootstrap
import dev.pampa.fluidweather.nowcast.scoring.ForecastCase
import dev.pampa.fluidweather.nowcast.scoring.ProperScores
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.tiers.TierKind

/** Le localita' e i gruppi del banco onesto: l'Europa e' dove vive l'app (gate duro), le altre quattro sono solo avviso. */
object TierGroups {

  /** Dove vive l'app: sul tasso di queste sei e sull'insieme di tutte il gate fallisce. */
  val EUROPA: List<String> =
    listOf("sesto-fiorentino", "milano", "genova", "innsbruck", "bergen", "reykjavik")

  /** Fuori dall'Europa il barometro puo' non aggiungere niente: si riporta, non si boccia. */
  val WARN_ONLY: List<String> = listOf("singapore", "tokyo", "denver", "buenos-aires")

  /** Casa. */
  const val SESTO: String = "sesto-fiorentino"

  const val EUROPA_LABEL = "EUROPA (6)"
}

/**
 * Le statistiche di un predittore su un insieme di casi: quanti, il tasso base, e i punteggi.
 * [bss] e' contro la climatologia dello stesso livello e finestra, sugli stessi casi.
 */
data class RowStats(
  val predictor: String,
  val n: Int,
  val base: Double,
  val brier: Double,
  val bss: Double,
  val logLoss: Double,
  val mae: Double,
)

/**
 * Il confronto appaiato modello - migliore baseline su un insieme di casi: il Brier di ciascuno,
 * la differenza media con l'intervallo al 95% del bootstrap a blocchi di giorni. Negativo = il modello vince.
 */
data class PairedDelta(
  val modelBrier: Double,
  val baseline: String,
  val baselineBrier: Double,
  val delta: BootstrapSummary,
)

/**
 * Le aggregazioni del replay per livello: punteggi per predittore, migliore baseline, differenze
 * appaiate. Tutto si calcola da [TierReplayResult.records], mai altrove: una tabella e' una vista.
 */
class TierStats(val result: TierReplayResult) {

  private val index: Map<Triple<String, TierKind, Int>, List<CaseRecord>> =
    result.records.groupBy { Triple(it.location, it.kind, it.windowIndex) }

  /**
   * I casi di un insieme di localita', un livello e una finestra che la verita' [truth] sa giudicare,
   * nell'ordine del replay (localita' nell'ordine dato, poi per emissione).
   */
  fun cases(locations: Collection<String>, kind: TierKind, windowIndex: Int, truth: TruthKind): List<CaseRecord> =
    locations.flatMap { index[Triple(it, kind, windowIndex)].orEmpty() }.filter { it.outcome(truth) >= 0 }

  /** Le righe di punteggio di tutti i predittori che si applicano ai casi, nell'ordine dei predittori. */
  fun rows(cases: List<CaseRecord>, truth: TruthKind): List<RowStats> =
    result.predictorNames.indices.mapNotNull { k -> row(cases, k, truth) }

  /** La riga di un predittore; null se non si applica a nessun caso. */
  fun row(cases: List<CaseRecord>, predictorIndex: Int, truth: TruthKind): RowStats? {
    val climatology = result.indexOf(PredictorNames.CLIMATOLOGIA)
    var n = 0
    var wet = 0
    var brier = 0.0
    var logLoss = 0.0
    var mae = 0.0
    var referenceBrier = 0.0
    for (case in cases) {
      val p = case.probabilities[predictorIndex]
      if (p.isNaN()) continue
      val occurred = case.outcome(truth) == 1
      n++
      if (occurred) wet++
      brier += ProperScores.brier(p, occurred)
      logLoss += ProperScores.logLoss(p, occurred)
      mae += ProperScores.absoluteError(p, occurred)
      referenceBrier += ProperScores.brier(case.probabilities[climatology], occurred)
    }
    if (n == 0) return null
    val meanBrier = brier / n
    return RowStats(
      predictor = result.predictorNames[predictorIndex],
      n = n,
      base = wet.toDouble() / n,
      brier = meanBrier,
      bss = ProperScores.bss(meanBrier, referenceBrier / n),
      logLoss = logLoss / n,
      mae = mae / n,
    )
  }

  /** La migliore baseline (D1: climatologia, persistenza, regola barometrica) per Brier; null se non ce n'e'. */
  fun bestBaseline(rows: List<RowStats>): RowStats? =
    rows.filter { it.predictor in PredictorNames.BASELINES }.minByOrNull { it.brier }

  /** La riga di un predittore per nome. */
  fun byName(rows: List<RowStats>, predictor: String): RowStats? = rows.firstOrNull { it.predictor == predictor }

  /**
   * Il modello contro la migliore baseline di questo insieme di casi, appaiato: per ogni caso la
   * differenza dei due Brier, ricampionata per giorno. Null se manca il modello o non c'e' una baseline.
   */
  fun pairedAgainstBest(
    cases: List<CaseRecord>,
    truth: TruthKind,
    bootstrap: DayBlockBootstrap = DayBlockBootstrap(),
  ): PairedDelta? {
    val modelIndex = result.indexOf(PredictorNames.MODELLO)
    if (modelIndex < 0) return null
    val rows = rows(cases, truth)
    val best = bestBaseline(rows) ?: return null
    return paired(cases, truth, modelIndex, result.indexOf(best.predictor), bootstrap)?.let { delta ->
      PairedDelta(
        modelBrier = byName(rows, PredictorNames.MODELLO)?.brier ?: Double.NaN,
        baseline = best.predictor,
        baselineBrier = best.brier,
        delta = delta,
      )
    }
  }

  /** La differenza appaiata Brier([a]) - Brier([b]) sui casi dove entrambi rispondono, con il bootstrap a blocchi di giorni. */
  fun paired(
    cases: List<CaseRecord>,
    truth: TruthKind,
    a: Int,
    b: Int,
    bootstrap: DayBlockBootstrap = DayBlockBootstrap(),
  ): BootstrapSummary? {
    val brierA = ArrayList<Double>()
    val brierB = ArrayList<Double>()
    val days = ArrayList<Long>()
    for (case in cases) {
      val pa = case.probabilities[a]
      val pb = case.probabilities[b]
      if (pa.isNaN() || pb.isNaN()) continue
      val occurred = case.outcome(truth) == 1
      brierA += ProperScores.brier(pa, occurred)
      brierB += ProperScores.brier(pb, occurred)
      days += DayBlockBootstrap.epochDayOf(case.issueMillis)
    }
    if (brierA.isEmpty()) return null
    return bootstrap.paired(brierA, brierB, days)
  }

  /** I casi di un predittore nella forma dei punteggi propri (per la curva di affidabilita'). */
  fun forecastCases(cases: List<CaseRecord>, predictorIndex: Int, truth: TruthKind): List<ForecastCase> =
    cases.mapNotNull { case ->
      val p = case.probabilities[predictorIndex]
      if (p.isNaN()) null else ForecastCase(p, case.outcome(truth) == 1)
    }
}
