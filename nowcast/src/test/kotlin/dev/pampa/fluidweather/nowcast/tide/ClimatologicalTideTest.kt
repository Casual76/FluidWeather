package dev.pampa.fluidweather.nowcast.tide

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimatologicalTideTest {

  /** 2024-03-20T00:00:00Z: equinozio, fattore stagionale S2 al massimo. */
  private val equinox = 1_710_892_800_000L

  @Test
  fun `all'equatore S2 vale piu' di un hPa, in linea con Haurwitz`() {
    val tide = ClimatologicalTide(latitude = 0.0, longitude = 0.0, referenceTimestampMillis = equinox)
    assertTrue(tide.s2AmplitudeHpa in 1.0..1.35)
  }

  @Test
  fun `a sessanta gradi la marea e' un ottavo di quella equatoriale`() {
    val equator = ClimatologicalTide(0.0, 0.0, equinox)
    val north = ClimatologicalTide(60.0, 0.0, equinox)
    assertEquals(8.0, equator.s2AmplitudeHpa / north.s2AmplitudeHpa, 0.5)
  }

  @Test
  fun `ai poli non c'e' quasi niente da sottrarre`() {
    val polar = ClimatologicalTide(89.0, 0.0, equinox)
    assertTrue(polar.s1AmplitudeHpa + polar.s2AmplitudeHpa < 0.01)
  }

  @Test
  fun `il massimo combinato sta vicino alle 09-44 solari`() {
    val tide = ClimatologicalTide(0.0, 0.0, equinox)
    var bestHour = 0.0
    var bestValue = Double.NEGATIVE_INFINITY
    var minute = 0
    while (minute < 24 * 60) {
      val value = tide.tideAt(equinox + minute * 60_000L)
      if (value > bestValue) {
        bestValue = value
        bestHour = minute / 60.0
      }
      minute += 5
    }
    assertTrue("massimo a $bestHour", bestHour in 8.5..10.5)
  }

  @Test
  fun `la S1 del prior e' attenuata - mezzo peso, fase incerta`() {
    val tide = ClimatologicalTide(0.0, 0.0, equinox)
    assertTrue(tide.s1AmplitudeHpa < tide.s2AmplitudeHpa / 2)
  }

  @Test
  fun `la longitudine sposta la fase - la marea segue il sole`() {
    val greenwich = ClimatologicalTide(0.0, 0.0, equinox)
    val east = ClimatologicalTide(0.0, 90.0, equinox)
    // A 90E il sole passa sei ore prima: il valore ad un istante t coincide con quello di
    // Greenwich sei ore piu' tardi.
    val t = equinox + 3 * 3_600_000L
    assertEquals(greenwich.tideAt(t + 6 * 3_600_000L), east.tideAt(t), 0.02)
  }

  @Test
  fun `la marea media su un giorno intero e' circa zero - si sottrae oscillazione, non livello`() {
    val tide = ClimatologicalTide(0.0, 0.0, equinox)
    val mean = (0 until 24 * 60 step 5)
      .map { tide.tideAt(equinox + it * 60_000L) }
      .average()
    assertTrue(abs(mean) < 0.01)
  }
}
