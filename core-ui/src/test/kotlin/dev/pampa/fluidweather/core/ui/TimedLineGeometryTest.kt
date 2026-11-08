package dev.pampa.fluidweather.core.ui

import org.junit.Assert.*
import org.junit.Test

class TimedLineGeometryTest {
  private val minute = 60_000L
  @Test fun elapsedTimeDoesNotCompressSeventeenHourGap() {
    val result = timedLineGeometry(listOf(0, 17 * 60 * minute, 19 * 60 * minute), 30 * minute)
    assertEquals(3, result.size)
    assertEquals(17f / 19f, result[1].single().second, .00001f)
    assertEquals(1f, result.last().single().second, 0f)
  }
  @Test fun thresholdIsInclusiveButUnsupportedGapBreaksTheCurve() {
    val result = timedLineGeometry(listOf(0, 30 * minute, 60 * minute + 1, 65 * minute), 30 * minute)
    assertEquals(listOf(listOf(0, 1), listOf(2, 3)), result.map { segment -> segment.map { it.first } })
  }
  @Test fun ambiguousOrUnorderedClocksAreNotPlottedAsContinuous() {
    assertTrue(timedLineGeometry(listOf(1, 1, 2), minute).isEmpty())
    assertTrue(timedLineGeometry(listOf(2, 1), minute).isEmpty())
    assertTrue(timedLineGeometry(emptyList(), minute).isEmpty())
  }
}
