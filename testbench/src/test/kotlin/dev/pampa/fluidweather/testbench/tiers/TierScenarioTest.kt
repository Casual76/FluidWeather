package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.data.BenchLocations
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierScenarioTest {

  private val hour = 3_600_000L
  private val anchors = TierPeriods.TEST.issueAnchors()

  @Test
  fun `il minuto di emissione sta sempre in zero-sessanta minuti e l'ancora resta t0`() {
    for (location in BenchLocations) {
      for (t0 in anchors) {
        val offset = TierScenarios.issueOffsetMillis(location.name, t0)
        assertTrue("offset $offset fuori da [0, 60 min)", offset in 0 until hour)
        val issue = TierScenarios.issueMillis(location.name, t0)
        // L'ancora delle finestre e' l'emissione arrotondata per eccesso all'ora: deve essere t0.
        assertEquals(t0, RainWindows.anchorOf(issue))
      }
    }
  }

  @Test
  fun `il minuto di emissione copre davvero l'intervallo, non un angolo`() {
    val offsets = anchors.map { TierScenarios.issueOffsetMillis("sesto-fiorentino", it) }
    assertTrue("minimo ${offsets.min()}", offsets.min() < 3 * 60_000L)
    assertTrue("massimo ${offsets.max()}", offsets.max() > 57 * 60_000L)
    val mean = offsets.average()
    assertTrue("media $mean", mean in (28 * 60_000.0)..(32 * 60_000.0))
  }

  @Test
  fun `l'eta' FRESH sta in zero-novanta minuti ed e' davvero FRESH`() {
    for (location in BenchLocations) {
      for (t0 in anchors) {
        val age = TierScenarios.contextAgeMillis(TierKind.FRESH, location.name, t0)!!
        assertTrue("eta' FRESH $age", age in 0..90 * 60_000L)
        assertEquals(ContextTier.FRESH, ContextTier.of(age, samePlace = true, hasClimatology = true))
      }
    }
  }

  @Test
  fun `l'eta' STALE sta fra un'ora e mezza e dodici ore ed e' davvero STALE`() {
    val ages = ArrayList<Long>()
    for (location in BenchLocations) {
      for (t0 in anchors) {
        val age = TierScenarios.contextAgeMillis(TierKind.STALE, location.name, t0)!!
        ages += age
        assertTrue("eta' STALE $age", age > 90 * 60_000L && age <= 12 * hour)
        assertEquals(ContextTier.STALE, ContextTier.of(age, samePlace = true, hasClimatology = true))
      }
    }
    // La distribuzione e' uniforme sull'intervallo: si vedono entrambe le code e la media e' a meta'.
    assertTrue(ages.min() < 2 * hour)
    assertTrue(ages.max() > 11 * hour)
    assertEquals(6.75 * hour, ages.average(), 0.25 * hour)
  }

  @Test
  fun `le eta' fisse sono esatte e i livelli senza contesto non hanno eta'`() {
    val t0 = anchors[10]
    assertEquals(3 * hour, TierScenarios.contextAgeMillis(TierKind.STALE_3H, "milano", t0))
    assertEquals(6 * hour, TierScenarios.contextAgeMillis(TierKind.STALE_6H, "milano", t0))
    assertEquals(12 * hour, TierScenarios.contextAgeMillis(TierKind.STALE_12H, "milano", t0))
    for (kind in TierKind.STALE_BUCKETS) {
      val age = TierScenarios.contextAgeMillis(kind, "milano", t0)!!
      assertEquals(ContextTier.STALE, ContextTier.of(age, samePlace = true, hasClimatology = true))
    }
    assertNull(TierScenarios.contextAgeMillis(TierKind.NONE, "milano", t0))
    assertNull(TierScenarios.contextAgeMillis(TierKind.NONE_NOCLIMA, "milano", t0))
    assertEquals(ContextTier.NONE, ContextTier.of(null, samePlace = false, hasClimatology = true))
    assertEquals(ContextTier.NONE_NOCLIMA, ContextTier.of(null, samePlace = false, hasClimatology = false))
  }

  @Test
  fun `i semi sono deterministici, indipendenti dall'ordine e distinti fra localita' e livelli`() {
    val forward = anchors.take(200).map { TierScenarios.scenariosFor("genova", it) }
    val backward = anchors.take(200).reversed().map { TierScenarios.scenariosFor("genova", it) }.reversed()
    assertEquals(forward, backward)
    assertEquals(forward, anchors.take(200).map { TierScenarios.scenariosFor("genova", it) })

    val t0 = anchors[42]
    val offsets = BenchLocations.map { TierScenarios.issueOffsetMillis(it.name, t0) }.toSet()
    assertTrue("le localita' pescano tutte lo stesso minuto: $offsets", offsets.size > 5)

    // FRESH e STALE non condividono il seme: non sono la stessa estrazione riscalata.
    val fresh = anchors.take(200).map { TierScenarios.contextAgeMillis(TierKind.FRESH, "genova", it)!! / (90.0 * 60_000.0) }
    val stale = anchors.take(200).map {
      (TierScenarios.contextAgeMillis(TierKind.STALE, "genova", it)!! - TierScenarios.STALE_AGE_MIN_MILLIS) /
        (TierScenarios.STALE_AGE_MAX_MILLIS - TierScenarios.STALE_AGE_MIN_MILLIS).toDouble()
    }
    val identical = fresh.indices.count { kotlin.math.abs(fresh[it] - stale[it]) < 0.01 }
    assertTrue("FRESH e STALE troppo simili: $identical/200", identical < 20)
    assertNotEquals(TierScenarios.issueOffsetMillis("genova", t0), TierScenarios.issueOffsetMillis("genova", t0 + 3 * hour))
  }

  @Test
  fun `gli scenari di un'emissione hanno un'eta' se e solo se hanno contesto`() {
    val scenarios = TierScenarios.scenariosFor("tokyo", anchors[5])
    assertEquals(TierKind.entries.size, scenarios.size)
    for (scenario in scenarios) assertEquals(scenario.kind.hasContext, scenario.contextAgeMillis != null)
  }

  @Test
  fun `i periodi del piano e la storia delle baseline`() {
    val train = TierPeriods.TRAIN
    val validation = TierPeriods.VALIDATION
    val test = TierPeriods.TEST
    assertEquals(TruthPanel.AVAILABLE_FROM_MILLIS, train.firstMillis)
    assertEquals(train.endExclusiveMillis, validation.firstMillis)
    assertEquals(validation.endExclusiveMillis, test.firstMillis)

    // TEST: due anni pieni prima dell'inizio; VALIDATION: la storia "che c'e'", dalla prima ora del pannello.
    assertEquals(
      LocalDate.of(2023, 9, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
      test.historyFromMillis,
    )
    assertEquals(TruthPanel.AVAILABLE_FROM_MILLIS, validation.historyFromMillis)
    // La storia finisce dove la verita' era gia' definitiva alla prima emissione (fino a un'ora prima di
    // t0): non a firstMillis, che e' nel futuro di quell'emissione.
    assertEquals(test.firstMillis - 3_600_000L - TruthPanel.FINALITY_MILLIS, test.historyUntilMillis)
    for (period in listOf(validation, test)) {
      val earliestIssue = period.issueAnchors().minOf { t0 ->
        BenchLocations.minOf { TierScenarios.issueMillis(it.name, t0) }
      }
      assertTrue(period.historyUntilMillis + TruthPanel.FINALITY_MILLIS <= earliestIssue)
    }

    // Un'emissione ogni tre ore, dalle 00 del primo giorno alle 21 dell'ultimo.
    assertEquals(365 * 8, test.issueAnchors().size)
    assertEquals(test.firstMillis, test.issueAnchors().first())
    assertEquals(test.endExclusiveMillis - 3 * hour, test.issueAnchors().last())
    assertTrue(test.issueAnchors().toList().zipWithNext().all { (a, b) -> b - a == 3 * hour })
    assertEquals(TierPeriods.VALIDATION, TierPeriods.byName("VALIDATION"))
    assertEquals(TierPeriods.TEST, TierPeriods.byName("test"))
    assertNull(TierPeriods.byName("train"))
    assertNull(TierPeriods.byName(null))
  }
}
