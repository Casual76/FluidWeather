package dev.pampa.fluidweather.nowcast.scoring

import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Una previsione probabilistica e cio' che e' successo: la materia prima di ogni punteggio. */
data class ForecastCase(val probability: Double, val occurred: Boolean)

/** Un gradino della curva di affidabilita': "quando dico X%, succede davvero Y% delle volte". */
data class ReliabilityBin(
  val lower: Double,
  val upper: Double,
  val meanForecast: Double,
  val observedFrequency: Double,
  val count: Int,
)

/**
 * I punteggi con cui si giudica una probabilita', uguali su telefono e banco.
 *
 * La classifica di prima ordinava per errore assoluto medio |p - esito|, che non e' un punteggio
 * *proprio*: premia chi dice 0 o 1 anche quando sa che la frequenza vera e' 0,3, e con eventi rari
 * un previsore tarato perde contro "non piove mai". Brier e log-loss sono propri — il punteggio
 * atteso e' migliore quando si dice la propria probabilita' vera, non un'altra — e quindi non si
 * possono vincere barando. La MAE resta, dichiarata e secondaria, perche' e' il numero che gli
 * utenti gia' conoscono.
 *
 * Tutto puro e deterministico: lo stesso elenco di casi da' lo stesso numero ovunque.
 */
object ProperScores {

  /** Il ritaglio della log-loss: una probabilita' 0 su un evento accaduto vale 13,8 e non infinito. */
  const val LOG_LOSS_CLIP: Double = 1e-6

  fun brier(probability: Double, occurred: Boolean): Double {
    val error = probability - outcome(occurred)
    return error * error
  }

  fun logLoss(probability: Double, occurred: Boolean): Double {
    val p = probability.coerceIn(LOG_LOSS_CLIP, 1 - LOG_LOSS_CLIP)
    return if (occurred) -ln(p) else -ln(1 - p)
  }

  fun absoluteError(probability: Double, occurred: Boolean): Double = abs(probability - outcome(occurred))

  /** Brier medio. NaN su lista vuota: nessun caso non e' un punteggio perfetto. */
  fun brier(cases: List<ForecastCase>): Double = mean(cases) { brier(it.probability, it.occurred) }

  fun logLoss(cases: List<ForecastCase>): Double = mean(cases) { logLoss(it.probability, it.occurred) }

  /** L'errore assoluto medio: la metrica della vecchia classifica, tenuta come secondaria. */
  fun mae(cases: List<ForecastCase>): Double = mean(cases) { absoluteError(it.probability, it.occurred) }

  /** La frequenza dell'evento nei casi. */
  fun baseRate(cases: List<ForecastCase>): Double = mean(cases) { outcome(it.occurred) }

  /**
   * Skill score contro un riferimento (climatologia, persistenza, "sempre 0%"): 1 - score/rif.
   * Positivo = meglio del riferimento, zero = uguale, negativo = peggio. Vale per qualunque
   * punteggio orientato al "piu' basso e' meglio" (Brier -> BSS). NaN se il riferimento e' zero.
   */
  fun bss(score: Double, referenceScore: Double): Double =
    if (referenceScore == 0.0 || referenceScore.isNaN() || score.isNaN()) Double.NaN
    else 1.0 - score / referenceScore

  /** La curva di affidabilita' su [bins] gradini uguali di [0, 1]; i gradini vuoti non compaiono. */
  fun reliability(cases: List<ForecastCase>, bins: Int = 10): List<ReliabilityBin> {
    require(bins > 0) { "servono gradini" }
    return cases
      .groupBy { (it.probability.coerceIn(0.0, 1.0) * bins).toInt().coerceAtMost(bins - 1) }
      .toSortedMap()
      .map { (bin, group) ->
        ReliabilityBin(
          lower = bin.toDouble() / bins,
          upper = (bin + 1).toDouble() / bins,
          meanForecast = group.sumOf { it.probability } / group.size,
          observedFrequency = group.count { it.occurred }.toDouble() / group.size,
          count = group.size,
        )
      }
  }

  private fun outcome(occurred: Boolean): Double = if (occurred) 1.0 else 0.0

  private inline fun mean(cases: List<ForecastCase>, value: (ForecastCase) -> Double): Double {
    if (cases.isEmpty()) return Double.NaN
    var sum = 0.0
    for (case in cases) sum += value(case)
    return sum / cases.size
  }
}

/**
 * La media di una serie di punteggi con la sua incertezza: intervallo percentile ed errore
 * standard dal bootstrap a blocchi di giorni.
 */
data class BootstrapSummary(
  val mean: Double,
  val low: Double,
  val high: Double,
  val standardError: Double,
  /** Quanti casi. */
  val count: Int,
  /** Su quanti giorni distinti: e' questo, non [count], il numero di cose indipendenti. */
  val days: Int,
)

