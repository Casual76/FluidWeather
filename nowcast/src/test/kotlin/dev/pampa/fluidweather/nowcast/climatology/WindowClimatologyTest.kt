package dev.pampa.fluidweather.nowcast.climatology

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowClimatologyTest {

  private val hour = 3_600_000L

  private fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

  /**
   * Una serie di verita' oraria da [from] a [to] (fini degli slot, estremi compresi): piove 1 mm
   * negli slot che si chiudono fra le 13 e le 18 UTC dei mesi estivi, mai altrove.
   */
  private fun summerAfternoons(from: String, to: String): Map<Long, Double> {
    val series = LinkedHashMap<Long, Double>()
    var t = utc(from)
    while (t <= utc(to)) {
      val time = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC)
      val wet = time.monthValue in 6..8 && time.hour in 13..18
      series[t] = if (wet) 1.0 else 0.0
      t += hour
    }
    return series
  }

  private val year = summerAfternoons("2023-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
  private val summerAfternoon = utc("2023-07-15T14:00:00Z")
  private val summerMorning = utc("2023-07-15T10:00:00Z")
  private val winterAfternoon = utc("2023-01-15T14:00:00Z")

  @Test
  fun `le celle ritrovano la struttura di stagione e ora`() {
    val climatology = WindowClimatology.build(year, longitudeDeg = 0.0)!!

    // Un'emissione a ogni ora dal 31 dicembre alle 23 al 31 dicembre alle 23: 8761 per la 0-1h.
    assertEquals(8761, climatology.samples("0-1h"))
    // Estate, pomeriggio solare (12-18): 92 giorni x 6 emissioni, tutte con la ora dopo bagnata.
    assertEquals(RateCell(552, 552), climatology.cellCounts("0-1h", summerAfternoon))
    // Inverno, stessa ora: mai (gennaio, febbraio, dicembre = 90 giorni).
    assertEquals(RateCell(0, 540), climatology.cellCounts("0-1h", winterAfternoon))
    // La 1-3h vede due ore piu' in la': 5 emissioni su 6 del pomeriggio, 2 su 6 della mattina.
    assertEquals(RateCell(460, 552), climatology.cellCounts("1-3h", summerAfternoon))
    assertEquals(RateCell(184, 552), climatology.cellCounts("1-3h", summerMorning))

    val overall = climatology.overallRate("0-1h")!!
    assertEquals(552.0 / 8761, overall, 1e-12)
    assertEquals((552 + 40 * overall) / (552 + 40), climatology.rate("0-1h", summerAfternoon)!!, 1e-12)
    assertEquals((40 * overall) / (540 + 40), climatology.rate("0-1h", winterAfternoon)!!, 1e-12)
    assertTrue(climatology.rate("0-1h", summerAfternoon)!! > 0.9)
    assertTrue(climatology.rate("0-1h", winterAfternoon)!! < 0.01)
  }

  @Test
  fun `la cella si sceglie dall'ancora, cioe' dall'ora piena successiva`() {
    val climatology = WindowClimatology.build(year, longitudeDeg = 0.0)!!
    // Le 11:30 si giudicano dalle 12: e' gia' pomeriggio solare.
    val halfPastEleven = utc("2023-07-15T11:30:00Z")
    assertEquals(climatology.rate("0-1h", summerAfternoon), climatology.rate("0-1h", halfPastEleven))
    // E le 23:30 del 31 agosto sono gia' autunno.
    assertEquals(
      WindowClimatology.cellOf(utc("2023-09-01T00:00:00Z"), 0.0),
      WindowClimatology.cellOf(utc("2023-08-31T23:30:00Z"), 0.0),
    )
  }

  @Test
  fun `l'ora e' quella del sole, non quella di Greenwich`() {
    // A 90 gradi est le 12-18 UTC sono la sera solare (18-24): tutto il bagnato finisce li'.
    val east = WindowClimatology.build(year, longitudeDeg = 90.0)!!
    assertEquals(RateCell(552, 552), east.cellCounts("0-1h", summerAfternoon))
    assertEquals(3, WindowClimatology.solarBinOf(summerAfternoon, 90.0))
    assertEquals(2, WindowClimatology.solarBinOf(summerAfternoon, 0.0))
    assertEquals(1, WindowClimatology.solarBinOf(utc("2024-01-01T00:00:00Z"), 90.0))
    assertEquals(3, WindowClimatology.solarBinOf(utc("2024-01-01T00:00:00Z"), -90.0))
  }

  @Test
  fun `a ovest e a est del banco le fasce girano intere, anche a cavallo della mezzanotte UTC`() {
    val midnight = utc("2025-07-01T00:00:00Z")
    // Denver, Reykjavik, Buenos Aires, Tokyo e l'antimeridiano: in ogni giorno ogni fascia ha
    // esattamente sei ore piene, e l'ora solare e' UTC + longitudine/15 riportata in [0, 24).
    for (longitude in listOf(-104.98, -21.94, -58.38, 139.69, 180.0, -180.0)) {
      val bins = (0 until 24).map { WindowClimatology.solarBinOf(midnight + it * hour, longitude) }
      assertEquals("longitudine $longitude", List(4) { 6 }, (0 until 4).map { bin -> bins.count { it == bin } })
    }
    // Denver alle 03 UTC: 03 - 7 = 20 solari del giorno prima, la sera.
    assertEquals(3, WindowClimatology.solarBinOf(utc("2025-07-01T03:00:00Z"), -104.98))
    // Denver alle 20 UTC: 13 solari, il pomeriggio.
    assertEquals(2, WindowClimatology.solarBinOf(utc("2025-07-01T20:00:00Z"), -104.98))
    // Tokyo alle 20 UTC: 5,3 solari del giorno dopo, ancora notte.
    assertEquals(0, WindowClimatology.solarBinOf(utc("2025-07-01T20:00:00Z"), 139.69))
    // La cella di un'emissione di Denver a fine agosto alle 03 UTC resta estate (mese UTC), sera.
    assertEquals(2 * WindowClimatology.SOLAR_BINS + 3, WindowClimatology.cellOf(utc("2025-08-31T03:00:00Z"), -104.98))
  }

  @Test
  fun `le stagioni sono quelle meteorologiche`() {
    assertEquals(0, WindowClimatology.seasonOf(utc("2023-12-01T00:00:00Z")))
    assertEquals(0, WindowClimatology.seasonOf(utc("2023-02-28T23:00:00Z")))
    assertEquals(1, WindowClimatology.seasonOf(utc("2023-03-01T00:00:00Z")))
    assertEquals(2, WindowClimatology.seasonOf(utc("2023-08-31T23:00:00Z")))
    assertEquals(3, WindowClimatology.seasonOf(utc("2023-11-30T23:00:00Z")))
  }

  @Test
  fun `con pochi casi la cella parla col tasso complessivo`() {
    // Un giorno solo: 25 emissioni, 6 bagnate, tutte nel pomeriggio.
    val oneDay = summerAfternoons("2023-07-01T00:00:00Z", "2023-07-02T00:00:00Z")
    val climatology = WindowClimatology.build(oneDay, longitudeDeg = 0.0)!!
    val afternoon = utc("2023-07-01T14:00:00Z")
    assertEquals(RateCell(6, 6), climatology.cellCounts("0-1h", afternoon))
    val overall = climatology.overallRate("0-1h")!!
    assertEquals(6.0 / 25, overall, 1e-12)

    // Grezza sarebbe 1,0: con 6 casi contro 40 pseudo-conteggi resta vicina allo 0,24.
    val shrunk = climatology.rate("0-1h", afternoon)!!
    assertEquals((6 + 40 * overall) / (6 + 40), shrunk, 1e-12)
    assertTrue(shrunk < 0.4)
    // Una cella senza casi e' esattamente il tasso complessivo.
    assertEquals(overall, climatology.rate("0-1h", utc("2023-01-15T14:00:00Z"))!!, 1e-12)

    // Anche senza pseudo-conteggi (una riga salvata con k = 0): mai 0/0, mai un NaN come feature.
    val unshrunk = WindowClimatology.build(oneDay, longitudeDeg = 0.0, pseudoCounts = 0)!!
    assertEquals(overall, unshrunk.rate("0-1h", utc("2023-01-15T14:00:00Z"))!!, 1e-12)
    assertEquals(1.0, unshrunk.rate("0-1h", afternoon)!!, 1e-12)
    assertEquals(0.3, RateCell(0, 0).shrunkToward(0.3, 0), 0.0)
  }

  @Test
  fun `entrano solo le emissioni con la finestra completa`() {
    val holed = year.toMutableMap()
    val hole = utc("2023-07-15T15:00:00Z")
    holed.remove(hole)
    val full = WindowClimatology.build(year, 0.0)!!
    val withHole = WindowClimatology.build(holed, 0.0)!!
    // Il buco toglie un'emissione alla 0-1h (quella delle 14), due alla 1-3h, tre alla 3-6h.
    assertEquals(full.samples("0-1h") - 1, withHole.samples("0-1h"))
    assertEquals(full.samples("1-3h") - 2, withHole.samples("1-3h"))
    assertEquals(full.samples("3-6h") - 3, withHole.samples("3-6h"))
    assertNull(WindowClimatology.build(emptyMap(), 0.0))
    assertNull(WindowClimatology.build(mapOf(hole to Double.NaN), 0.0))
  }

  @Test
  fun `il logit e' quello del tasso, ritagliato ai bordi`() {
    val climatology = WindowClimatology.build(year, 0.0)!!
    val p = climatology.rate("1-3h", summerMorning)!!
    assertEquals(ln(p / (1 - p)), climatology.logit("1-3h", summerMorning)!!, 1e-12)
    assertEquals(ln(1e-4 / (1 - 1e-4)), WindowClimatology.logitOf(0.0), 1e-12)
    assertNull(climatology.rate("0-2h", summerMorning))
    assertNull(climatology.logit("0-2h", summerMorning))
  }

  @Test
  fun `la riga di testo torna identica`() {
    val climatology = WindowClimatology.build(year, longitudeDeg = 11.164902)!!
    val text = climatology.encode()
    assertTrue(text.startsWith("WC1|11.164902|40|0-1h:"))
    assertTrue('\n' !in text)

    val decoded = WindowClimatology.decode(text)
    assertNotNull(decoded)
    decoded!!
    assertEquals(text, decoded.encode())
    assertEquals(climatology.longitudeDeg, decoded.longitudeDeg, 0.0)
    assertEquals(climatology.windowLabels, decoded.windowLabels)
    var t = utc("2023-01-01T00:00:00Z")
    while (t < utc("2024-01-01T00:00:00Z")) {
      for (label in climatology.windowLabels) {
        assertEquals(climatology.rate(label, t), decoded.rate(label, t))
      }
      t += 7 * hour
    }
  }

  @Test
  fun `una riga storta non e' una climatologia`() {
    val good = WindowClimatology.build(year, 0.0)!!.encode()
    assertNull(WindowClimatology.decode(""))
    assertNull(WindowClimatology.decode(good.replaceFirst("WC1", "WC2")))
    assertNull(WindowClimatology.decode(good.replaceFirst("|0.0|", "|est|")))
    assertNull(WindowClimatology.decode(good.substringBeforeLast(",")))
    assertNull(WindowClimatology.decode(good.replaceFirst("0/540", "541/540")))
    assertNull(WindowClimatology.decode("WC1|0.0|40"))
  }
}
