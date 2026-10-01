package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.data.HourlySeries
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSourcesTest {

  private val hour = 3_600_000L
  private val origin = SyntheticWorld.START_MILLIS

  /**
   * Una serie dove ogni valore dice da quale slot viene: il valore dello slot che si chiude all'ora
   * `origin + k h` e' `k` (la pioggia `k / 100`). Cosi' un contesto che leggesse lo slot sbagliato
   * si tradisce nel numero, senza bisogno di un'altra verita'.
   */
  private fun labelledSeries(hours: Int = 200): HourlySeries {
    val times = LongArray(hours) { origin + it * hour }
    fun column(scale: Double = 1.0) = DoubleArray(hours) { it * scale }
    return HourlySeries(
      times,
      mapOf(
        "temperature_2m" to column(),
        "relative_humidity_2m" to column(),
        "dew_point_2m" to DoubleArray(hours) { it - 2.0 },
        "pressure_msl" to column(),
        "cloud_cover" to column(),
        "wind_speed_10m" to column(),
        "wind_direction_10m" to column(),
        "precipitation" to column(0.01),
      ),
    )
  }

  /** Un lettore che registra ogni slot chiesto. */
  private class Recorder(private val inner: SlotReader) : SlotReader {
    val asked = ArrayList<Long>()
    override fun value(slotEndMillis: Long, variable: String): Double? {
      asked += slotEndMillis
      return inner.value(slotEndMillis, variable)
    }
  }

  @Test
  fun `il contesto non legge mai uno slot che si chiude dopo il riferimento`() {
    val random = Random(7)
    val reader = HourlySeriesReader(labelledSeries())
    repeat(500) {
      // Riferimenti a qualunque millisecondo, dentro la serie (almeno 4 ore di storia).
      val ref = origin + 4 * hour + (random.nextDouble() * 190 * hour).toLong()
      val recorder = Recorder(reader)
      val asOf = ContextSources.contextAsOf(recorder, ref)
      assertNotNull(asOf)
      assertTrue("ha chiesto qualcosa", recorder.asked.isNotEmpty())
      assertTrue(
        "slot ${recorder.asked.max()} oltre il riferimento $ref",
        recorder.asked.all { it <= ref },
      )
      assertTrue(asOf!!.slotEndMillis <= ref)
      assertTrue("lo slot 'adesso' e' l'ultimo chiuso", ref - asOf.slotEndMillis < hour)
    }
  }

  @Test
  fun `un valore futuro non cambia il contesto`() {
    // Due serie identiche fino a ref e diversissime dopo: il contesto deve essere lo stesso.
    val ref = origin + 50 * hour + 20 * 60_000L
    val base = labelledSeries()
    val future = HourlySeries(
      LongArray(200) { origin + it * hour },
      base.variables.associateWith { variable ->
        DoubleArray(200) { k -> if (origin + k * hour > ref) 99_999.0 else base.column(variable)[k] }
      },
    )
    val a = ContextSources.contextAsOf(HourlySeriesReader(base), ref)!!
    val b = ContextSources.contextAsOf(HourlySeriesReader(future), ref)!!
    assertEquals(a.context, b.context)
    assertEquals(a.slotEndMillis, b.slotEndMillis)
  }

  @Test
  fun `il mapping e' quello di toContext ma sugli slot chiusi`() {
    val reader = HourlySeriesReader(labelledSeries())
    // Riferimento alle 50:20: l'ultimo slot chiuso e' quello delle 50:00 (k = 50), non quello delle 51:00.
    val ref = origin + 50 * hour + 20 * 60_000L
    val asOf = ContextSources.contextAsOf(reader, ref)!!
    val c = asOf.context
    assertEquals(origin + 50 * hour, asOf.slotEndMillis)
    assertEquals(50.0, c.relativeHumidityPercent!!, 1e-9)
    assertEquals(50.0, c.cloudCoverPercent!!, 1e-9)
    assertEquals(50.0, c.windSpeedKmh!!, 1e-9)
    assertEquals(50.0, c.windDirectionDeg!!, 1e-9)
    assertEquals(47.0, c.windDirectionDeg3hAgo!!, 1e-9)
    assertEquals(2.0, c.dewPointSpreadC!!, 1e-9) // temperatura 50, rugiada 48
    assertEquals(50.0, c.pressureMslHpa!!, 1e-9)
    assertEquals(47.0, c.pressureMsl3hAgoHpa!!, 1e-9)
    // La pioggia dell'ultima ora e' lo slot chiuso; le ultime tre ore sono tre slot chiusi.
    assertEquals(0.50, c.rainLastHourMm!!, 1e-9)
    assertEquals(0.50 + 0.49 + 0.48, c.rainLast3hMm!!, 1e-9)
  }

  @Test
  fun `allo scoccare dell'ora lo slot che si chiude in quell'istante e' chiuso`() {
    val reader = HourlySeriesReader(labelledSeries())
    val ref = origin + 60 * hour
    assertEquals(ref, RainWindows.lastClosedSlotEnd(ref))
    assertEquals(ref, ContextSources.contextAsOf(reader, ref)!!.slotEndMillis)
    // Un millisecondo prima, quello dopo non e' ancora chiuso.
    assertEquals(ref - hour, ContextSources.contextAsOf(reader, ref - 1)!!.slotEndMillis)
  }

  @Test
  fun `senza la riga adesso il contesto non c'e' e non si ripiega su uno slot vicino`() {
    val full = labelledSeries()
    // Tolgo la riga k = 50 (la serie resta crescente): il riferimento 50:20 non ha piu' un "adesso".
    val keep = (0 until 200).filter { it != 50 }
    val holed = HourlySeries(
      LongArray(keep.size) { origin + keep[it] * hour },
      full.variables.associateWith { v -> DoubleArray(keep.size) { full.column(v)[keep[it]] } },
    )
    assertNull(ContextSources.contextAsOf(HourlySeriesReader(holed), origin + 50 * hour + 20 * 60_000L))
    // Tre ore dopo il buco il contesto torna, ma lo slot "tre ore fa" e' il buco: niente, non il vicino.
    val after = ContextSources.contextAsOf(HourlySeriesReader(holed), origin + 53 * hour)!!
    assertEquals(53.0, after.context.pressureMslHpa!!, 1e-9)
    assertNull(after.context.pressureMsl3hAgoHpa)
  }

  @Test
  fun `l'eta' del contesto sposta il riferimento all'indietro`() {
    val reader = HourlySeriesReader(labelledSeries())
    val issue = origin + 100 * hour + 40 * 60_000L
    // FRESH di 30 minuti: ref 100:10, ultimo chiuso k = 100. STALE di 3 h: ref 97:40, k = 97.
    assertEquals(origin + 100 * hour, ContextSources.contextAtAge(reader, issue, 30 * 60_000L)!!.slotEndMillis)
    assertEquals(origin + 97 * hour, ContextSources.contextAtAge(reader, issue, 3 * hour)!!.slotEndMillis)
  }

  @Test
  fun `una serie senza una colonna la dichiara assente invece di rompersi`() {
    val series = HourlySeries(longArrayOf(origin), mapOf("precipitation" to doubleArrayOf(0.4)))
    val asOf = ContextSources.contextAsOf(HourlySeriesReader(series), origin + 10 * 60_000L)!!
    assertEquals(0.4, asOf.context.rainLastHourMm!!, 1e-9)
    assertNull(asOf.context.relativeHumidityPercent)
    assertNull(asOf.context.pressureMslHpa)
  }
}
