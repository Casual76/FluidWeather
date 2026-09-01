package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.DeviceCalibration
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.PressureTrend
import dev.pampa.fluidweather.core.model.SampleSource
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I casi da manuale del piano (§5), giocati contro gli stadi 1-2 interi. L'esito verificabile
 * della fase 2 e' il primo test: l'ascensore non produce piu' un fronte.
 */
class CleaningPipelineTest {

  private val pipeline = CleaningPipeline()
  private val start = 1_700_000_000_000L

  private fun zigzag(index: Int): Double = if (index % 2 == 0) 0.02 else -0.02

  private fun sample(
    secondsFromStart: Long,
    hPa: Double,
    activity: ActivityKind = ActivityKind.STILL,
    confidence: Int? = 90,
    altitude: Double? = null,
    source: SampleSource = SampleSource.PERIODIC,
  ) = PressureSample(
    timestampMillis = start + secondsFromStart * 1_000L,
    pressureHpa = hPa,
    source = source,
    altitudeMeters = altitude,
    activity = activity,
    activityConfidence = confidence,
  )

  /** Il fattore di riduzione a una quota: comodo per costruire serie coerenti col mare. */
  private fun factor(altitude: Double) = SeaLevel.reduce(1.0, altitude)

  @Test
  fun `l'ascensore non produce un fronte`() {
    // Tre ore ferme a 1013, un punto ogni 10 minuti. A meta' serie, un giro rapido in ascensore
    // col GPS cieco (indoor): tre letture ravvicinate 1,2 hPa piu' in basso, poi di nuovo giu'.
    val base = (0..18).map { i -> sample(i * 600L, 1013.0 + zigzag(i)) }
    val elevator = listOf(
      sample(90 * 60 + 10, 1011.8, source = SampleSource.CONTINUOUS),
      sample(90 * 60 + 30, 1011.8, source = SampleSource.CONTINUOUS),
      sample(90 * 60 + 50, 1011.8, source = SampleSource.CONTINUOUS),
    )
    val result = pipeline.process(base + elevator)

    // I tre punti dell'ascensore sono stati scartati come salto non-meteo...
    assertEquals(3, result.rejected.count { it.reason == RejectionReason.NON_WEATHER_JUMP })
    // ...la tendenza resta quiete dall'inizio alla fine: la sorveglianza non deve mai scattare...
    assertTrue(result.filtered.all { abs(it.trendHpaPerHour) < 1.0 })
    assertTrue(abs(result.latest!!.trendHpaPerHour) < 0.1)
    // ...mentre sul segnale grezzo lo stesso ascensore un "fronte" lo disegnerebbe eccome.
    val rawAroundElevator = (base + elevator)
      .filter { it.timestampMillis >= start + 60 * 60_000L }
      .filter { it.timestampMillis <= start + 100 * 60_000L }
    assertTrue(abs(PressureTrend.hPaPerHour(rawAroundElevator)!!) > 1.0)
  }

  @Test
  fun `il viaggio in auto in salita viene scartato per attivita'`() {
    val before = (0..6).map { i -> sample(i * 600L, 1013.0 + zigzag(i)) }
    // Venti minuti d'auto su per la montagna: la pressione crolla di 8 hPa, ma non e' meteo.
    val driving = (1..4).map { i ->
      sample(3600L + i * 300L, 1013.0 - i * 2.0, activity = ActivityKind.IN_VEHICLE)
    }
    // Arrivo in quota cinque minuti dopo: 8 hPa piu' in basso rispetto all'ultimo punto fermo.
    val after = (0..6).map { i -> sample(5100L + i * 600L, 1005.0 + zigzag(i)) }
    val result = pipeline.process(before + driving + after)

    assertEquals(4, result.rejected.count { it.reason == RejectionReason.VEHICLE })
    // Il salto residuo viene bocciato finche' il tasso non rientra sotto soglia (~48 minuti per
    // 8 hPa), poi la serie si riaggancia da sola al nuovo livello: e' il self-healing voluto.
    assertTrue(result.rejected.count { it.reason == RejectionReason.NON_WEATHER_JUMP } in 1..5)
    assertTrue(result.cleaned.any { it.stationPressureHpa < 1006.0 })
    assertTrue(result.filtered.isNotEmpty())
  }

