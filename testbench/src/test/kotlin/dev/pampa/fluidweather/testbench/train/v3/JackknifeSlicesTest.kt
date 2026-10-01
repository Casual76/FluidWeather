package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il coltello a rotazione per anno: le tabelle di una riga non contengono mai il suo anno, il periodo di valutazione ne' quello di prova. */
class JackknifeSlicesTest {

  private val hour = SyntheticWorld.HOUR
  private val day = 24 * hour
  private val start = SyntheticWorld.START_MILLIS

  private fun utc(text: String) = Instant.parse(text).toEpochMilli()

  /** Un piano compresso sul mondo sintetico: Y1 venti giorni, Y2 venti, Y3 venti, TEST dal giorno 60. */
  private val plan = JackknifePlan(start, start + 20 * day, start + 40 * day, start + 60 * day)

  // ------------------------------------------------------------------ il piano vero

  @Test
  fun `il piano vero ha le date del banco`() {
    val real = JackknifePlan.real()
    assertEquals(utc("2022-11-24T00:00:00Z"), real.startMillis)
    assertEquals(utc("2023-09-01T00:00:00Z"), real.y2StartMillis)
    assertEquals(utc("2024-09-01T00:00:00Z"), real.y3StartMillis)
    assertEquals(utc("2025-09-01T00:00:00Z"), real.testStartMillis)
    assertEquals(TierPeriods.TRAIN.firstMillis, real.startMillis)
    assertEquals(TierPeriods.VALIDATION.firstMillis, real.y3StartMillis)
    assertEquals(TierPeriods.TEST.firstMillis, real.testStartMillis)
    assertEquals(30 * 3_600_000L, real.bufferMillis)
  }

  @Test
  fun `le tabelle della fase TUNE sono quelle della specifica`() {
    val real = JackknifePlan.real()
    val buffer = 30 * hour
    // Y1: gli slot di Y2 dopo il margine, fino alla storia di VALIDATION (2024-08-30T23Z).
    assertEquals(
      listOf((utc("2023-09-01T00:00:00Z") + buffer)..utc("2024-08-30T23:00:00Z")),
      real.ranges(V3Stage.TUNE, TableRegion.Y1),
    )
    // Y2: Y1 fino al margine.
    assertEquals(listOf(utc("2022-11-24T00:00:00Z")..(utc("2023-09-01T00:00:00Z") - buffer)), real.ranges(V3Stage.TUNE, TableRegion.Y2))
    // Y3 (VALIDATION): la storia del gate.
    assertEquals(listOf(utc("2022-11-24T00:00:00Z")..utc("2024-08-30T23:00:00Z")), real.ranges(V3Stage.TUNE, TableRegion.Y3))
    assertTrue(real.ranges(V3Stage.TUNE, TableRegion.TEST).isEmpty())
  }

  @Test
  fun `le tabelle della fase REFIT sono quelle della specifica`() {
    val real = JackknifePlan.real()
    val buffer = 30 * hour
    assertEquals(
      listOf((utc("2023-09-01T00:00:00Z") + buffer)..utc("2025-08-30T23:00:00Z")),
      real.ranges(V3Stage.REFIT, TableRegion.Y1),
    )
    assertEquals(
      listOf(
        utc("2022-11-24T00:00:00Z")..(utc("2023-09-01T00:00:00Z") - buffer),
        (utc("2024-09-01T00:00:00Z") + buffer)..utc("2025-08-30T23:00:00Z"),
      ),
      real.ranges(V3Stage.REFIT, TableRegion.Y2),
    )
    assertEquals(listOf(utc("2022-11-24T00:00:00Z")..utc("2024-08-30T23:00:00Z")), real.ranges(V3Stage.REFIT, TableRegion.Y3))
    // TEST: la storia del gate di TEST, i due anni prima.
    assertEquals(listOf(utc("2023-09-01T00:00:00Z")..utc("2025-08-30T23:00:00Z")), real.ranges(V3Stage.REFIT, TableRegion.TEST))
  }

