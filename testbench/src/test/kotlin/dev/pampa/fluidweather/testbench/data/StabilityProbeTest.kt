package dev.pampa.fluidweather.testbench.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class StabilityProbeTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val models = listOf("ma", "mb", "mc")
  private val hour = 3_600_000L

  private fun csv(slots: Map<Long, List<String>>): String = buildString {
    appendLine("latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation")
    appendLine("43.83,11.199999,59.0,0,GMT,GMT")
    appendLine()
    appendLine(models.joinToString(",", prefix = "time,") { "precipitation_$it (mm),pressure_msl_$it (hPa)" })
    for ((millis, rain) in slots.toSortedMap()) {
      appendLine("${millis / 1_000L}," + rain.joinToString(",") { "$it,1010.0" })
    }
  }

  private fun probe(harness: FetcherHarness, now: Long) =
    StabilityProbe(harness.http, File(tmp.root, "stability"), TestLocations[0], models, { now }) { harness.logLines += it }

  @Test
  fun `il ritardo e' l'eta' dello slot all'istantanea precedente e i bucket contano i cambi`() {
    val harness = FetcherHarness(tmp.root) { FetcherHarness.ok("") }
    val probe = probe(harness, 0L)
    val stamp = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli()

    // La riga T e' lo slot (T-1h, T]: chiuso da 0 h (la riga delle 12:00), da 6 h, da 24 h, da 40 h.
    val slots = listOf(stamp, stamp - 6 * hour, stamp - 24 * hour, stamp - 40 * hour)
    val before = probe.parseSnapshot(stamp, csv(slots.associateWith { listOf("0.05", "0.05", "0.05") }))
    val later = probe.parseSnapshot(
      stamp + 6 * hour,
      csv(
        mapOf(
          slots[0] to listOf("0.15", "0.15", "0.15"), // attraversa 0,1 mm
          slots[1] to listOf("0.05", "0.05", "0.05"), // uguale
          slots[2] to listOf("0.08", "0.05", "0.05"), // cambia un modello, ma la mediana no
          slots[3] to listOf("0.05", "0.05", "0.05"),
        ),
      ),
    )

    val observations = probe.observe(before, later)
    val median = observations.filter { it.series == StabilityProbe.PANEL_MEDIAN }
    assertEquals(setOf(0, 6, 24, 40), median.map { it.lagHours }.toSet())
    assertEquals(4 * 4, observations.size) // quattro slot x (mediana + tre modelli)

    val report = probe.report(later, listOf(before))
    val lines = report.lines()
    val lag0 = lines.single { it.trim().startsWith("0-6") && "mediana" in it }
    // 1 slot, cambio medio = massimo = 0,1, tutti e uno attraversano la soglia.
    assertTrue(lag0, lag0.contains("0.100") && lag0.contains("100.0%"))
    val lag24 = lines.single { it.trim().startsWith("24-36") && "mediana" in it }
    assertTrue(lag24, lag24.contains("0.000") && lag24.contains("0.0%"))
    val lag24ModelA = lines.single { it.trim().startsWith("24-36") && " ma " in it }
    assertTrue(lag24ModelA, lag24ModelA.contains("0.030"))
    // Il bucket senza osservazioni lo dice invece di sparire.
    assertTrue(lines.any { it.trim().startsWith("12-18") && " 0 " in it })
  }

  @Test
  fun `la mediana del pannello vuole tutti i giudici, come la verita'`() {
    val harness = FetcherHarness(tmp.root) { FetcherHarness.ok("") }
    val probe = probe(harness, 0L)
    val slot = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()
    val snapshot = probe.parseSnapshot(
      slot + hour,
      csv(mapOf(slot to listOf("0.20", "NaN", "NaN"), slot + hour to listOf("0.20", "0.40", "NaN"), slot - hour to listOf("0.20", "0.40", "0.10"))),
    )

    val median = snapshot.values.getValue(StabilityProbe.PANEL_MEDIAN)
    // Con due voti su tre non c'e' verita' (sarebbe la media di due): come TruthPanel.slotValue.
    assertEquals(setOf(slot - hour), median.keys)
    assertEquals(0.20, median.getValue(slot - hour), 1e-9)
    assertEquals(0.20, snapshot.values.getValue("ma").getValue(slot), 1e-9)
  }

  @Test
  fun `lo slot di 24 ore esatte cade nel gruppo 24-36, non prima`() {
    val harness = FetcherHarness(tmp.root) { FetcherHarness.ok("") }
    val probe = probe(harness, 0L)
    val stamp = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli()
    // La riga delle 12:00 di ieri chiude lo slot (11:00, 12:00]: all'istantanea ha 24 ore esatte.
    val slot = stamp - 24 * hour
    val before = probe.parseSnapshot(stamp, csv(mapOf(slot to listOf("0.00", "0.00", "0.00"))))
    val later = probe.parseSnapshot(stamp + 6 * hour, csv(mapOf(slot to listOf("0.30", "0.30", "0.30"))))

    val lags = probe.observe(before, later).map { it.lagHours }.toSet()
    assertEquals(setOf(24), lags)
    val report = probe.report(later, listOf(before)).lines()
    assertTrue(report.single { it.trim().startsWith("24-36") && "mediana" in it }.contains("100.0%"))
    assertTrue(report.single { it.trim().startsWith("18-24") && "mediana" in it }.contains(" 0 "))
  }

  @Test
  fun `salva solo le 72 ore chiuse e confronta con le istantanee precedenti`() {
    val first = Instant.parse("2026-09-30T10:30:00Z").toEpochMilli()
    val harness = FetcherHarness(tmp.root) { url ->
      val start = java.time.LocalDate.parse(Fixtures.queryParam(url, "start_date")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
      val end = java.time.LocalDate.parse(Fixtures.queryParam(url, "end_date")).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
      // La risposta arriva fino a fine giornata, slot futuri compresi, come l'API vera.
      FetcherHarness.ok(csv((start until end step hour).associateWith { listOf("0.00", "0.00", "0.00") }))
    }

    probe(harness, first).run()

    val saved = File(tmp.root, "stability/2026093010.csv")
    assertTrue(saved.exists())
    val rows = saved.readLines().drop(1)
    // 72 slot chiusi, righe dal 27/09 11:00 al 30/09 10:00: la riga delle 10:00 chiude lo slot
    // (9:00, 10:00], gia' finito alle 10:30; quella delle 11:00 e' ancora una previsione.
    assertEquals(72, rows.size)
    assertEquals(Instant.parse("2026-09-27T11:00:00Z").epochSecond, rows.first().substringBefore(',').toLong())
    assertEquals(Instant.parse("2026-09-30T10:00:00Z").epochSecond, rows.last().substringBefore(',').toLong())
    assertFalse(File(tmp.root, "stability/2026093010.csv.part").exists())
    assertTrue(harness.logLines.any { "nessuna istantanea precedente" in it })

    // Seconda istantanea, sei ore dopo: stavolta c'e' la tabella.
    probe(harness, first + 6 * hour).run()
    assertTrue(File(tmp.root, "stability/2026093016.csv").exists())
    val text = harness.logLines.joinToString("\n")
    assertTrue(text, text.contains("ritardo"))
    assertTrue(text, text.contains("36-72"))
    assertFalse(text.contains("FALLITO"))
  }
}
