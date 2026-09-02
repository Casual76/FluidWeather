package dev.pampa.fluidweather.nowcast.learning

import kotlin.math.sqrt

/** Una situazione barica del passato: le feature com'erano, e com'e' finita finestra per finestra. */
data class AnalogCase(
  val issuedAtMillis: Long,
  val features: DoubleArray,
  /** finestra ("0-1h", "1-3h", "3-6h") -> ha piovuto. Assente = esito non ancora noto. */
  val outcomes: Map<String, Boolean>,
)

/** Cosa dicono gli analoghi per una finestra: quanti, quanti finiti in pioggia. */
data class AnalogSummary(
  val window: String,
  val neighbours: Int,
  val rained: Int,
) {
  val frequency: Double get() = if (neighbours == 0) 0.0 else rained.toDouble() / neighbours
}

/**
 * Il modulo degli analoghi storici del piano (fase 16): cerca nell'archivio locale le
 * situazioni bariche piu' simili a quella di adesso e guarda com'erano finite. Da' il meglio
 * dopo mesi, ed e' cio' che rende l'app sempre piu' "tua".
 *
 * La somiglianza e' una distanza euclidea sulle feature BAROMETRICHE standardizzate (tendenze a
 * 1/3/6/12 ore, accelerazione, incertezza) piu' l'ora solare: il contesto dei provider resta
 * fuori, perche' gli analoghi devono raccontare cio' che il barometro da solo ha visto. Due
 * passate a un quarto d'ora di distanza sono la stessa situazione, non due: si tiene il caso
 * piu' vicino per ogni finestra di sei ore.
 */
object Analogs {

  /** Indici in FeatureExtractor.names: tendenza-1h, -3h, -6h, -12h, accelerazione-3h, incertezza, ora-sin, ora-cos. */
  val featureIndices: IntArray = intArrayOf(0, 1, 2, 3, 4, 6, 14, 15)

  const val DEFAULT_NEIGHBOURS: Int = 25

  /** Quanto pesano gli analoghi contro il modello: con N vicini, N/(N+40); a 25 vicini e' ~0,38. */
  const val PRIOR_STRENGTH: Double = 40.0

  const val EPISODE_MILLIS: Long = 6 * 3_600_000L

  fun standardize(features: DoubleArray, means: DoubleArray, sds: DoubleArray): DoubleArray =
    DoubleArray(featureIndices.size) { k ->
      val i = featureIndices[k]
      val value = features.getOrNull(i) ?: Double.NaN
      if (value.isNaN() || sds[i] <= 0.0) 0.0 else (value - means[i]) / sds[i]
    }

  fun distance(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    for (i in a.indices) {
      val d = a[i] - b[i]
      sum += d * d
    }
    return sqrt(sum)
  }

  /** I [k] casi piu' vicini alla situazione di adesso, uno per episodio, coi loro esiti. */
  fun nearest(
    query: DoubleArray,
    cases: List<AnalogCase>,
    means: DoubleArray,
    sds: DoubleArray,
    k: Int = DEFAULT_NEIGHBOURS,
  ): List<Pair<AnalogCase, Double>> {
    val standardizedQuery = standardize(query, means, sds)
    val ranked = cases
      .filter { it.outcomes.isNotEmpty() }
      .map { it to distance(standardizedQuery, standardize(it.features, means, sds)) }
      .sortedBy { it.second }
    val chosen = mutableListOf<Pair<AnalogCase, Double>>()
    for (candidate in ranked) {
      if (chosen.size >= k) break
      val sameEpisode = chosen.any { kotlin.math.abs(it.first.issuedAtMillis - candidate.first.issuedAtMillis) < EPISODE_MILLIS }
      if (!sameEpisode) chosen += candidate
    }
    return chosen
  }

  fun summarize(neighbours: List<Pair<AnalogCase, Double>>, window: String): AnalogSummary {
    val known = neighbours.mapNotNull { it.first.outcomes[window] }
    return AnalogSummary(window, neighbours = known.size, rained = known.count { it })
  }

  /** La probabilita' finale: il modello (ricalibrato) e la frequenza degli analoghi, pesati. */
  fun blend(modelProbability: Double, summary: AnalogSummary?, priorStrength: Double = PRIOR_STRENGTH): Double {
    if (summary == null || summary.neighbours == 0) return modelProbability
    val weight = summary.neighbours / (summary.neighbours + priorStrength)
    return (1 - weight) * modelProbability + weight * summary.frequency
  }
}
