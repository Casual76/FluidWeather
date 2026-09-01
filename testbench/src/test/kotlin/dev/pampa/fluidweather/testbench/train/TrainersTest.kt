package dev.pampa.fluidweather.testbench.train

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainersTest {

  @Test
  fun `la standardizzazione ignora i NaN e li rende neutri`() {
    val rows = listOf(
      doubleArrayOf(10.0, Double.NaN),
      doubleArrayOf(20.0, 5.0),
      doubleArrayOf(30.0, 7.0),
    )
    val standardization = Standardization.fit(rows, 2)

    assertEquals(20.0, standardization.means[0], 1e-9)
    assertEquals(6.0, standardization.means[1], 1e-9)
    // Un NaN standardizzato vale zero: il neutro esatto.
    assertEquals(0.0, standardization.apply(rows[0])[1], 1e-9)
    assertEquals(-1.0, standardization.apply(rows[0])[0], 1e-9)
  }

  @Test
  fun `la logistica impara segno e forza di una feature informativa`() {
    val random = Random(7)
    val features = mutableListOf<DoubleArray>()
    val labels = mutableListOf<Boolean>()
    repeat(4_000) {
      val informative = random.nextGaussian()
      val noise = random.nextGaussian()
      features += doubleArrayOf(informative, noise)
      // La verita': dipende solo dalla prima feature, con segno negativo.
      labels += random.nextDouble() < 1.0 / (1.0 + kotlin.math.exp(2.0 * informative))
    }

    val weights = LogisticTrainer().fit(features, labels)
    assertTrue("peso informativo ${weights[1]}", weights[1] < -1.0)
    assertTrue("peso rumore ${weights[2]}", abs(weights[2]) < 0.2)
  }

  @Test
  fun `i bag differiscono ma raccontano la stessa storia`() {
    val random = Random(11)
    val features = (0 until 2_000).map { doubleArrayOf(random.nextGaussian()) }
    val labels = features.map { it[0] > 0 != (random.nextDouble() < 0.1) }

    val bags = LogisticTrainer().fitBagged(features, labels)
    assertEquals(5, bags.size)
    assertTrue(bags.all { it[1] > 0.5 })
    assertTrue(bags.map { it[1] }.toSet().size > 1)
  }

  @Test
  fun `il boosting impara cio' che una retta non puo' - lo XOR`() {
    val random = Random(3)
    val features = (0 until 4_000).map { doubleArrayOf(random.nextGaussian(), random.nextGaussian()) }
    val labels = features.map { (it[0] > 0) != (it[1] > 0) } // XOR: linearmente impossibile

    val gbm = Gbm(rounds = 60)
    gbm.fit(features, labels)
    val accuracy = features.indices.count { i ->
      (gbm.predict(features[i]) >= 0.5) == labels[i]
    }.toDouble() / features.size

    val logistic = LogisticTrainer().fit(features, labels)
    val logisticAccuracy = features.indices.count { i ->
      val score = logistic[0] + logistic[1] * features[i][0] + logistic[2] * features[i][1]
      (score >= 0.0) == labels[i]
    }.toDouble() / features.size

    assertTrue("gbm $accuracy", accuracy > 0.9)
    assertTrue("logistica $logisticAccuracy", logisticAccuracy < 0.6)
  }
}
