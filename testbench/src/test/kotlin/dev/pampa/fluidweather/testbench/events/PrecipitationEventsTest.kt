package dev.pampa.fluidweather.testbench.events

import dev.pampa.fluidweather.testbench.data.HourlyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrecipitationEventsTest {

  private val start = 1_700_000_000_000L

  private fun record(hour: Int, precipMm: Double?) = HourlyRecord(
    timestampMillis = start + hour * 3_600_000L,
    temperatureC = 15.0,
    relativeHumidityPercent = null,
    dewPointC = null,
    surfacePressureHpa = 1013.0,
    pressureMslHpa = 1013.0,
    precipitationMm = precipMm,
    cloudCoverPercent = null,
    windSpeedKmh = null,
    windDirectionDeg = null,
  )

  // Ore 0..9: piove (0,5 mm) solo nell'ora che si chiude alle 3.
  private val events = PrecipitationEvents(
    (0..9).map { record(it, if (it == 3) 0.5 else 0.0) },
  )

  @Test
  fun `la finestra somma i record che si chiudono dentro di lei`() {
    val window = EventWindow(0, 1)
    // Da h2: la finestra (2, 3] contiene il record delle 3 -> piove.
    assertEquals(true, events.occurred(start + 2 * 3_600_000L, window))
    // Da h3: la finestra (3, 4] contiene solo il record asciutto delle 4.
    assertEquals(false, events.occurred(start + 3 * 3_600_000L, window))
  }

  @Test
  fun `le finestre lunghe vedono lontano`() {
    assertEquals(true, events.occurred(start, EventWindow(1, 3)))
    assertEquals(false, events.occurred(start + 3 * 3_600_000L, EventWindow(1, 3)))
  }

  @Test
  fun `le tracce sotto soglia non sono pioggia`() {
    val drizzle = PrecipitationEvents((0..5).map { record(it, 0.05) })
    assertEquals(false, drizzle.occurred(start, EventWindow(0, 1)))
    // Ma tre ore di pioviggine accumulano 0,15+... la soglia e' sull'accumulo di finestra.
    assertEquals(false, drizzle.occurred(start, EventWindow(1, 3)))
  }

  @Test
  fun `oltre la fine del dataset non si giudica`() {
    assertNull(events.occurred(start + 9 * 3_600_000L, EventWindow(0, 1)))
    assertNull(events.occurred(start + 7 * 3_600_000L, EventWindow(1, 3)))
  }

  @Test
  fun `un buco nei dati rende la finestra ingiudicabile`() {
    val holed = PrecipitationEvents(
      (0..9).map { record(it, if (it == 5) null else 0.0) },
    )
    assertNull(holed.occurred(start + 4 * 3_600_000L, EventWindow(0, 1)))
  }

  @Test
  fun `piove adesso guarda l'ora appena conclusa`() {
    assertEquals(true, events.rainingAt(start + 3 * 3_600_000L))
    assertEquals(false, events.rainingAt(start + 4 * 3_600_000L))
    assertNull(events.rainingAt(start + 90 * 60_000L)) // fra due record: non si inventa
  }
}
