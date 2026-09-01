package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureTrendTest {

  private fun sample(minute: Int, hPa: Double) = PressureSample(
    timestampMillis = minute * 60_000L,
    pressureHpa = hPa,
    source = SampleSource.PERIODIC,
  )

  @Test
  fun `caduta di un hPa in un'ora misura meno uno`() {
    val samples = (0..60 step 15).map { sample(it, 1013.0 - it / 60.0) }
    assertEquals(-1.0, PressureTrend.hPaPerHour(samples)!!, 1e-9)
  }

  @Test
  fun `pressione stabile misura zero`() {
    val samples = (0..60 step 15).map { sample(it, 1013.0) }
    assertEquals(0.0, PressureTrend.hPaPerHour(samples)!!, 1e-9)
  }

  @Test
  fun `con meno di due campioni non esiste tendenza`() {
    assertNull(PressureTrend.hPaPerHour(emptyList()))
    assertNull(PressureTrend.hPaPerHour(listOf(sample(0, 1013.0))))
  }

  @Test
  fun `campioni allo stesso istante non producono pendenza`() {
    assertNull(PressureTrend.hPaPerHour(listOf(sample(0, 1013.0), sample(0, 1010.0))))
  }

  @Test
  fun `l'ordine dei campioni non conta`() {
    val ordered = (0..60 step 10).map { sample(it, 1013.0 - it / 30.0) }
    assertEquals(
      PressureTrend.hPaPerHour(ordered)!!,
      PressureTrend.hPaPerHour(ordered.shuffled())!!,
      1e-9,
    )
  }

  @Test
  fun `la sorveglianza scatta sopra soglia in entrambe le direzioni`() {
    assertTrue(PressureTrend.callsForSurveillance(-1.2))
    assertTrue(PressureTrend.callsForSurveillance(1.2))
    assertFalse(PressureTrend.callsForSurveillance(0.5))
    assertFalse(PressureTrend.callsForSurveillance(null))
  }
}
