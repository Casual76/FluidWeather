package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class D4DecisionTest {

  @Test
  fun `G e' la media dei guadagni relativi per cella`() {
    val g = D4Decision.meanGain(listOf(0.10, 0.20), listOf(0.09, 0.19))
    assertEquals((0.1 + 0.05) / 2, g, 1e-12)
  }

  @Test
  fun `gli alberi solo con guadagno, gate e peso`() {
    val ok = D4Decision.decide(0.035, gbmPassesGate = true, logisticPassesGate = true, treeBytes = 1_000_000)
    assertEquals(D4Outcome.GBM, ok.outcome)
    assertEquals(ModelFamily.GBM, ok.family)
    assertTrue(ok.line.startsWith("SCELTA: alberi"))
  }

  @Test
  fun `sotto il 3 per cento vince la logistica`() {
    val r = D4Decision.decide(0.029, gbmPassesGate = true, logisticPassesGate = true, treeBytes = 1_000_000)
    assertEquals(D4Outcome.LOGISTICA, r.outcome)
    assertEquals(ModelFamily.LOGISTICA, r.family)
  }

  @Test
  fun `troppo pesanti o bocciati, logistica`() {
    assertEquals(D4Outcome.LOGISTICA, D4Decision.decide(0.05, true, true, 1_600_000).outcome)
    assertEquals(D4Outcome.LOGISTICA, D4Decision.decide(0.05, false, true, 1_000_000).outcome)
    assertEquals(D4Outcome.LOGISTICA, D4Decision.decide(0.05, null, true, 1_000_000).outcome)
    assertEquals(D4Outcome.LOGISTICA, D4Decision.decide(0.05, true, true, 0).outcome)
  }

  @Test
  fun `passano solo gli alberi con guadagno piccolo, decide l utente`() {
    val r = D4Decision.decide(0.0166, gbmPassesGate = true, logisticPassesGate = false, treeBytes = 1_300_000)
    assertEquals(D4Outcome.ESCALATE_ONLY_GBM_PASSES, r.outcome)
    assertNull(r.family)
    assertTrue(r.line.contains("DA DECIDERE"))
  }

  @Test
  fun `passano solo gli alberi ma pesano troppo, decide l utente e non la logistica bocciata`() {
    // Regressione: con G sopra soglia ma alberi oltre 1,5 MB e logistica bocciata, la regola sceglieva in silenzio la
    // logistica che non passa il gate.
    val r = D4Decision.decide(0.05, gbmPassesGate = true, logisticPassesGate = false, treeBytes = 1_600_000)
    assertEquals(D4Outcome.ESCALATE_ONLY_GBM_PASSES, r.outcome)
    assertNull(r.family)
  }

  @Test
  fun `alberi scelti ma paracadute bocciato, decide l utente`() {
    val r = D4Decision.decide(0.04, gbmPassesGate = true, logisticPassesGate = false, treeBytes = 1_300_000)
    assertEquals(D4Outcome.ESCALATE_FALLBACK_FAILS, r.outcome)
    assertNull(r.family)
  }

  @Test
  fun `nessuno passa, la logistica con il gate da riportare`() {
    val r = D4Decision.decide(0.01, gbmPassesGate = false, logisticPassesGate = false, treeBytes = 1_300_000)
    assertEquals(D4Outcome.LOGISTICA, r.outcome)
    assertTrue(r.line.contains("NON PASSA"))
  }
}
