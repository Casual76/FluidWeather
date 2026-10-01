package dev.pampa.fluidweather.testbench.metrics

import dev.pampa.fluidweather.nowcast.scoring.ForecastCase
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
  @Test
  fun `il log-loss e la MAE a mano, con il ritaglio sui bordi`() {
    val verifications = listOf(
      Verification(0.8, true), // -ln 0,8
      Verification(0.3, false), // -ln 0,7
      Verification(0.0, true), // ritagliato a 1e-6: 13,8
    )
    val expectedLogLoss = (-Math.log(0.8) - Math.log(0.7) - Math.log(1e-6)) / 3.0
    assertEquals(expectedLogLoss, Probabilistic.logLoss(verifications), 1e-12)
    assertEquals((0.2 + 0.3 + 1.0) / 3.0, Probabilistic.mae(verifications), 1e-12)
    assertTrue(Probabilistic.logLoss(emptyList()).isNaN())
  }

  @Test
  fun `il BSS contro un riferimento dato non e' quello contro il tasso base`() {
    val outcomes = listOf(true, false, false, false, false, false, false, false)
    val model = outcomes.map { Verification(if (it) 0.5 else 0.05, it) }
    val reference = outcomes.map { Verification(0.3, it) } // un riferimento sbagliato di proposito
    val referenceBrier = Probabilistic.brier(reference)

    val against = Probabilistic.brierSkillScoreAgainst(model, referenceBrier)
    assertEquals(1.0 - Probabilistic.brier(model) / referenceBrier, against, 1e-12)
    // Contro la climatologia dei casi stessi e' un'altra cosa.
    assertTrue(Math.abs(against - Probabilistic.brierSkillScore(model)) > 0.01)
    // Un riferimento con Brier zero non da' un punteggio.
    assertTrue(Probabilistic.brierSkillScoreAgainst(model, 0.0).isNaN())
  }

  @Test
  fun `la curva di affidabilita' si stampa, un gradino per riga`() {
    val cases = listOf(
      ForecastCase(0.62, true),
      ForecastCase(0.65, false),
      ForecastCase(0.68, true),
      ForecastCase(0.05, false),
    )
    val lines = ReliabilityPrinter.lines(cases)
    assertEquals(2, lines.size)
    assertTrue(lines.last().contains("0.60-0.70"))
    assertTrue(lines.last().trim().split(Regex("""\s+""")).contains("3"))
    val table = ReliabilityPrinter.format(cases, indent = "  ")
    assertEquals(3, table.lines().size)
    assertTrue(table.lines().all { it.startsWith("  ") })
    assertEquals(1, ReliabilityPrinter.format(emptyList()).lines().size) // solo l'intestazione
  }
}
