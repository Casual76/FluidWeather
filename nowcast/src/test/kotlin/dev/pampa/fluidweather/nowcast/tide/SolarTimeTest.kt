package dev.pampa.fluidweather.nowcast.tide

import org.junit.Assert.assertEquals
import org.junit.Test

class SolarTimeTest {

  /** 2024-01-01T00:00:00Z. */
  private val midnightUtc = 1_704_067_200_000L

  @Test
  fun `a Greenwich il tempo solare e' l'UTC`() {
    assertEquals(0.0, SolarTime.solarHours(midnightUtc, 0.0), 1e-9)
    assertEquals(12.0, SolarTime.solarHours(midnightUtc + 12 * 3_600_000L, 0.0), 1e-9)
  }

  @Test
  fun `novanta gradi est sono sei ore avanti`() {
    assertEquals(6.0, SolarTime.solarHours(midnightUtc, 90.0), 1e-9)
  }

  @Test
  fun `novanta gradi ovest sono sei ore indietro, avvolte in 0-24`() {
    assertEquals(18.0, SolarTime.solarHours(midnightUtc, -90.0), 1e-9)
  }

  @Test
  fun `il giorno dell'anno viene dal calendario UTC`() {
    assertEquals(1, SolarTime.dayOfYear(midnightUtc))
    assertEquals(32, SolarTime.dayOfYear(midnightUtc + 31L * 86_400_000L))
  }
}
