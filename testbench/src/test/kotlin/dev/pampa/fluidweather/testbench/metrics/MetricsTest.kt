package dev.pampa.fluidweather.testbench.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTest {

  @Test
  fun `la tabella di contingenza conta a mano`() {
    val verifications = listOf(
      Verification(0.8, true), // hit
      Verification(0.3, true), // miss
      Verification(0.7, false), // falso allarme
      Verification(0.1, false), // negativo corretto
      Verification(0.6, true), // hit
    )
    val c = Contingency.at(0.5, verifications)

    assertEquals(2, c.hits)
    assertEquals(1, c.misses)
    assertEquals(1, c.falseAlarms)
    assertEquals(1, c.correctNegatives)
    assertEquals(2.0 / 3.0, c.pod, 1e-12)
    assertEquals(1.0 / 3.0, c.far, 1e-12)
    assertEquals(0.5, c.csi, 1e-12)
    assertEquals(1.0, c.frequencyBias, 1e-12)
  }

  @Test
  fun `senza eventi le metriche categoriche sono NaN, non bugie`() {
    val c = Contingency.at(0.5, listOf(Verification(0.1, false)))
    assertTrue(c.pod.isNaN())
    assertTrue(c.frequencyBias.isNaN())
  }

  @Test
  fun `il Brier a mano`() {
    val verifications = listOf(
      Verification(1.0, true), // 0
      Verification(0.0, true), // 1
      Verification(0.5, false), // 0,25
    )
    assertEquals(1.25 / 3.0, Probabilistic.brier(verifications), 1e-12)
  }

  @Test
  fun `il perfetto vale BSS uno, la climatologia zero`() {
    val outcomes = listOf(true, false, false, true, false)
    val perfect = outcomes.map { Verification(if (it) 1.0 else 0.0, it) }
    assertEquals(1.0, Probabilistic.brierSkillScore(perfect), 1e-12)

    val baseRate = outcomes.count { it }.toDouble() / outcomes.size
    val climatology = outcomes.map { Verification(baseRate, it) }
    assertEquals(0.0, Probabilistic.brierSkillScore(climatology), 1e-12)
  }

  @Test
  fun `la spavalderia sbagliata ha BSS negativo`() {
    val overconfident = listOf(
      Verification(0.95, false),
      Verification(0.95, false),
      Verification(0.95, true),
    )
    assertTrue(Probabilistic.brierSkillScore(overconfident) < 0.0)
  }

  @Test
  fun `la curva di affidabilita' raggruppa e conta`() {
    val verifications = listOf(
      Verification(0.62, true),
      Verification(0.65, false),
      Verification(0.68, true),
      Verification(0.05, false),
    )
    val bins = Probabilistic.reliability(verifications, bins = 10)

    assertEquals(2, bins.size)
    val high = bins.last()
    assertEquals(3, high.count)
    assertEquals(0.65, high.meanForecast, 1e-9)
    assertEquals(2.0 / 3.0, high.observedFrequency, 1e-9)
  }
}
