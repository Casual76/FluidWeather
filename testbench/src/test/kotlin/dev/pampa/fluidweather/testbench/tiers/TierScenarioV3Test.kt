package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.testbench.data.BenchLocations
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le estrazioni di scenario del v3: stato dei dati, tabelle locali, bias della quota, passo delle emissioni, meta' di VALIDATION. */
class TierScenarioV3Test {

  private val hour = 3_600_000L
  private val hourly = TierPeriods.TEST.issueAnchors(hour)
  private val location = "sesto-fiorentino"

  @Test
  fun `il passo delle emissioni e' un parametro, tre ore di default`() {
    val period = TierPeriods.VALIDATION
    assertEquals(period.issueAnchors(3 * hour).toList(), period.issueAnchors().toList())
    val hourlyAnchors = period.issueAnchors(hour)
    assertEquals(365 * 24, hourlyAnchors.size)
    assertEquals(period.firstMillis, hourlyAnchors.first())
    assertEquals(period.endExclusiveMillis - hour, hourlyAnchors.last())
    assertEquals(365 * 8, period.issueAnchors().size)
    // Ogni terza emissione oraria e' un'emissione a passo di tre ore.
    assertEquals(period.issueAnchors().toList(), hourlyAnchors.filterIndexed { i, _ -> i % 3 == 0 })
    assertTrue(runCatching { period.issueAnchors(0) }.isFailure)
  }

  @Test
  fun `le due meta' di VALIDATION sono le settimane ISO pari e dispari, a meta' a meta'`() {
    // 2024-09-02 e' un lunedi' della settimana ISO 36 (pari); la settimana dopo e' la 37.
    val monday36 = Instant.parse("2024-09-02T00:00:00Z").toEpochMilli()
    val monday37 = Instant.parse("2024-09-09T00:00:00Z").toEpochMilli()
    assertEquals(TierHalf.A, TierPeriods.half(monday36))
    assertEquals(TierHalf.B, TierPeriods.half(monday37))
    // Tutta la settimana dalla stessa parte, fino alla domenica sera.
    for (h in 0 until 7 * 24) {
      assertEquals(TierHalf.A, TierPeriods.half(monday36 + h * hour))
      assertEquals(TierHalf.B, TierPeriods.half(monday37 + h * hour))
    }
    val anchors = TierPeriods.VALIDATION.issueAnchors(hour)
    val a = anchors.count { TierPeriods.half(it) == TierHalf.A }
    val b = anchors.size - a
    assertTrue("A $a, B $b", abs(a - b) < 0.1 * anchors.size)
  }

  @Test
  fun `NONE_NOCLIMA non ha mai la storia ne' le tabelle, in nessuna modalita'`() {
    for (t0 in hourly.filterIndexed { i, _ -> i % 7 == 0 }) {
      for (mode in ScenarioMode.entries) {
        assertFalse(TierScenarios.historyKnown(TierKind.NONE_NOCLIMA, location, t0, mode))
        assertFalse(TierScenarios.localPriorsKept(TierKind.NONE_NOCLIMA, location, t0, mode))
      }
    }
  }

  @Test
  fun `NONE ha sempre le tabelle locali`() {
    for (t0 in hourly.filterIndexed { i, _ -> i % 7 == 0 }) {
      for (mode in ScenarioMode.entries) {
        assertTrue(TierScenarios.localPriorsKept(TierKind.NONE, location, t0, mode))
      }
    }
  }

  @Test
  fun `in valutazione il telefono ha tutto quello che il suo livello puo' avere`() {
    for (t0 in hourly.filterIndexed { i, _ -> i % 5 == 0 }) {
      for (kind in listOf(TierKind.FRESH, TierKind.STALE, TierKind.NONE)) {
        assertTrue(TierScenarios.historyKnown(kind, location, t0, ScenarioMode.EVALUATION))
        assertTrue(TierScenarios.localPriorsKept(kind, location, t0, ScenarioMode.EVALUATION))
      }
    }
  }

