package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationSegments
import org.junit.Assert.*
import org.junit.Test

class SampleLocationContextTest {
  private val now = 1_700_000_000_000L
  private val mountain = LocationSnapshot(45.0, 9.0, 2000.0, now)
  private fun sample(at: Long, fix: LocationSnapshot?) = TimedReading(at, 1013.0)
    .toPressureSample(SampleSource.CALIBRATION, "burst", fix, ActivityKind.STILL to 90)

  @Test fun `yesterdays mountain fix cannot label todays valley pressure`() {
    val recorded = sample(now, mountain.copy(fixedAtMillis = now - 86_400_000L))
    assertEquals(1013.0, recorded.pressureHpa, 0.0)
    assertNull(recorded.altitudeMeters)
    assertNull(recorded.latitude)
    assertNull(recorded.longitude)
  }

  @Test fun `fresh fix enriches pressure and future or undated fixes do not`() {
    assertEquals(2000.0, sample(now, mountain).altitudeMeters!!, 0.0)
    assertNull(sample(now, mountain.copy(fixedAtMillis = now + 1)).latitude)
    assertNull(sample(now, mountain.copy(fixedAtMillis = null)).altitudeMeters)
  }

  @Test fun `fix expires for each reading inside a long burst`() {
    assertNotNull(sample(now + LocationSnapshot.FRESH_MAX_AGE_MILLIS, mountain).altitudeMeters)
    assertNull(sample(now + LocationSnapshot.FRESH_MAX_AGE_MILLIS + 1, mountain).altitudeMeters)
    assertNull(sample(now, null).altitudeMeters)
  }

  @Test fun `expired altitude cannot be borrowed by a later unknown calibration segment`() {
    val recorded = (0 until 120).map { second ->
      sample(now + second * 1_000L, if (second < 60) mountain else null)
    }
    val segments = CalibrationSegments.of(recorded)
    assertEquals(2, segments.size)
    assertEquals(2000.0, segments.first().altitudeMeters!!, 0.0)
    assertNull(segments.last().altitudeMeters)
  }

  @Test fun `invalid coordinates and altitude are never written as measured context`() {
    assertNull(sample(now, mountain.copy(latitude = Double.NaN)).latitude)
    assertNull(sample(now, mountain.copy(altitudeMeters = Double.NaN)).altitudeMeters)
    assertEquals(1013.0, sample(now, null).pressureHpa, 0.0)
  }
}
