package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary
import dev.pampa.fluidweather.nowcast.scoring.DayBlockBootstrap
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TruthKind
import dev.pampa.fluidweather.testbench.tiers.TierHalf
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import java.util.Locale
import java.util.stream.IntStream

/**
 * I modelli del v3 giudicati sulle righe di VALIDATION (modalita' valutazione = la semantica del gate),
 * attraverso gli artefatti **esportati** (le tabelle come [dev.pampa.fluidweather.nowcast.verdict.NowcastModel],
 * gli alberi riletti dai loro byte), accanto alle tre baseline, che nelle righe sono le colonne 30-38.
 *
 * Le righe sono orarie; quelle con `t0` sul passo di tre ore del periodo sono **gli stessi casi del gate**
 * (stesso minuto di emissione, stesso contesto, stesse tabelle): [preGate] e' un anticipo del gate di
 * sviluppo senza rigiocare il periodo, esatto a meno dell'arrotondamento a Float delle feature.
 */
class V3Evaluation(
  private val rows: V3Rows,
  models: Map<String, TieredNowcastModel>,
) {

  /** I predittori valutati, nell'ordine delle colonne: clima, persistenza, regola, poi i modelli. */
  val names: List<String> = listOf(CLIMA, PERSISTENZA, REGOLA) + models.keys

  /** probabilities[riga][predittore * 3 + finestra], NaN dove non si applica. */
  private val probabilities: Array<DoubleArray>

  init {
    val modelList = models.values.toList()
    probabilities = arrayOfNulls<DoubleArray>(rows.size).let { out ->
      IntStream.range(0, rows.size).parallel().forEach { row ->
        val tier = rows.tierOf(row)
        val x = rows.featureRow(row)
        val p = DoubleArray(names.size * 3) { Double.NaN }
        for (w in 0 until 3) {
          p[w] = sigmoid(x[FeatureExtractorV3.CLIMATOLOGY + w])
          if (tier.hasContext) p[3 + w] = sigmoid(x[FeatureExtractorV3.PERSISTENCE + w])
          p[6 + w] = sigmoid(x[FeatureExtractorV3.BAROMETRIC_RULE + w])
        }
        for ((m, model) in modelList.withIndex()) {
          val verdict = model.forTier(tier).verdict(x)
          for (w in 0 until 3) p[(3 + m) * 3 + w] = verdict.windows[w].probability
        }
        out[row] = p
      }
      Array(rows.size) { out[it]!! }
    }
  }

  fun probability(row: Int, predictor: Int, window: Int): Double = probabilities[row][predictor * 3 + window]

  /** Le righe di un livello e finestra giudicabili sotto [truth] che passano [filter]. */
  fun cases(tier: ContextTier, window: Int, truth: TruthKind = TruthKind.PANEL, filter: (Int) -> Boolean): IntArray =
    (0 until rows.size).filter { rows.tier[it].toInt() == tier.ordinal && rows.label(it, window, truth) >= 0 && filter(it) }.toIntArray()

  fun brier(cases: IntArray, predictor: Int, window: Int, truth: TruthKind = TruthKind.PANEL): Double {
    if (cases.isEmpty()) return Double.NaN
    var sum = 0.0
    var n = 0
    for (row in cases) {
      val p = probability(row, predictor, window)
      if (p.isNaN()) return Double.NaN
      val y = rows.label(row, window, truth)
      sum += (p - y) * (p - y)
      n++
    }
    return sum / n
  }

  /** La migliore baseline (indice e Brier) su [cases]. */
  fun bestBaseline(cases: IntArray, window: Int, truth: TruthKind = TruthKind.PANEL): Pair<Int, Double> =
    (0 until 3).map { it to brier(cases, it, window, truth) }.filter { !it.second.isNaN() }.minBy { it.second }

  /** Brier(a) - Brier(b) appaiato con il bootstrap a blocchi di giorni. */
  fun paired(cases: IntArray, a: Int, b: Int, window: Int, truth: TruthKind = TruthKind.PANEL): BootstrapSummary {
    val da = ArrayList<Double>(cases.size)
    val db = ArrayList<Double>(cases.size)
    val days = ArrayList<Long>(cases.size)
    for (row in cases) {
      val y = rows.label(row, window, truth)
      val pa = probability(row, a, window)
      val pb = probability(row, b, window)
      da += (pa - y) * (pa - y)
      db += (pb - y) * (pb - y)
      days += DayBlockBootstrap.epochDayOf(rows.issue[row])
    }
    return DayBlockBootstrap().paired(da, db, days)
  }

  fun label(row: Int, window: Int, truth: TruthKind = TruthKind.PANEL): Int = rows.label(row, window, truth)

  fun isEurope(row: Int): Boolean = rows.locationOf(row) in TierGroups.EUROPA

  fun isHalf(row: Int, half: TierHalf): Boolean = TierPeriods.half(rows.t0[row]) == half

  fun onGateStep(row: Int): Boolean = (rows.t0[row] - TierPeriods.VALIDATION.firstMillis) % (3 * 3_600_000L) == 0L

  fun location(row: Int): String = rows.locationOf(row)

  val locations: List<String> get() = rows.locations

  /**
   * L'anticipo del gate per il predittore [model]: per gruppo (EUROPA, le sei localita', TUTTE) x livello x
   * finestra, sul passo di tre ore di tutto VALIDATION, il margine sulla migliore baseline. Ritorna il testo
   * e il numero di celle dure che non passano.
   */
  fun preGate(model: Int): Pair<String, Int> {
    val groups = listOf("EUROPA" to TierGroups.EUROPA.toSet()) + TierGroups.EUROPA.map { it to setOf(it) } +
      listOf("TUTTE" to locations.toSet())
    var failures = 0
    val failed = ArrayList<String>()
    val text = buildString {
      append(String.format(Locale.ROOT, "  %-13s %-5s", "livello", "fin."))
      for ((label, _) in groups) append(String.format(Locale.ROOT, " %9s", label.take(9)))
      appendLine()
      for (tier in ContextTier.entries) {
        for (w in 0 until 3) {
          append(String.format(Locale.ROOT, "  %-13s %-5s", tier.name, RainWindows.ALL[w].label))
          for ((label, members) in groups) {
            val cases = cases(tier, w) { onGateStep(it) && location(it) in members }
            if (cases.isEmpty()) {
              append(String.format(Locale.ROOT, " %9s", "-"))
              continue
            }
            val (_, bestBrier) = bestBaseline(cases, w)
            val margin = brier(cases, model, w) - bestBrier
            val hard = label != "TUTTE"
            val fails = !(margin < 0)
            if (hard && fails) {
              failures++
              failed += "$label ${tier.name} ${RainWindows.ALL[w].label} ${Fmt.sgn4(margin)}"
            }
            append(String.format(Locale.ROOT, " %8s%s", Fmt.sgn4(margin), if (fails) (if (hard) "X" else "~") else " "))
          }
          appendLine()
        }
      }
      appendLine("  (margine = Brier(modello) - Brier(migliore baseline) sugli stessi casi; X = cella dura che non passa, ~ = avviso)")
      if (failed.isNotEmpty()) appendLine("  Celle dure che non passano: " + failed.joinToString("; "))
    }
    return text to failures
  }

  companion object {
    const val CLIMA = "climatologia"
    const val PERSISTENZA = "persistenza"
    const val REGOLA = "regola-barometrica"

    fun sigmoid(x: Double): Double = if (x.isNaN()) Double.NaN else 1.0 / (1.0 + Math.exp(-x))
  }
}