  @Test
  fun `la caduta pre-temporalesca NON viene scartata - e' il segnale che cerchiamo`() {
    val calm = (0..12).map { i -> sample(i * 900L, 1013.0 + zigzag(i)) }
    val falling = (1..12).map { i ->
      sample(10800L + i * 900L, 1013.0 - i * 0.375 + zigzag(i)) // -1,5 hPa/h per 3 ore
    }
    val result = pipeline.process(calm + falling)

    assertTrue(result.rejected.isEmpty())
    assertTrue(result.discardedBursts.isEmpty())
    assertEquals(-1.5, result.latest!!.trendHpaPerHour, 0.35)
    assertTrue(PressureTrend.callsForSurveillance(result.latest!!.trendHpaPerHour))
  }

  @Test
  fun `alta pressione stabile - quiete su tutta la linea`() {
    val series = (0..24).map { i -> sample(i * 900L, 1028.0 + zigzag(i)) }
    val result = pipeline.process(series)

    assertTrue(result.rejected.isEmpty())
    assertEquals(0.0, result.latest!!.trendHpaPerHour, 0.1)
    assertEquals(1028.0, result.latest!!.levelHpa, 0.1)
  }

  @Test
  fun `un cambio di quota col GPS onesto viene compensato, non scartato`() {
    // Un'ora a 100 m, poi ci si sposta a 130 m (un punto ogni 15': fuori dalla finestra del
    // dislivello). La pressione di stazione cala di ~3,5 hPa, ma al livello del mare e' piatta.
    val seaLevel = 1013.0
    val low = (0..4).map { i ->
      sample(i * 900L, seaLevel / factor(100.0) + zigzag(i), altitude = 100.0)
    }
    val high = (5..9).map { i ->
      sample(i * 900L, seaLevel / factor(130.0) + zigzag(i), altitude = 130.0)
    }
    val result = pipeline.process(low + high)

    assertTrue(result.rejected.isEmpty())
    assertEquals(seaLevel, result.latest!!.levelHpa, 0.2)
    assertTrue(result.filtered.all { abs(it.trendHpaPerHour) < 0.5 })
  }

  @Test
  fun `il bias del dispositivo si sottrae prima di tutto`() {
    val series = (0..8).map { i -> sample(i * 900L, 1015.0) }
    val result = pipeline.process(series, calibration = DeviceCalibration(biasHpa = 2.0))

    assertEquals(1013.0, result.latest!!.levelHpa, 0.05)
  }

  @Test
  fun `la temperatura reale cambia la riduzione`() {
    val series = (0..8).map { i -> sample(i * 900L, 1000.0, altitude = 200.0) }
    val standard = pipeline.process(series).latest!!.levelHpa
    val warm = pipeline.process(series, temperatureCelsius = 30.0).latest!!.levelHpa

    assertTrue(warm < standard)
  }

  @Test
  fun `senza campioni nessun crash e nessuna stima`() {
    val result = pipeline.process(emptyList())
    assertNull(result.latest)
    assertTrue(result.cleaned.isEmpty() && result.rejected.isEmpty())
  }

  @Test
  fun `i conteggi per stadio raccontano cosa e' stato mangiato`() {
    val base = (0..6).map { i -> sample(i * 600L, 1013.0) }
    val vehicle = sample(4000, 1010.0, activity = ActivityKind.IN_VEHICLE)
    val burst = (0 until 30).map { i ->
      sample(4500L + i, 1013.0 - i * 0.04, source = SampleSource.MANUAL_BURST)
        .copy(burstId = "ascensore-in-raffica")
    }
    val counts = pipeline.process(base + vehicle + burst).rejectionCounts()

    assertEquals(1, counts[RejectionReason.VEHICLE])
    assertEquals(1, counts[RejectionReason.ANOMALOUS_VARIANCE])
  }
}