/**
 * L'incertezza di un punteggio medio, rispettando il fatto che i casi di uno stesso giorno non
 * sono indipendenti.
 *
 * Venti verdetti di un giorno di pioggia sono, per il Brier, quasi un caso solo: lo stesso fronte
 * visto venti volte. Un bootstrap caso per caso li tratterebbe come venti prove e darebbe un
 * intervallo stretto e bugiardo — e "il barometro batte il provider" diventerebbe vero con una
 * settimana di dati. Qui si ricampionano i **giorni**: si pescano con reimmissione tanti giorni
 * quanti ce ne sono, si prendono tutti i loro casi, si ricalcola la media; [resamples] volte.
 * L'intervallo e' fra i percentili, l'errore standard e' la deviazione delle medie ricampionate.
 *
 * Per confrontare due righe si usa [paired]: le differenze caso per caso sugli stessi giri,
 * ricampionate per giorno. E' il confronto giusto — stessi casi, stesse giornate difficili — e ha
 * un intervallo molto piu' stretto di due intervalli messi accanto.
 *
 * Deterministico: il generatore e' `java.util.Random` col seme dato, e i giorni si visitano in
 * ordine. Lo stesso elenco da' lo stesso intervallo sul telefono, sul banco, domani.
 */
class DayBlockBootstrap(
  private val resamples: Int = DEFAULT_RESAMPLES,
  private val seed: Long = DEFAULT_SEED,
  /** L'ampiezza dell'intervallo: 0,95 = percentili 2,5 e 97,5. */
  private val confidence: Double = DEFAULT_CONFIDENCE,
) {

  init {
    require(resamples > 0) { "servono ricampionamenti" }
    require(confidence > 0.0 && confidence < 1.0) { "confidenza fuori da (0, 1): $confidence" }
  }

  /** [values] e [epochDays] allineati: il valore i-esimo e' stato osservato nel giorno i-esimo. */
  fun summarize(values: List<Double>, epochDays: List<Long>): BootstrapSummary {
    require(values.size == epochDays.size) { "valori e giorni non allineati: ${values.size} vs ${epochDays.size}" }
    if (values.isEmpty()) return BootstrapSummary(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0)

    // I blocchi: somma e numero di casi per giorno, in ordine di giorno.
    val byDay = java.util.TreeMap<Long, DoubleArray>()
    for (i in values.indices) {
      val block = byDay.getOrPut(epochDays[i]) { DoubleArray(2) }
      block[0] += values[i]
      block[1] += 1.0
    }
    val sums = byDay.values.map { it[0] }.toDoubleArray()
    val counts = byDay.values.map { it[1] }.toDoubleArray()
    val days = sums.size
    val mean = sums.sum() / counts.sum()

    val random = Random(seed)
    val statistics = DoubleArray(resamples) {
      var sum = 0.0
      var count = 0.0
      repeat(days) {
        val day = random.nextInt(days)
        sum += sums[day]
        count += counts[day]
      }
      sum / count
    }
    statistics.sort()

    val tail = (1.0 - confidence) / 2.0
    return BootstrapSummary(
      mean = mean,
      low = percentile(statistics, tail),
      high = percentile(statistics, 1.0 - tail),
      standardError = standardDeviation(statistics),
      count = values.size,
      days = days,
    )
  }

  /** Lo stesso, per casi etichettati col giorno. */
  fun summarize(valuesByDay: List<Pair<Long, Double>>): BootstrapSummary =
    summarize(valuesByDay.map { it.second }, valuesByDay.map { it.first })

  /**
   * La differenza appaiata [a] - [b], caso per caso (stessi giri, stesso ordine), ricampionata per
   * giorno. Negativa sul Brier = [a] meglio di [b]; l'intervallo che non contiene lo zero e' la
   * condizione per dirlo.
   */
  fun paired(a: List<Double>, b: List<Double>, epochDays: List<Long>): BootstrapSummary {
    require(a.size == b.size) { "liste non appaiate: ${a.size} vs ${b.size}" }
    return summarize(List(a.size) { a[it] - b[it] }, epochDays)
  }

  companion object {
    const val DEFAULT_RESAMPLES: Int = 1000
    const val DEFAULT_SEED: Long = 42L
    const val DEFAULT_CONFIDENCE: Double = 0.95

    /** Il giorno UTC di un istante: la chiave dei blocchi. */
    fun epochDayOf(timestampMillis: Long): Long = Math.floorDiv(timestampMillis, 86_400_000L)

    /** Percentile con interpolazione lineare fra ranghi (la definizione "tipo 7"). */
    internal fun percentile(sorted: DoubleArray, fraction: Double): Double {
      if (sorted.size == 1) return sorted[0]
      val position = fraction * (sorted.size - 1)
      val lower = position.toInt().coerceIn(0, sorted.size - 1)
      val upper = (lower + 1).coerceAtMost(sorted.size - 1)
      val weight = position - lower
      return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
    }

    private fun standardDeviation(values: DoubleArray): Double {
      if (values.size < 2) return 0.0
      val mean = values.average()
      var squares = 0.0
      for (value in values) squares += (value - mean) * (value - mean)
      return sqrt(squares / (values.size - 1))
    }
  }
}
