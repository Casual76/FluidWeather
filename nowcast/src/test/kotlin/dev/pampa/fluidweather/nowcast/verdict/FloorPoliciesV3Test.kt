package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPoliciesV3Test {

  private val steps = listOf(
    RainNowFloor(0.5, mapOf("0-1h" to 0.45, "1-3h" to 0.40)),
    RainNowFloor(2.0, mapOf("0-1h" to 0.70, "1-3h" to 0.60, "3-6h" to 0.45)),
  )

  @Test
  fun `vale il gradino piu' alto che la pioggia raggiunge`() {
    val policy = ObservationFloors(ContextTier.FRESH, steps)
    assertTrue(policy.floorsFor(0.3).isEmpty())
    assertEquals(0.45, policy.floorsFor(0.5)["0-1h"]!!, 0.0)
    assertNull(policy.floorsFor(1.0)["3-6h"])
    assertEquals(0.45, policy.floorsFor(5.0)["3-6h"]!!, 0.0)
    assertTrue(policy.floorsFor(null).isEmpty())
    assertTrue(policy.floorsFor(Double.NaN).isEmpty())
  }

  @Test
  fun `l'osservazione per il motore porta i pavimenti della politica`() {
    val policy = ObservationFloors(ContextTier.FRESH, steps)
    val raining = policy.observation(3.0, "contesto")!!
    assertTrue(raining.rainingNow)
    assertEquals(0.70, raining.floorFor("0-1h"), 0.0)
    val dry = policy.observation(0.0, "contesto")!!
    assertFalse(dry.rainingNow)
    assertEquals(0.0, dry.floorFor("0-1h"), 0.0)
    assertNull(policy.observation(null, "contesto"))
    // La soglia di "piove adesso" e' quella dell'evento, come per il v2.
    assertTrue(policy.observation(RainObservation.RAINING_FROM_MM_PER_HOUR, "x")!!.rainingNow)
  }

  @Test
  fun `la politica si scrive e si rilegge identica`() {
    val policies = ContextTier.entries.associateWith { tier ->
      when (tier) {
        ContextTier.FRESH -> ObservationFloors(tier, steps, RadarFloor(floor = 0.55))
        ContextTier.STALE -> ObservationFloors(tier, listOf(RainNowFloor(0.2, mapOf("0-1h" to 0.65))))
        else -> ObservationFloors(tier)
      }
    }
    val text = ObservationFloors.encodeAll(policies)
    assertEquals(policies, ObservationFloors.decodeAll(text))
    assertNull(ObservationFloors.decodeAll("FRESH:>=x{}|radar=off"))
  }

  @Test
  fun `senza contesto non ci sono gradini`() {
    try {
      ObservationFloors(ContextTier.NONE, steps)
      throw AssertionError("doveva rifiutare")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("NONE"))
    }
  }

  @Test
  fun `la politica generata copre tutti i livelli e il radar resta spento`() {
    assertEquals(ContextTier.entries.toSet(), FloorPoliciesV3.BY_TIER.keys)
    for ((tier, policy) in FloorPoliciesV3.BY_TIER) {
      assertEquals(tier, policy.tier)
      assertNull("il radar non e' certificato dal gate: spento", policy.radar)
      if (!tier.hasContext) assertTrue(policy.whenRainingNow.isEmpty())
    }
    assertTrue(FloorPoliciesV3.VERSION.startsWith("floors-v3-"))
  }

  @Test
  fun `le soglie di default sono quelle di sempre`() {
    fun verdicts(p01: Double, p13: Double, p36: Double) = listOf(
      WindowVerdict("0-1h", p01, p01, p01, emptyList()),
      WindowVerdict("1-3h", p13, p13, p13, emptyList()),
      WindowVerdict("3-6h", p36, p36, p36, emptyList()),
    )
    assertEquals(AlertLevel.ALLERTA, alertLevelOf(verdicts(0.55, 0.1, 0.1)))
    assertEquals(AlertLevel.ALLERTA, alertLevelOf(verdicts(0.1, 0.6, 0.1)))
    assertEquals(AlertLevel.SORVEGLIANZA, alertLevelOf(verdicts(0.1, 0.1, 0.35)))
    assertEquals(AlertLevel.QUIETE, alertLevelOf(verdicts(0.3, 0.34, 0.34)))
    assertEquals(ContextTier.entries.toSet(), AlertThresholdsV3.BY_TIER.keys)
  }
}