  @Test
  fun `le tabelle di VALIDATION e TEST sono quelle che il gate da' alle baseline`() {
    val real = JackknifePlan.real()
    for ((period, stage, region) in listOf(
      Triple(TierPeriods.VALIDATION, V3Stage.TUNE, TableRegion.Y3),
      Triple(TierPeriods.VALIDATION, V3Stage.REFIT, TableRegion.Y3),
      Triple(TierPeriods.TEST, V3Stage.REFIT, TableRegion.TEST),
    )) {
      assertEquals(listOf(period.historyFromMillis..period.historyUntilMillis), real.ranges(stage, region))
    }
  }

  // ------------------------------------------------------------------ nessuna fuga, per tutti e due i piani

  @Test
  fun `nessuna tabella contiene l'anno della riga piu' il margine, ne' VALIDATION in TUNE, ne' TEST mai`() {
    for (p in listOf(plan, JackknifePlan.real())) {
      val buffer = p.bufferMillis
      for (stage in V3Stage.entries) {
        for (region in TableRegion.entries) {
          val ranges = p.ranges(stage, region)
          for (range in ranges) {
            when (region) {
              TableRegion.Y1, TableRegion.Y2 -> {
                // Niente dentro [inizio - margine, fine + margine]: l'anno e il margine ai confini che lo toccano.
                val forbiddenFrom = p.startOf(region) - buffer + 1
                val forbiddenTo = p.endOf(region) + buffer - 1
                assertTrue(
                  "$stage $region: $range tocca l'anno della riga",
                  range.last < forbiddenFrom || range.first > forbiddenTo,
                )
              }

              TableRegion.Y3, TableRegion.TEST ->
                // Le righe di valutazione hanno la storia del gate: finita prima del loro inizio.
                assertTrue("$stage $region: $range", range.last <= p.startOf(region) - p.finalityMillis)
            }
            // Mai un istante da cui una riga di prova avrebbe gia' visto la propria etichetta.
            if (stage == V3Stage.TUNE) assertTrue("TUNE vede VALIDATION: $range", range.last <= p.y3StartMillis - p.finalityMillis)
            assertTrue("$stage $region vede TEST: $range", range.last <= p.testStartMillis - p.finalityMillis)
            assertTrue(range.first >= p.startMillis)
          }
        }
      }
    }
  }

  // ------------------------------------------------------------------ i dati veri dietro le tabelle

  private fun world(name: String = "a", phase: Int = 0, from: Long? = null, until: Long? = null) =
    SyntheticWorld.inputs(
      name, days = 80, phase = phase,
      panelOverrideAfterMillis = from, panelOverrideUntilMillis = until,
    )

  private fun state(stage: V3Stage, region: TableRegion, vararg inputs: dev.pampa.fluidweather.testbench.tiers.LocationInputs): String =
    JackknifeSlices(plan, inputs.toList()).honest(stage, region).getValue("a").encodedState()

  @Test
  fun `un diluvio nell'anno Y2 non entra nelle tabelle di Y2, e si vede in quelle degli altri anni`() {
    val base = world()
    // Il diluvio occupa tutto Y2: dal primo slot dopo Y2.start fino all'ultimo dentro Y2.
    val y2 = world(from = plan.y2StartMillis, until = plan.y3StartMillis)
    // Le righe di Y2 non vedono Y2: nessuna differenza, in nessuna fase.
    assertEquals(state(V3Stage.TUNE, TableRegion.Y2, base), state(V3Stage.TUNE, TableRegion.Y2, y2))
    assertEquals(state(V3Stage.REFIT, TableRegion.Y2, base), state(V3Stage.REFIT, TableRegion.Y2, y2))
    // Le righe di Y1 (che leggono Y2), di Y3 (che leggono Y1+Y2) e di TEST si accorgono eccome.
    assertNotEquals(state(V3Stage.TUNE, TableRegion.Y1, base), state(V3Stage.TUNE, TableRegion.Y1, y2))
    assertNotEquals(state(V3Stage.TUNE, TableRegion.Y3, base), state(V3Stage.TUNE, TableRegion.Y3, y2))
    assertNotEquals(state(V3Stage.REFIT, TableRegion.TEST, base), state(V3Stage.REFIT, TableRegion.TEST, y2))
  }

  @Test
  fun `un diluvio nell'anno Y1 non entra nelle tabelle di Y1`() {
    val base = world()
    val y1 = world(from = plan.startMillis - hour, until = plan.y2StartMillis)
    assertEquals(state(V3Stage.TUNE, TableRegion.Y1, base), state(V3Stage.TUNE, TableRegion.Y1, y1))
    assertEquals(state(V3Stage.REFIT, TableRegion.Y1, base), state(V3Stage.REFIT, TableRegion.Y1, y1))
    assertNotEquals(state(V3Stage.TUNE, TableRegion.Y2, base), state(V3Stage.TUNE, TableRegion.Y2, y1))
  }

