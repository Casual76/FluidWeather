package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessAndCsvTest {

  @Test
  fun `la barra unica pesa un quinto la raffica e quattro quinti la storia`() {
    val running = NowcastReadiness.of(calibration = null, calibrationProgress = 300 to 600, historyHours = 0.0)
    assertTrue(running.calibrationRunning)
    assertEquals(0.5f, running.calibrationFraction, 1e-6f)
    assertEquals(0.1f, running.overallFraction, 1e-6f)
    assertEquals("Raffica iniziale: 5:00 di 10:00", running.stageLabel)

    val record = CalibrationRecord(0.8, 0.35, 0L, 600, 1013.0, 1012.2, 41.0)
    val halfway = NowcastReadiness.of(record, null, historyHours = 6.5)
    assertEquals(0.5f, halfway.historyFraction, 1e-6f)
    assertEquals(0.6f, halfway.overallFraction, 1e-6f)
    assertEquals("Storia barometrica: 6 h 30 min di 13 ore", halfway.stageLabel)
    assertFalse(halfway.ready)

    val ready = NowcastReadiness.of(record, null, historyHours = 17.0)
    assertTrue(ready.ready)
    assertEquals(1f, ready.overallFraction, 1e-6f)
    assertEquals("Barometro pronto", ready.stageLabel)
  }

  @Test
  fun `il CSV ha intestazione fissa, una riga per lettura, UTC e punto decimale`() {
    val samples = listOf(
      PressureSample(1_700_000_060_000L, 1013.456, SampleSource.PERIODIC, "b1", 41.0, 43.83193, 11.19924, ActivityKind.STILL, 90),
      PressureSample(1_700_000_000_000L, 1013.4, SampleSource.CALIBRATION, null, null, null, null, ActivityKind.UNKNOWN, null),
    )
    val csv = PressureCsv.render(samples)
    val lines = csv.trimEnd().split('\n')
    assertEquals(3, lines.size)
    assertEquals(PressureCsv.HEADER, lines[0])
    // Ordinate per tempo: la lettura di taratura viene prima.
    assertEquals("1700000000000,2023-11-14T22:13:20Z,1013.400,CALIBRATION,,,,,UNKNOWN,", lines[1])
    assertEquals("1700000060000,2023-11-14T22:14:20Z,1013.456,PERIODIC,b1,41.0,43.83193,11.19924,STILL,90", lines[2])
  }
}
