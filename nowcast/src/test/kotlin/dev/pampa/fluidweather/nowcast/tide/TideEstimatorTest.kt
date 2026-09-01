package dev.pampa.fluidweather.nowcast.tide

import dev.pampa.fluidweather.nowcast.cleaning.CleanPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TideEstimatorTest {

  private val estimator = TideEstimator()
  private val start = 1_704_067_200_000L // 2024-01-01T00:00:00Z
  private val longitude = 12.5
  private val latitude = 43.0

  /** La marea "vera" del sito sintetico: fasi libere, non allineate col prior. */
  private fun trueTide(timestampMillis: Long): Double {
    val angle = 2 * PI * SolarTime.solarHours(timestampMillis, longitude) / 24.0
    return 0.2 * cos(angle) - 0.25 * sin(angle) + 0.55 * cos(2 * angle) + 0.35 * sin(2 * angle)
  }

  private fun zigzag(index: Int): Double = if (index % 2 == 0) 0.03 else -0.03

  private fun point(
    timestampMillis: Long,
    seaLevel: Double,
    lat: Double? = latitude,
    lon: Double? = longitude,
  ) = CleanPoint(
    timestampMillis = timestampMillis,
    stationPressureHpa = seaLevel,
    seaLevelPressureHpa = seaLevel,
    noiseSigmaHpa = 0.0,
    altitudeMeters = null,
    latitude = lat,
    longitude = lon,
  )

  /** Dieci giorni, un punto ogni 15 minuti: marea + deriva sinottica lenta + rumore. */
  private fun tenDays(): List<CleanPoint> = (0 until 10 * 96).map { i ->
    val t = start + i * 15 * 60_000L
    val hours = i * 0.25
    point(t, 1013.0 - 0.02 * hours + trueTide(t) + zigzag(i))
  }

  @Test
  fun `con dieci giorni ben distribuiti il fit locale prende il posto del prior`() {
    val model = estimator.estimate(tenDays())

    assertEquals(TideSource.FITTED, model.source)
    // Ampiezze vere: S1 = hypot(0.2, 0.25) = 0.320; S2 = hypot(0.55, 0.35) = 0.652.
    assertEquals(0.320, model.s1AmplitudeHpa, 0.08)
    assertEquals(0.652, model.s2AmplitudeHpa, 0.08)
  }

  @Test
  fun `il modello fittato riproduce la marea vera, non solo le sue ampiezze`() {
    val model = estimator.estimate(tenDays())
    val maxError = (0 until 24 * 4)
      .map { start + 5L * 86_400_000L + it * 15 * 60_000L }
      .maxOf { abs(model.tideAt(it) - trueTide(it)) }
    assertTrue("errore massimo $maxError", maxError < 0.15)
  }

  @Test
  fun `con due giorni si resta sul prior climatologico`() {
    val short = tenDays().take(2 * 96)
    assertEquals(TideSource.CLIMATOLOGICAL, estimator.estimate(short).source)
  }

  @Test
  fun `un archivio di sole ore d'ufficio non puo' vincolare 24 ore di sinusoide`() {
    // Dieci giorni ma solo dalle 8 alle 17 solari: 10 bin su 24 — il fit resta proibito.
    val officeHours = tenDays().filter {
      SolarTime.solarHours(it.timestampMillis, longitude).toInt() in 8..17
    }
    assertEquals(TideSource.CLIMATOLOGICAL, estimator.estimate(officeHours).source)
  }

  @Test
  fun `senza coordinate non si sottrae niente, dichiaratamente`() {
    val nowhere = tenDays().map { it.copy(latitude = null, longitude = null) }
    assertEquals(TideSource.NONE, estimator.estimate(nowhere).source)
  }
}
