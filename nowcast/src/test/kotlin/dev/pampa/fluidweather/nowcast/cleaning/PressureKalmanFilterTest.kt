package dev.pampa.fluidweather.nowcast.cleaning

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureKalmanFilterTest {

  private val filter = PressureKalmanFilter()
  private val start = 1_700_000_000_000L

  /** Rumore deterministico: uno zigzag di ±0,03 hPa, l'ampiezza tipica del sensore. */
  private fun zigzag(index: Int): Double = if (index % 2 == 0) 0.03 else -0.03

  private fun measurement(minutes: Int, hPa: Double) =
    Measurement(start + minutes * 60_000L, hPa, 0.0)

  @Test
  fun `pressione costante - tendenza a zero e incertezza sotto il rumore`() {
    val series = (0..24).map { i -> measurement(i * 15, 1013.0 + zigzag(i)) }
    val last = filter.filter(series).last()

    assertEquals(0.0, last.trendHpaPerHour, 0.1)
    assertEquals(1013.0, last.levelHpa, 0.05)
    assertTrue(last.levelSigmaHpa < 0.05)
  }

  @Test
  fun `rampa di meno un hPa l'ora - la tendenza converge sul valore vero`() {
    val series = (0..18).map { i ->
      val hours = i * 10 / 60.0
      measurement(i * 10, 1013.0 - hours + zigzag(i))
    }
    val last = filter.filter(series).last()

    assertEquals(-1.0, last.trendHpaPerHour, 0.25)
  }

  @Test
  fun `dopo un buco lungo il filtro sa di non sapere`() {
    val dense = (0..8).map { i -> measurement(i * 15, 1013.0 + zigzag(i)) }
    val beforeGap = filter.filter(dense).last()

    val withGap = dense + measurement(8 * 15 + 6 * 60, 1013.0)
    val afterGap = filter.filter(withGap).last()

    assertTrue(afterGap.trendSigmaHpaPerHour > beforeGap.trendSigmaHpaPerHour)
  }

  @Test
  fun `un punto rumoroso pesa meno di uno pulito`() {
    // Stessa serie, stesso outlier finale: una volta dichiarato rumoroso, una volta pulito.
    val base = (0..8).map { i -> measurement(i * 15, 1013.0) }
    val outlierAt = start + 9 * 15 * 60_000L
    val trusted = filter.filter(base + Measurement(outlierAt, 1014.0, 0.0)).last()
    val distrusted = filter.filter(base + Measurement(outlierAt, 1014.0, 0.5)).last()

    assertTrue(abs(distrusted.levelHpa - 1013.0) < abs(trusted.levelHpa - 1013.0))
  }

  @Test
  fun `misure allo stesso istante non producono passi degeneri`() {
    val series = listOf(
      measurement(0, 1013.0),
      measurement(0, 1013.1),
      measurement(15, 1013.0),
    )
    val output = filter.filter(series)
    assertTrue(output.all { it.levelSigmaHpa.isFinite() && it.trendSigmaHpaPerHour.isFinite() })
  }

  @Test
  fun `serie vuota, uscita vuota`() {
    assertTrue(filter.filter(emptyList()).isEmpty())
  }
}
