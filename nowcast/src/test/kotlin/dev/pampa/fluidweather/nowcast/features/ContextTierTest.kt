package dev.pampa.fluidweather.nowcast.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTierTest {

  private val minute = 60_000L
  private val hour = 60 * minute

  @Test
  fun `fresco fino a novanta minuti compresi`() {
    assertEquals(ContextTier.FRESH, ContextTier.of(0L, samePlace = true, hasClimatology = true))
    assertEquals(ContextTier.FRESH, ContextTier.of(90 * minute, samePlace = true, hasClimatology = true))
    assertEquals(ContextTier.STALE, ContextTier.of(90 * minute + 1, samePlace = true, hasClimatology = true))
  }

  @Test
  fun `vecchio fino a dodici ore comprese, poi niente`() {
    assertEquals(ContextTier.STALE, ContextTier.of(12 * hour, samePlace = true, hasClimatology = true))
    assertEquals(ContextTier.NONE, ContextTier.of(12 * hour + 1, samePlace = true, hasClimatology = true))
    assertEquals(ContextTier.NONE_NOCLIMA, ContextTier.of(12 * hour + 1, samePlace = true, hasClimatology = false))
  }

  @Test
  fun `il contesto di un altro posto non e' contesto`() {
    assertEquals(ContextTier.NONE, ContextTier.of(10 * minute, samePlace = false, hasClimatology = true))
    assertEquals(ContextTier.NONE_NOCLIMA, ContextTier.of(10 * minute, samePlace = false, hasClimatology = false))
  }

  @Test
  fun `senza contesto decide la climatologia`() {
    assertEquals(ContextTier.NONE, ContextTier.of(null, samePlace = true, hasClimatology = true))
    assertEquals(ContextTier.NONE_NOCLIMA, ContextTier.of(null, samePlace = true, hasClimatology = false))
    // Col contesto la climatologia non cambia il livello.
    assertEquals(ContextTier.FRESH, ContextTier.of(5 * minute, samePlace = true, hasClimatology = false))
    assertEquals(ContextTier.STALE, ContextTier.of(3 * hour, samePlace = true, hasClimatology = false))
  }

  @Test
  fun `un orologio che salta un poco all'indietro non butta il contesto`() {
    assertEquals(ContextTier.FRESH, ContextTier.of(-minute, samePlace = true, hasClimatology = true))
    assertEquals(
      ContextTier.FRESH,
      ContextTier.of(-ContextTier.CLOCK_SKEW_TOLERANCE_MILLIS, samePlace = true, hasClimatology = true),
    )
    assertEquals(
      ContextTier.NONE,
      ContextTier.of(-ContextTier.CLOCK_SKEW_TOLERANCE_MILLIS - 1, samePlace = true, hasClimatology = true),
    )
  }

  @Test
  fun `le soglie sono quelle dichiarate`() {
    assertEquals(90 * minute, ContextTier.FRESH_MAX_AGE_MILLIS)
    assertEquals(12 * hour, ContextTier.STALE_MAX_AGE_MILLIS)
    assertEquals(3_000.0, ContextTier.SAME_PLACE_RADIUS_METERS, 0.0)
    assertTrue(ContextTier.FRESH.hasContext && ContextTier.STALE.hasContext)
    assertFalse(ContextTier.NONE.hasContext || ContextTier.NONE_NOCLIMA.hasContext)
  }
}
