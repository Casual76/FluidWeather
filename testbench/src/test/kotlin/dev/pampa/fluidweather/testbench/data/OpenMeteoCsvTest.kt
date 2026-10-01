package dev.pampa.fluidweather.testbench.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoCsvTest {

  @Test
  fun `una risposta a un modello si legge senza suffisso ne' unita'`() {
    val text = listOf(
      "latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation",
      "43.83,11.199999,59.0,0,GMT,GMT",
      "",
      "time,precipitation (mm),pressure_msl (hPa)",
      "1790294400,0.00,1017.9",
      "1790298000,NaN,1017.6",
      "",
    ).joinToString("\n")

    val csv = OpenMeteoCsv.parse(text, "meteofrance_seamless")

    assertEquals(listOf("precipitation", "pressure_msl"), csv.columns)
    assertFalse(csv.hasLocationId)
    assertEquals(2, csv.rows.size)
    assertEquals(1_790_294_400L, csv.rows[0].epochSeconds)
    assertEquals(listOf("0.00", "1017.9"), csv.rows[0].cells)
    // "NaN" e' assente, non un numero.
    assertEquals(listOf("", "1017.6"), csv.rows[1].cells)
    assertNull(csv.rows[1].number(0))
    assertEquals(1017.6, csv.rows[1].number(1)!!, 1e-9)
  }

  @Test
  fun `a piu' modelli l'API aggiunge il suffisso e lo si cerca per modello`() {
    val text = listOf(
      "latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation",
      "43.83,11.199999,59.0,0,GMT,GMT",
      "",
      "time,precipitation_meteofrance_seamless (mm),pressure_msl_meteofrance_seamless (hPa)," +
        "precipitation_ukmo_seamless (mm),pressure_msl_ukmo_seamless (hPa)",
      "1790294400,0.00,1017.9,0.30,1017.7",
    ).joinToString("\n")

    val raw = OpenMeteoCsv.parse(text)
    assertEquals(
      listOf(
        "precipitation_meteofrance_seamless", "pressure_msl_meteofrance_seamless",
        "precipitation_ukmo_seamless", "pressure_msl_ukmo_seamless",
      ),
      raw.columns,
    )
    assertEquals(2, raw.columnIndex("precipitation", "ukmo_seamless"))
    assertEquals(1, raw.columnIndex("pressure_msl", "meteofrance_seamless"))
    assertEquals(0.30, raw.rows[0].number(raw.columnIndex("precipitation", "ukmo_seamless"))!!, 1e-9)

    // Dicendo quale modello si e' chiesto, il suffisso di quel modello sparisce.
    val stripped = OpenMeteoCsv.parse(text, "ukmo_seamless")
    assertEquals(2, stripped.columnIndex("precipitation"))
    assertEquals(3, stripped.columnIndex("pressure_msl"))
  }

  @Test
  fun `precipitation non pesca precipitation_probability`() {
    val text = "time,precipitation_probability (%),precipitation (mm)\n1,50,0.1\n"
    val csv = OpenMeteoCsv.parse(text)
    assertEquals(1, csv.columnIndex("precipitation"))
    assertEquals(0, csv.columnIndex("precipitation_probability"))

    val onlyProbability = OpenMeteoCsv.parse("time,precipitation_probability (%)\n1,50\n")
    val failure = runCatching { onlyProbability.columnIndex("precipitation") }.exceptionOrNull()
    assertTrue("niente corrispondenza per prefisso", failure is IllegalStateException)
  }

  @Test
  fun `una risposta a piu' localita' ha location_id e un solo blocco di righe`() {
    val text = listOf(
      "location_id,latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation",
      "0,43.83128,11.164902,59.0,0,GMT,GMT",
      "1,45.448154,9.169279,138.0,0,GMT,GMT",
      "",
      "location_id,time,precipitation (mm),pressure_msl (hPa)",
      "0,1790553600,NaN,1022.3",
      "0,1790557200,0.00,1022.2",
      "1,1790553600,0.10,1019.0",
      "1,1790557200,NaN,1019.1",
    ).joinToString("\r\n") // le risposte vere arrivano anche con CRLF

    val csv = OpenMeteoCsv.parse(text, "ecmwf_ifs")

    assertTrue(csv.hasLocationId)
    assertEquals(listOf("precipitation", "pressure_msl"), csv.columns)
    assertEquals(listOf(0, 0, 1, 1), csv.rows.map { it.locationId })
    assertEquals(1_790_553_600L, csv.rows[2].epochSeconds)
    // L'ora 0 di una corsa puo' essere nulla: si legge come assente, sulla localita' giusta.
    assertNull(csv.rows[0].number(0))
    assertEquals(0.10, csv.rows[2].number(0)!!, 1e-9)
    assertEquals(1019.1, csv.rows[3].number(1)!!, 1e-9)
    assertNull(csv.rows[3].number(0))
  }

  @Test
  fun `senza intestazione non si indovina`() {
    val failure = runCatching { OpenMeteoCsv.parse("{\"error\":true,\"reason\":\"x\"}") }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
  }

  @Test
  fun `la serie normalizzata si cerca solo per istante esatto`() {
    val series = HourlySeries.parse(
      "time,precipitation,pressure_msl\n" +
        "3600,0.50,1010.0\n" +
        "7200,,1011.0\n" +
        "10800,0.00,\n",
    )

    assertEquals(3, series.size)
    assertEquals(3_600_000L, series.firstMillis)
    assertEquals(10_800_000L, series.lastMillis)
    assertEquals(0.5, series.at(3_600_000L, "precipitation")!!, 1e-9)
    assertEquals(1011.0, series.at(7_200_000L, "pressure_msl")!!, 1e-9)
    // Vuoto = assente; istante fuori griglia = assente; mai il vicino piu' prossimo.
    assertNull(series.at(7_200_000L, "precipitation"))
    assertNull(series.at(10_800_000L, "pressure_msl"))
    assertNull(series.at(3_600_001L, "precipitation"))
    assertNull(series.at(0L, "precipitation"))
    assertNull(series.at(99_000_000L, "precipitation"))
    assertEquals(-1, series.indexOf(5_000_000L))
    assertEquals(2, series.indexOf(10_800_000L))
  }
}