  @Test
  fun `in TUNE nessuna tabella vede VALIDATION, in REFIT nessuna vede TEST`() {
    val base = world()
    // Un diluvio da un giorno prima di VALIDATION in poi: la storia del gate finisce 25 ore prima.
    val validation = world(from = plan.y3StartMillis - day, until = plan.testStartMillis - day)
    for (region in listOf(TableRegion.Y1, TableRegion.Y2, TableRegion.Y3)) {
      assertEquals("TUNE $region", state(V3Stage.TUNE, region, base), state(V3Stage.TUNE, region, validation))
    }
    // In REFIT VALIDATION entra nelle tabelle di Y1 e Y2 (non nelle proprie): cambiano.
    assertNotEquals(state(V3Stage.REFIT, TableRegion.Y1, base), state(V3Stage.REFIT, TableRegion.Y1, validation))
    assertNotEquals(state(V3Stage.REFIT, TableRegion.Y2, base), state(V3Stage.REFIT, TableRegion.Y2, validation))
    assertEquals(state(V3Stage.REFIT, TableRegion.Y3, base), state(V3Stage.REFIT, TableRegion.Y3, validation))

    // TEST: un diluvio da un giorno prima della sua prima emissione in poi non tocca nessuna tabella di nessuna fase.
    val test = world(from = plan.testStartMillis - day, until = Long.MAX_VALUE)
    for (stage in V3Stage.entries) {
      for (region in TableRegion.entries) {
        if (plan.ranges(stage, region).isEmpty()) continue
        assertEquals("$stage $region", state(stage, region, base), state(stage, region, test))
      }
    }
  }

  @Test
  fun `le righe di VALIDATION hanno esattamente la storia del gate`() {
    val input = world()
    val gatePeriod = TierPeriod("val", plan.y3StartMillis, plan.testStartMillis, plan.startMillis)
    val gate = HonestBaselines.buildAll(gatePeriod, listOf(input)).getValue("a")
    val slices = JackknifeSlices(plan, listOf(input))
    assertEquals(gate.encodedState(), slices.honest(V3Stage.TUNE, TableRegion.Y3).getValue("a").encodedState())
    assertEquals(gate.encodedState(), slices.honest(V3Stage.REFIT, TableRegion.Y3).getValue("a").encodedState())
    val testPeriod = TierPeriod("test", plan.testStartMillis, plan.testStartMillis + 5 * day, plan.y2StartMillis)
    val testGate = HonestBaselines.buildAll(testPeriod, listOf(input)).getValue("a")
    assertEquals(testGate.encodedState(), slices.honest(V3Stage.REFIT, TableRegion.TEST).getValue("a").encodedState())
  }

  @Test
  fun `l'anno di una riga e i limiti delle etichette`() {
    assertEquals(TableRegion.Y1, plan.regionOf(start))
    assertEquals(TableRegion.Y2, plan.regionOf(plan.y2StartMillis))
    assertEquals(TableRegion.Y1, plan.regionOf(plan.y2StartMillis - 1))
    assertEquals(TableRegion.Y3, plan.regionOf(plan.y3StartMillis))
    assertEquals(TableRegion.TEST, plan.regionOf(plan.testStartMillis))
    assertEquals(null, plan.regionOf(start - 1))
    // In TUNE le righe di addestramento non vedono VALIDATION, quelle di VALIDATION non hanno limite.
    assertEquals(plan.y3StartMillis, plan.labelCutoffMillis(V3Stage.TUNE, TableRegion.Y1))
    assertEquals(plan.y3StartMillis, plan.labelCutoffMillis(V3Stage.TUNE, TableRegion.Y2))
    assertEquals(Long.MAX_VALUE, plan.labelCutoffMillis(V3Stage.TUNE, TableRegion.Y3))
    // In REFIT nessuna riga vede TEST.
    for (region in TableRegion.entries) assertEquals(plan.testStartMillis, plan.labelCutoffMillis(V3Stage.REFIT, region))
  }
}
