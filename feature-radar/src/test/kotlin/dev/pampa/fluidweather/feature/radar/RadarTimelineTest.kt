package dev.pampa.fluidweather.feature.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarTimelineTest {

  private val now = 1_788_333_000_000L

  @Test
  fun `le etichette dicono il tempo rispetto ad adesso`() {
    assertEquals("adesso", RadarTimeline.label(now, now))
    assertEquals("−10 min", RadarTimeline.label(now - 10 * 60_000L, now))
    assertEquals("−1 h", RadarTimeline.label(now - 60 * 60_000L, now))
    assertEquals("−1 h 40 min", RadarTimeline.label(now - 100 * 60_000L, now))
    assertEquals("+20 min", RadarTimeline.label(now + 20 * 60_000L, now))
  }

  @Test
  fun `l'ultimo fotogramma resta piu' a lungo`() {
    assertTrue(RadarTimeline.frameDelayMillis(12, 13) > RadarTimeline.frameDelayMillis(3, 13))
  }

  @Test
  fun `frazione e indice si corrispondono agli estremi e nel mezzo`() {
    assertEquals(0, RadarTimeline.indexForFraction(0f, 13))
    assertEquals(12, RadarTimeline.indexForFraction(1f, 13))
    assertEquals(6, RadarTimeline.indexForFraction(0.5f, 13))
    assertEquals(0, RadarTimeline.indexForFraction(0.7f, 0))
    assertEquals(0.5f, RadarTimeline.fractionForIndex(6, 13), 1e-6f)
    assertEquals(0f, RadarTimeline.fractionForIndex(0, 1), 1e-6f)
  }
}