  @Test
  fun `in addestramento la storia c'e' otto volte su dieci e le tabelle locali nove su dieci`() {
    val n = hourly.size
    for (kind in listOf(TierKind.FRESH, TierKind.STALE, TierKind.NONE)) {
      val history = hourly.count { TierScenarios.historyKnown(kind, location, it, ScenarioMode.TRAINING) }.toDouble() / n
      assertEquals("storia $kind", 0.8, history, 0.02)
    }
    for (kind in listOf(TierKind.FRESH, TierKind.STALE)) {
      val local = hourly.count { TierScenarios.localPriorsKept(kind, location, it, ScenarioMode.TRAINING) }.toDouble() / n
      assertEquals("tabelle $kind", 0.9, local, 0.02)
    }
  }

  @Test
  fun `lo stato dei dati e' una proprieta' del telefono, non del livello, e vale per tutti`() {
    for (t0 in hourly.filterIndexed { i, _ -> i % 3 == 0 }) {
      val fresh = TierScenarios.historyKnown(TierKind.FRESH, location, t0, ScenarioMode.TRAINING)
      assertEquals(fresh, TierScenarios.historyKnown(TierKind.STALE, location, t0, ScenarioMode.TRAINING))
      assertEquals(fresh, TierScenarios.historyKnown(TierKind.NONE, location, t0, ScenarioMode.TRAINING))
      assertEquals(
        TierScenarios.localPriorsKept(TierKind.FRESH, location, t0, ScenarioMode.TRAINING),
        TierScenarios.localPriorsKept(TierKind.STALE, location, t0, ScenarioMode.TRAINING),
      )
    }
  }

  @Test
  fun `le estrazioni sono deterministiche e dipendono dalla localita'`() {
    val t0 = hourly[1234]
    assertEquals(
      TierScenarios.historyKnown(TierKind.FRESH, location, t0, ScenarioMode.TRAINING),
      TierScenarios.historyKnown(TierKind.FRESH, location, t0, ScenarioMode.TRAINING),
    )
    val draws = BenchLocations.map { l -> hourly.take(400).map { TierScenarios.historyKnown(TierKind.FRESH, l.name, it, ScenarioMode.TRAINING) } }
    assertTrue("le localita' non devono avere le stesse estrazioni", draws.distinct().size > 1)
  }

  @Test
  fun `il bias della quota e' per localita' e per mese, normale con sigma 1,5`() {
    val month = Instant.parse("2025-03-01T00:00:00Z").toEpochMilli()
    val first = TierScenarios.levelBiasHpa(location, month)
    // Tutto il mese lo stesso bias, e deterministico.
    for (h in 0 until 31 * 24 step 13) assertEquals(first, TierScenarios.levelBiasHpa(location, month + h * hour), 0.0)
    assertEquals(first, TierScenarios.levelBiasHpa(location, month), 0.0)
    // Il mese dopo e le altre localita' sono estrazioni diverse.
    assertNotEquals(first, TierScenarios.levelBiasHpa(location, Instant.parse("2025-04-01T00:00:00Z").toEpochMilli()), 0.0)
    assertNotEquals(first, TierScenarios.levelBiasHpa("milano", month), 0.0)

    val values = ArrayList<Double>()
    for (l in BenchLocations) {
      for (m in 0 until 48) {
        values += TierScenarios.levelBiasHpa(l.name, Instant.parse("2022-11-01T00:00:00Z").toEpochMilli() + m * 30L * 24 * hour)
      }
    }
    val mean = values.average()
    val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    assertEquals("media $mean", 0.0, mean, 0.25)
    assertEquals("sigma $sd", TierScenarios.LEVEL_BIAS_SIGMA_HPA, sd, 0.25)
    assertTrue(values.all { it.isFinite() && abs(it) < 8.0 })
  }
}
