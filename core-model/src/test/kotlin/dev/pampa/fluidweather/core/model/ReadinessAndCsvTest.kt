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
    assertEquals(ReadinessStage.CALIBRATING, running.stage)

    val record = CalibrationRecord(0.8, 0.35, 0L, 600, 1013.0, 1012.2, 41.0)
    val halfway = NowcastReadiness.of(record, null, historyHours = 6.5)
    assertEquals(0.5f, halfway.historyFraction, 1e-6f)
    assertEquals(0.6f, halfway.overallFraction, 1e-6f)
    assertEquals(ReadinessStage.HISTORY, halfway.stage)
    assertFalse(halfway.ready)

    val ready = NowcastReadiness.of(record, null, historyHours = 17.0)
    assertTrue(ready.ready)
    assertEquals(1f, ready.overallFraction, 1e-6f)
    assertEquals(ReadinessStage.READY, ready.stage)
  }

  @Test
  fun `senza barometro lo stadio e' uno solo, qualunque cosa dica la storia`() {
    val none = NowcastReadiness.of(calibration = null, calibrationProgress = null, historyHours = 0.0, sensorAvailable = false)
    assertEquals(ReadinessStage.NO_SENSOR, none.stage)
    val withRun = NowcastReadiness.of(calibration = null, calibrationProgress = 10 to 600, historyHours = 20.0, sensorAvailable = false)
    assertEquals(ReadinessStage.NO_SENSOR, withRun.stage)
  }

  @Test
  fun `il campionamento fermo si dice finche' la storia non basta, poi vince il pronto`() {
    val record = CalibrationRecord(0.8, 0.35, 0L, 600, 1013.0, 1012.2, 41.0)
    val blocked = NowcastReadiness.of(record, null, historyHours = 0.0, samplingBlocked = true)
    assertEquals(ReadinessStage.BLOCKED, blocked.stage)
    val readyAnyway = NowcastReadiness.of(record, null, historyHours = 14.0, samplingBlocked = true)
    assertEquals(ReadinessStage.READY, readyAnyway.stage)
    val calibrating = NowcastReadiness.of(record, 30 to 600, historyHours = 0.0, samplingBlocked = true)
    assertEquals(ReadinessStage.CALIBRATING, calibrating.stage)
  }

  @Test
  fun `la copertura conta le ore, non i campioni, e non giudica un archivio giovane`() {
    val hour = 3_600_000L
    val now = 100 * hour
    // Due ore di sorveglianza a una lettura al minuto: tanti campioni, due ore coperte.
    val surveillance = (0 until 120).map { now - 30 * 60_000L - it * 60_000L }
    assertEquals(3, SamplingCoverage.coveredHours(surveillance, now))
    assertTrue(SamplingCoverage.isBlocked(surveillance, now, oldestSampleMillis = now - 10 * 24 * hour))
    // Lo stesso archivio, ma installato da sei ore: sta cominciando, non e' fermo.
    assertFalse(SamplingCoverage.isBlocked(surveillance, now, oldestSampleMillis = now - 6 * hour))
    // Un telefono sano: una passata ogni quarto d'ora, con una notte in Doze da sei ore di buco.
    val healthy = (0 until 96).map { now - it * 15 * 60_000L }.filterNot { now - it in (2 * hour)..(8 * hour) }
    assertFalse(SamplingCoverage.isBlocked(healthy, now, oldestSampleMillis = now - 10 * 24 * hour))
    // Archivio vuoto: niente da giudicare.
    assertFalse(SamplingCoverage.isBlocked(emptyList(), now, oldestSampleMillis = null))
    // Campioni fuori finestra o nel futuro (orologio spostato) non coprono niente.
    assertEquals(0, SamplingCoverage.coveredHours(listOf(now - 30 * hour, now + hour), now))
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
