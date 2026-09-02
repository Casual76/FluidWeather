package dev.pampa.fluidweather.nowcast.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMathTest {

  @Test
  fun `la mediana ignora gli sbalzi e il bias e' locale ridotta meno riferimento`() {
    // Seicento letture a 1013.0 con qualche sbalzo: la mediana resta 1013.0.
    val pressures = List(600) { if (it % 100 == 0) 1015.5 else 1013.0 }
    val record = CalibrationMath.estimate(
      stationPressures = pressures,
      altitudeMeters = 0.0,
      temperatureCelsius = 15.0,
      referenceMslHpa = 1012.2,
      nowMillis = 1_700_000_000_000L,
    )!!
    assertEquals(1013.0, record.localMslHpa, 1e-9)
    assertEquals(0.8, record.biasHpa, 1e-9)
    assertEquals(600, record.sampleCount)
    assertEquals(CalibrationMath.CONFIDENCE_WITH_ALTITUDE, record.confidence, 1e-9)
  }

  @Test
  fun `con la quota la riduzione al mare fa la differenza, e senza quota la fiducia crolla`() {
    val withAltitude = CalibrationMath.estimate(List(600) { 1008.0 }, altitudeMeters = 41.0, temperatureCelsius = 20.0, referenceMslHpa = 1013.0, nowMillis = 0L)!!
    // 41 m valgono circa 4,9 hPa: la lettura ridotta sta vicino al riferimento, il bias e' piccolo.
    assertTrue(kotlin.math.abs(withAltitude.biasHpa) < 0.5)
    val withoutAltitude = CalibrationMath.estimate(List(600) { 1008.0 }, altitudeMeters = null, temperatureCelsius = null, referenceMslHpa = 1013.0, nowMillis = 0L)!!
    assertEquals(-5.0, withoutAltitude.biasHpa, 1e-9)
    assertEquals(CalibrationMath.CONFIDENCE_WITHOUT_ALTITUDE, withoutAltitude.confidence, 1e-9)
  }

  @Test
  fun `poche letture, poca fiducia; nessuna lettura, nessuna stima`() {
    val few = CalibrationMath.estimate(List(60) { 1013.0 }, 0.0, 15.0, 1013.0, 0L)!!
    assertEquals(CalibrationMath.CONFIDENCE_WITH_ALTITUDE * 0.1, few.confidence, 1e-9)
    assertNull(CalibrationMath.estimate(emptyList(), 0.0, 15.0, 1013.0, 0L))
  }
}
