package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.CalibrationBurst
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import org.junit.Assert.*
import org.junit.Test

class CalibrationReferenceReliabilityTest {
  private val start = 1_700_000_000_000L
  private val segment = CalibrationSegment(start, start + 299_000L, List(300) { 1015.0 }, 0.0, 45.0, 9.0)
  private val middle = start + 149_500L
  private val reference = CalibrationReferenceSample(middle, 1015.0, 15.0, 45.0, 9.0)

  @Test fun `one day later cannot normalize its small weight into a full fifteen hPa bias`() {
    val tomorrow = reference.copy(atMillis = middle + 86_400_000L, mslHpa = 1000.0)
    assertEquals(0.0, tomorrow.weightFor(segment), 0.0)
    assertNull(CalibrationMath.estimateSegments(listOf(segment), listOf(tomorrow), tomorrow.atMillis))
  }

  @Test fun `same time in another city is not an admissible reference`() {
    val otherCity = reference.copy(latitude = 43.0, longitude = 11.0)
    assertEquals(0.0, otherCity.weightFor(segment), 0.0)
    assertNull(CalibrationMath.estimateSegments(listOf(segment), listOf(otherCity), middle))
  }

  @Test fun `reference quality lowers absolute confidence even with just one vote`() {
    val exact = CalibrationMath.estimateSegments(listOf(segment), listOf(reference), middle)!!
    val older = CalibrationMath.estimateSegments(listOf(segment), listOf(reference.copy(atMillis = middle - 20 * 60_000L)), middle)!!
    val unlocated = CalibrationMath.estimateSegments(listOf(segment.copy(latitude = null, longitude = null)), listOf(reference), middle)!!
    assertEquals(0.0, exact.biasHpa, 0.001)
    assertTrue(older.confidence < exact.confidence)
    assertTrue(unlocated.confidence < exact.confidence)
    assertEquals(exact.biasHpa, older.biasHpa, 0.001)
  }

  @Test fun `unmatched segments do not inflate accepted time or sample count`() {
    val remote = segment.copy(latitude = 43.0, longitude = 11.0)
    val valid = CalibrationMath.estimateSegments(listOf(segment), listOf(reference), middle)!!
    val mixed = CalibrationMath.estimateSegments(listOf(segment, remote), listOf(reference), middle)!!
    assertEquals(valid.confidence, mixed.confidence, 0.0)
    assertEquals(segment.pressures.size, mixed.sampleCount)
    assertEquals(1, mixed.segmentCount)
  }

  @Test fun `nonfinite reference cannot produce a saved nonfinite bias`() {
    assertNull(CalibrationMath.estimateSegments(listOf(segment), listOf(reference.copy(mslHpa = Double.NaN)), middle))
    assertNull(CalibrationMath.estimateSegments(listOf(segment), listOf(reference.copy(temperatureCelsius = Double.POSITIVE_INFINITY)), middle))
  }

  @Test fun `a minute of empty archive between readings is not a stable minute`() {
    val sparse = listOf(0, CalibrationBurst.DURATION_SECONDS).map { second ->
      PressureSample(timestampMillis = start + second * 1_000L, pressureHpa = 1015.0, source = SampleSource.CALIBRATION)
    }
    assertTrue(CalibrationSegments.of(sparse).isEmpty())
  }
}
