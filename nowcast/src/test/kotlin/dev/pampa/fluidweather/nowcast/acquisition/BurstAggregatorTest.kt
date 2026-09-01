package dev.pampa.fluidweather.nowcast.acquisition

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstAggregatorTest {

  private val aggregator = BurstAggregator()
  private val start = 1_700_000_000_000L

  private fun sample(
    secondsFromStart: Int,
    hPa: Double,
    burstId: String? = null,
    source: SampleSource = SampleSource.PERIODIC,
  ) = PressureSample(
    timestampMillis = start + secondsFromStart * 1_000L,
    pressureHpa = hPa,
    source = source,
    burstId = burstId,
  )

  @Test
  fun `la mediana non si muove per un campione impazzito`() {
    val burst = (0 until 29).map { sample(it, 1013.0 + (it % 2) * 0.02, burstId = "b") } +
      sample(29, 1020.0, burstId = "b") // il glitch: portiera, tocco, spike del sensore
    val result = aggregator.aggregate(burst)

    assertEquals(1, result.points.size)
    assertTrue(result.discardedBursts.isEmpty())
    assertEquals(1013.0, result.points.single().pressureHpa, 0.03)
  }

  @Test
  fun `una raffica attraversata da un ascensore viene scartata per varianza`() {
    // 30 letture che scendono linearmente di 1,2 hPa: il sensore sta cambiando piano.
    val burst = (0 until 30).map { sample(it, 1013.0 - it * 0.04, burstId = "b") }
    val result = aggregator.aggregate(burst)

    assertTrue(result.points.isEmpty())
    assertEquals(1, result.discardedBursts.size)
    assertTrue(result.discardedBursts.single().madHpa > 0.15)
  }

  @Test
  fun `le letture singole passano senza filtro con spread zero`() {
    val singles = listOf(sample(0, 1013.0), sample(600, 1013.1), sample(1200, 1013.2))
    val result = aggregator.aggregate(singles)

    assertEquals(3, result.points.size)
    assertTrue(result.points.all { it.spreadHpa == 0.0 && it.sampleCount == 1 })
  }

  @Test
  fun `sotto quattro campioni il MAD non boccia nessuno`() {
    val burst = listOf(
      sample(0, 1013.0, burstId = "b"),
      sample(1, 1014.0, burstId = "b"),
      sample(2, 1015.0, burstId = "b"),
    )
    val result = aggregator.aggregate(burst)

    assertEquals(1, result.points.size)
    assertEquals(1014.0, result.points.single().pressureHpa, 1e-9)
  }

  @Test
  fun `due raffiche back-to-back restano due punti`() {
    val samples = (0 until 10).map { sample(it, 1013.0, burstId = "prima") } +
      (10 until 20).map { sample(it, 1013.2, burstId = "seconda") }
    val result = aggregator.aggregate(samples)

    assertEquals(2, result.points.size)
  }

  @Test
  fun `i punti escono ordinati nel tempo anche se entrano mescolati`() {
    val samples = listOf(sample(1200, 1013.2), sample(0, 1013.0), sample(600, 1013.1))
    val result = aggregator.aggregate(samples)

    assertEquals(
      result.points.map { it.timestampMillis }.sorted(),
      result.points.map { it.timestampMillis },
    )
  }

  @Test
  fun `lista vuota, risultato vuoto`() {
    val result = aggregator.aggregate(emptyList())
    assertTrue(result.points.isEmpty() && result.discardedBursts.isEmpty())
  }
}
