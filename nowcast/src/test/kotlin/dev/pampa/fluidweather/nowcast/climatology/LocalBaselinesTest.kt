package dev.pampa.fluidweather.nowcast.climatology

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBaselinesTest {

  private val hour = 3_600_000L

  private fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

  private fun hourOf(t: Long): Int = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC).hour

  /** Trenta giorni di marzo: slot orari dal primo alle 00 al 31 alle 00, estremi compresi. */
  private val slots: List<Long> = (0..30 * 24).map { utc("2023-03-01T00:00:00Z") + it * hour }

  /** Ogni giorno piove forte (3 mm) nelle ore che si chiudono alle 10 e alle 11, mai altrove. */
  private val truth: Map<Long, Double> = slots.associateWith { if (hourOf(it) in 10..11) 3.0 else 0.0 }

  /**
   * La pressione scende di 1,2 hPa/h dalle 7 alle 10 e torna di colpo a 1015 alle 11. Tendenze a 3
   * ore all'emissione: 8 -> -0,4, 9 -> -0,8, 10 -> -1,2, 11 -> +0,4, 12 -> +0,8, 13 -> +1,2.
   */
  private val msl: Map<Long, Double> = slots.associateWith {
    val h = hourOf(it)
    if (h in 8..10) 1015.0 - 1.2 * (h - 7) else 1015.0
  }

  private val climatology = WindowClimatology.build(truth, longitudeDeg = 0.0)!!
  private val baselines = LocalBaselines.build(truth, climatology, rainNowMm = truth, mslHpa = msl)

  private val tenOClock = utc("2023-03-10T10:00:00Z")

  /**
   * Un mondo a blocchi: ogni 48 ore piovono (1 mm) sei ore di fila, quelle con indice 0..5 del ciclo.
   * Cosi' "ha piovuto `lag` ore fa" dice qualcosa di diverso a seconda del ritardo: poco dopo la fine
   * del blocco la pioggia continua, a otto ore di distanza no.
   */
  private val blockSlots: List<Long> = (0..40 * 24).map { utc("2023-03-01T00:00:00Z") + it * hour }
  private val blockTruth: Map<Long, Double> =
    blockSlots.withIndex().associate { (i, t) -> t to if (i % 48 in 0..5) 1.0 else 0.0 }
  private val blockClimatology = WindowClimatology.build(blockTruth, longitudeDeg = 0.0)!!
  private val blockBaselines = LocalBaselines.build(blockTruth, blockClimatology)

  /** Il conto "a mano" della finestra 0-1h: per ogni emissione oraria giudicabile e ogni ritardo da 1 a 13. */
  private fun bruteForce(truth: Map<Long, Double>): Array<Array<IntArray>> {
    val counts = Array(LocalBaselines.LAG_BINS) { Array(LocalBaselines.RAIN_NOW_CLASSES) { IntArray(2) } }
    val first = truth.keys.min()
    val last = truth.keys.max()
    var anchor = first - hour
    while (anchor < last) {
      val judged = truth[anchor + hour]
      if (judged != null) {
        val wet = judged >= 0.2
        for (lag in 1..13) {
          val rainNow = truth[anchor - lag * hour] ?: continue
          val bin = when {
            lag <= 2 -> 0
            lag <= 4 -> 1
            lag <= 7 -> 2
            else -> 3
          }
          val cls = when {
            rainNow < 0.2 -> 0
            rainNow < 0.5 -> 1
            rainNow < 2.0 -> 2
            else -> 3
          }
          counts[bin][cls][1]++
          if (wet) counts[bin][cls][0]++
        }
      }
      anchor += hour
    }
    return counts
  }

  @Test
  fun `le classi della pioggia di adesso`() {
    assertEquals(0, LocalBaselines.rainNowClassOf(0.0))
    assertEquals(0, LocalBaselines.rainNowClassOf(0.19))
    assertEquals(1, LocalBaselines.rainNowClassOf(0.2))
    assertEquals(1, LocalBaselines.rainNowClassOf(0.49))
    assertEquals(2, LocalBaselines.rainNowClassOf(0.5))
    assertEquals(2, LocalBaselines.rainNowClassOf(1.99))
    assertEquals(3, LocalBaselines.rainNowClassOf(2.0))
    assertEquals(3, LocalBaselines.rainNowClassOf(40.0))
  }

  @Test
  fun `le classi della tendenza`() {
    assertEquals(0, LocalBaselines.trendClassOf(-3.0))
    assertEquals(0, LocalBaselines.trendClassOf(-1.16))
    assertEquals(1, LocalBaselines.trendClassOf(-1.0))
    assertEquals(1, LocalBaselines.trendClassOf(-0.53))
    assertEquals(2, LocalBaselines.trendClassOf(-0.3))
    assertEquals(2, LocalBaselines.trendClassOf(-0.1))
    assertEquals(3, LocalBaselines.trendClassOf(-0.09))
    assertEquals(3, LocalBaselines.trendClassOf(0.0))
    assertEquals(3, LocalBaselines.trendClassOf(0.09))
    assertEquals(4, LocalBaselines.trendClassOf(0.1))
    assertEquals(4, LocalBaselines.trendClassOf(0.52))
    assertEquals(5, LocalBaselines.trendClassOf(0.53))
    assertEquals(5, LocalBaselines.trendClassOf(2.0))
  }

  @Test
  fun `il ritardo e le sue fasce`() {
    // L'ancora e' l'emissione per eccesso: alle 10:20 e' l'ora piena delle 11.
    val issue = utc("2023-03-10T10:20:00Z")
    assertEquals(2, LocalBaselines.lagOf(issue, utc("2023-03-10T09:00:00Z")))
    assertEquals(1, LocalBaselines.lagOf(issue, utc("2023-03-10T10:00:00Z")))
    // Emissione esattamente sull'ora, contesto dello stesso istante: ritardo zero, prima fascia.
    assertEquals(0, LocalBaselines.lagOf(utc("2023-03-10T10:00:00Z"), utc("2023-03-10T10:00:00Z")))
    assertEquals(0, LocalBaselines.lagBinOf(0))
    assertEquals(0, LocalBaselines.lagBinOf(-3))
    assertEquals(0, LocalBaselines.lagBinOf(1))
    assertEquals(0, LocalBaselines.lagBinOf(2))
    assertEquals(1, LocalBaselines.lagBinOf(3))
    assertEquals(1, LocalBaselines.lagBinOf(4))
    assertEquals(2, LocalBaselines.lagBinOf(5))
    assertEquals(2, LocalBaselines.lagBinOf(7))
    assertEquals(3, LocalBaselines.lagBinOf(8))
    assertEquals(3, LocalBaselines.lagBinOf(13))
    assertNull(LocalBaselines.lagBinOf(14))
    assertNull(LocalBaselines.lagBinOf(40))
  }

  @Test
  fun `la persistenza conta cio' che segue la pioggia di adesso, per fascia di ritardo`() {
    val expected = bruteForce(blockTruth)
    for (bin in 0 until LocalBaselines.LAG_BINS) {
      val cells = blockBaselines.persistenceCounts("0-1h", bin)!!
      for (cls in 0 until LocalBaselines.RAIN_NOW_CLASSES) {
        assertEquals("fascia $bin classe $cls", RateCell(expected[bin][cls][0], expected[bin][cls][1]), cells[cls])
      }
    }
    // La classe 2 (1 mm) e' l'unica bagnata nel mondo a blocchi: le altre due sono vuote.
    assertEquals(RateCell(0, 0), blockBaselines.persistenceCounts("0-1h", 0)!![3])
    assertEquals(RateCell(0, 0), blockBaselines.persistenceCounts("0-1h", 0)!![1])
  }

  @Test
  fun `a parita' di pioggia di adesso, piu' il contesto e' vecchio meno vale`() {
    val fresh = blockBaselines.persistenceCounts("0-1h", 0)!![2]
    val old = blockBaselines.persistenceCounts("0-1h", 3)!![2]
    assertTrue("$fresh", fresh.wet.toDouble() / fresh.total > 0.4)
    // A otto-tredici ore dalla fine di un blocco di sei ore la pioggia di adesso non annuncia piu' niente.
    assertEquals(0, old.wet)
    assertTrue(old.total > 0)

    val issue = utc("2023-03-10T10:00:00Z")
    val lagTwo = blockBaselines.persistence("0-1h", issue, 1.0, contextSlotEndMillis = issue - 2 * hour)!!
    val lagTen = blockBaselines.persistence("0-1h", issue, 1.0, contextSlotEndMillis = issue - 10 * hour)!!
    assertTrue("$lagTwo contro $lagTen", lagTwo > lagTen + 0.2)
  }

  @Test
  fun `un ritardo oltre tredici ore non ha risposta`() {
    val issue = utc("2023-03-10T10:00:00Z")
    assertNotNull(blockBaselines.persistence("0-1h", issue, 1.0, contextSlotEndMillis = issue - 13 * hour))
    assertNull(blockBaselines.persistence("0-1h", issue, 1.0, contextSlotEndMillis = issue - 14 * hour))
  }

  @Test
  fun `la persistenza e' ristretta verso la climatologia della stessa emissione`() {
    val prior = blockClimatology.rate("0-1h", tenOClock)!!
    for ((lag, bin) in listOf(1 to 0, 4 to 1, 6 to 2, 12 to 3)) {
      val cell = blockBaselines.persistenceCounts("0-1h", bin)!![2]
      val got = blockBaselines.persistence("0-1h", tenOClock, rainNowMm = 1.0, contextSlotEndMillis = tenOClock - lag * hour)!!
      assertEquals((cell.wet + 40 * prior) / (cell.total + 40), got, 1e-12)
    }
    // Una classe senza casi (qui: 3 mm) e' esattamente la climatologia.
    assertEquals(prior, blockBaselines.persistence("0-1h", tenOClock, 3.0, tenOClock - hour)!!, 1e-12)
  }

  @Test
  fun `la regola barometrica conta cio' che segue ogni tendenza`() {
    val counts = baselines.barometricCounts("0-1h")!!
    assertEquals(RateCell(30, 30), counts[0]) // alle 10: crollo, e l'ora dopo piove
    assertEquals(RateCell(30, 30), counts[1]) // alle 9: caduta, e l'ora dopo piove
    assertEquals(RateCell(0, 30), counts[2]) // alle 8: lieve calo, l'ora dopo e' ancora asciutta
    assertEquals(RateCell(0, 30), counts[4])
    assertEquals(RateCell(0, 60), counts[5])
    // Il resto e' pressione ferma; le prime tre ore della serie non hanno tre ore prima.
    assertEquals(RateCell(0, 721 - 1 - 3 - 180), counts[3])
    assertEquals(-1.2, LocalBaselines.trendAt(msl, tenOClock)!!, 1e-9)
    assertNull(LocalBaselines.trendAt(msl, slots.first()))
  }

  @Test
  fun `la regola barometrica e' ristretta verso la climatologia`() {
    val prior = climatology.rate("0-1h", tenOClock)!!
    val storm = baselines.barometric("0-1h", tenOClock, trendHpaPerHour = -1.5)!!
    // Trenta casi su trenta: grezza sarebbe 1,0, ristretta resta fra il prior e 1.
    assertEquals((30 + 40 * prior) / (30 + 40), storm, 1e-12)
    assertTrue(storm > prior && storm < 1.0)
    val steady = baselines.barometric("0-1h", tenOClock, trendHpaPerHour = 0.0)!!
    assertEquals((0 + 40 * prior) / (537 + 40), steady, 1e-12)
  }

  @Test
  fun `senza ingresso nessuna risposta`() {
    assertNull(baselines.persistence("0-1h", tenOClock, null, tenOClock))
    assertNull(baselines.persistence("0-1h", tenOClock, Double.NaN, tenOClock))
    assertNull(baselines.barometric("0-1h", tenOClock, trendHpaPerHour = null))
    assertNull(baselines.barometric("0-1h", tenOClock, trendHpaPerHour = Double.NaN))
    assertNull(baselines.persistence("0-2h", tenOClock, 1.0, tenOClock))
  }

  @Test
  fun `senza pressione la regola barometrica e' la climatologia`() {
    val noBarometer = LocalBaselines.build(truth, climatology)
    val prior = climatology.rate("1-3h", tenOClock)!!
    assertEquals(prior, noBarometer.barometric("1-3h", tenOClock, trendHpaPerHour = -2.0)!!, 1e-12)
    assertTrue(noBarometer.barometricCounts("1-3h")!!.all { it.total == 0 })
    // La persistenza di default guarda la verita' stessa.
    for (bin in 0 until LocalBaselines.LAG_BINS) {
      assertEquals(baselines.persistenceCounts("1-3h", bin), noBarometer.persistenceCounts("1-3h", bin))
    }
  }

  @Test
  fun `senza la serie di pioggia la persistenza e' vuota`() {
    val none = LocalBaselines.build(truth, climatology, rainNowMm = null)
    for (bin in 0 until LocalBaselines.LAG_BINS) {
      assertTrue(none.persistenceCounts("0-1h", bin)!!.all { it.total == 0 })
    }
    val prior = climatology.rate("0-1h", tenOClock)!!
    assertEquals(prior, none.persistence("0-1h", tenOClock, 3.0, tenOClock - 2 * hour)!!, 1e-12)
  }

  @Test
  fun `la riga di testo torna identica`() {
    val text = blockBaselines.encode()
    assertTrue(text.startsWith("LB2|40|P0-1h@0:"))
    val decoded = LocalBaselines.decode(text, blockClimatology)
    assertNotNull(decoded)
    decoded!!
    assertEquals(text, decoded.encode())
    for (label in listOf("0-1h", "1-3h", "3-6h")) {
      for (bin in 0 until LocalBaselines.LAG_BINS) {
        assertEquals(blockBaselines.persistenceCounts(label, bin), decoded.persistenceCounts(label, bin))
      }
      assertEquals(blockBaselines.barometricCounts(label), decoded.barometricCounts(label))
      assertEquals(
        blockBaselines.persistence(label, tenOClock, 1.0, tenOClock - 3 * hour),
        decoded.persistence(label, tenOClock, 1.0, tenOClock - 3 * hour),
      )
    }
    assertEquals(listOf("0-1h", "1-3h", "3-6h"), decoded.windowLabels)
  }

  @Test
  fun `il vecchio formato e le righe rotte non si leggono`() {
    val text = blockBaselines.encode()
    // LB1 (senza fasce di ritardo) e' un altro formato: null, e chi lo trova lo ricostruisce.
    assertNull(LocalBaselines.decode(text.replaceFirst("LB2", "LB1"), blockClimatology))
    assertNull(LocalBaselines.decode("LB1|40|P0-1h:0/1,0/1,0/1,0/1|T0-1h:0/1,0/1,0/1,0/1,0/1,0/1", blockClimatology))
    assertNull(LocalBaselines.decode(text.replaceFirst("LB2", "LB0"), blockClimatology))
    assertNull(LocalBaselines.decode(text.replaceFirst("|P0-1h@0:", "|X0-1h@0:"), blockClimatology))
    assertNull(LocalBaselines.decode(text.substringBeforeLast(","), blockClimatology))
    assertNull(LocalBaselines.decode("LB2", blockClimatology))
    // Una fascia di ritardo mancante: la persistenza non sarebbe completa.
    val withoutBin = text.split("|").filterNot { it.startsWith("P0-1h@2:") }.joinToString("|")
    assertNull(LocalBaselines.decode(withoutBin, blockClimatology))
    // Una fascia doppia, o fuori dalle quattro.
    val firstBin = text.split("|").first { it.startsWith("P0-1h@0:") }
    assertNull(LocalBaselines.decode("$text|$firstBin", blockClimatology))
    assertNull(LocalBaselines.decode(text.replaceFirst("P0-1h@3:", "P0-1h@4:"), blockClimatology))
  }
}
