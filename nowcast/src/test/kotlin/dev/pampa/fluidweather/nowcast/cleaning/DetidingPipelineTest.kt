package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.tide.ClimatologicalTide
import dev.pampa.fluidweather.nowcast.tide.TideSource
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'esito verificabile della fase 3: il calo pomeridiano sparisce dal segnale. La stessa serie
 * equatoriale — dove S1/S2 valgono ~1,3 hPa di oscillazione — passa nella pipeline con e senza
 * stadio 3, e la tendenza racconta due storie diverse.
 */
class DetidingPipelineTest {

  /** 2024-03-20T00:00:00Z, equinozio: la S2 e' al suo massimo stagionale. */
  private val start = 1_710_892_800_000L
  private val samplesPerDay = 96
  private val days = 3
  private val lastTimestamp = start + (days * samplesPerDay - 1) * 15 * 60_000L

  private fun zigzag(index: Int): Double = if (index % 2 == 0) 0.02 else -0.02

  /** Tre giorni fermi all'equatore: pressione sinottica piatta, sopra solo la marea e il rumore. */
  private fun equatorialSeries(): List<PressureSample> {
    val atmosphere = ClimatologicalTide(0.0, 0.0, referenceTimestampMillis = lastTimestamp)
    return (0 until days * samplesPerDay).map { i ->
      val t = start + i * 15 * 60_000L
      PressureSample(
        timestampMillis = t,
        pressureHpa = 1010.0 + atmosphere.tideAt(t) + zigzag(i),
        source = SampleSource.PERIODIC,
        latitude = 0.0,
        longitude = 0.0,
        activity = ActivityKind.STILL,
        activityConfidence = 90,
      )
    }
  }

  @Test
  fun `il calo pomeridiano sparisce dal segnale`() {
    val series = equatorialSeries()

    val without = CleaningPipeline(tideEstimator = null).process(series)
    val with = CleaningPipeline().process(series)

    // Senza stadio 3, la marea si traveste da tendenza: oscillazioni oltre il terzo di hPa/h.
    val maxRawTrend = without.filtered.maxOf { abs(it.trendHpaPerHour) }
    assertTrue("tendenza mareale $maxRawTrend", maxRawTrend > 0.35)

    // Con lo stadio 3, la quiete e' quiete: mai vicini alla soglia di sorveglianza, e alla fine
    // la tendenza e' indistinguibile da zero.
    assertTrue(with.filtered.all { abs(it.trendHpaPerHour) < 0.3 })
    assertTrue(abs(with.latest!!.trendHpaPerHour) < 0.1)

    // Niente viene scartato: la marea si sottrae, non si boccia.
    assertTrue(with.rejected.isEmpty())
  }

  @Test
  fun `con tre giorni di archivio il modello e' il prior climatologico, dichiarato`() {
    val result = CleaningPipeline().process(equatorialSeries())

    assertEquals(TideSource.CLIMATOLOGICAL, result.tide.source)
    assertTrue(result.tide.s2AmplitudeHpa in 1.0..1.35)
    // Il valore dichiarato "adesso" e' quello del modello all'ultimo punto.
    val model = ClimatologicalTide(0.0, 0.0, lastTimestamp)
    assertEquals(model.tideAt(lastTimestamp), result.tide.tideAtLatestHpa, 0.01)
  }

  @Test
  fun `senza coordinate lo stadio 3 non inventa niente`() {
    val nowhere = equatorialSeries().map { it.copy(latitude = null, longitude = null) }
    val result = CleaningPipeline().process(nowhere)

    assertEquals(TideSource.NONE, result.tide.source)
    // E il prezzo e' visibile: la marea resta nel segnale.
    assertTrue(result.filtered.maxOf { abs(it.trendHpaPerHour) } > 0.35)
  }
}
