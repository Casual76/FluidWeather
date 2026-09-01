package dev.pampa.fluidweather.testbench.train

import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Medie e deviazioni per feature, stimate sul solo train: il contratto di standardizzazione. */
class Standardization(val means: DoubleArray, val sds: DoubleArray) {

  /** NaN -> 0 dopo la standardizzazione: il neutro esatto, mai un'invenzione. */
  fun apply(features: DoubleArray): DoubleArray = DoubleArray(features.size) { i ->
    if (features[i].isNaN()) 0.0 else (features[i] - means[i]) / sds[i]
  }

  companion object {
    fun fit(rows: List<DoubleArray>, featureCount: Int): Standardization {
      val means = DoubleArray(featureCount)
      val sds = DoubleArray(featureCount)
      for (i in 0 until featureCount) {
        val values = rows.mapNotNull { row -> row[i].takeIf { !it.isNaN() } }
        val mean = if (values.isEmpty()) 0.0 else values.average()
        val variance = if (values.size < 2) 1.0 else values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        means[i] = mean
        sds[i] = max(sqrt(variance), 1e-6)
      }
      return Standardization(means, sds)
    }
  }
}

/**
 * Regressione logistica con L2, discesa del gradiente full-batch: nessuna dipendenza, nessuna
 * sorpresa, riproducibile al bit. Il bagging (5 ricampionamenti con seme fisso) produce il
 * comitato del modello spedito: media = verdetto, disaccordo = banda.
 */
class LogisticTrainer(
  private val l2: Double = 1e-3,
  private val learningRate: Double = 0.3,
  private val iterations: Int = 400,
  private val bags: Int = 5,
  private val seed: Long = 42L,
) {

  /** Restituisce [bags] vettori [intercetta, pesi...] su feature gia' standardizzate. */
  fun fitBagged(features: List<DoubleArray>, labels: List<Boolean>): List<DoubleArray> {
    val random = Random(seed)
    return (0 until bags).map {
      val indices = IntArray(features.size) { random.nextInt(features.size) }
      fit(indices.map { features[it] }, indices.map { labels[it] })
    }
  }

  fun fit(features: List<DoubleArray>, labels: List<Boolean>): DoubleArray {
    val n = features.size
    val d = features.first().size
    val weights = DoubleArray(d + 1)
    val targets = DoubleArray(n) { if (labels[it]) 1.0 else 0.0 }

    for (iteration in 0 until iterations) {
      val gradient = DoubleArray(d + 1)
      for (row in 0 until n) {
        val x = features[row]
        var score = weights[0]
        for (i in 0 until d) score += weights[i + 1] * x[i]
        val error = sigmoid(score) - targets[row]
        gradient[0] += error
        for (i in 0 until d) gradient[i + 1] += error * x[i]
      }
      // L2 sui pesi, mai sull'intercetta: regolarizzare il tasso base sarebbe una bugia.
      for (i in 1..d) gradient[i] += l2 * n * weights[i]
      val step = learningRate / n
      for (i in 0..d) weights[i] -= step * gradient[i]
    }
    return weights
  }

  private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
