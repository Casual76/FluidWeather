package dev.pampa.fluidweather.testbench.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class RunArchiveTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val hour = 3_600_000L

  private fun init(day: String, hourOfDay: Int): Long = Fixtures.epoch(day, hourOfDay) * 1_000L

  @Test
  fun `la corsa scelta e' la piu' recente gia' uscita, bordo compreso`() {
    val inits = longArrayOf(
      init("2026-04-02", 0), init("2026-04-02", 6), init("2026-04-02", 12), init("2026-04-02", 18),
      init("2026-04-03", 0),
    )
    val delay = 4 * hour // ARPEGE, circa

    // Esattamente init + ritardo: la corsa e' appena uscita, vale.
    assertEquals(init("2026-04-02", 0), RunArchive.latestRun(inits, init("2026-04-02", 4), delay))
    // Un millisecondo prima non esiste ancora, e prima della prima corsa non c'e' niente.
    assertNull(RunArchive.latestRun(inits, init("2026-04-02", 4) - 1, delay))
    assertEquals(init("2026-04-02", 6), RunArchive.latestRun(inits, init("2026-04-02", 10), delay))
    assertEquals(init("2026-04-02", 0), RunArchive.latestRun(inits, init("2026-04-02", 10) - 1, delay))
    // Dopo l'ultima emissione si resta sull'ultima.
    assertEquals(init("2026-04-03", 0), RunArchive.latestRun(inits, init("2026-04-09", 0), delay))

    // Un ritardo piu' lungo (UKMO, circa 9 ore) sposta la scelta indietro.
    val ukmo = 9 * hour
    assertEquals(init("2026-04-02", 6), RunArchive.latestRun(inits, init("2026-04-02", 15), ukmo))
    assertEquals(init("2026-04-02", 0), RunArchive.latestRun(inits, init("2026-04-02", 15) - 1, ukmo))
  }

  @Test
  fun `una corsa mancante si salta e si cade su quella prima`() {
    // La corsa delle 06 non e' mai stata scaricata (o l'API non l'aveva).
    val inits = longArrayOf(init("2026-04-02", 0), init("2026-04-02", 12))
    val delay = 4 * hour

    assertEquals(init("2026-04-02", 0), RunArchive.latestRun(inits, init("2026-04-02", 11), delay))
    assertEquals(init("2026-04-02", 0), RunArchive.latestRun(inits, init("2026-04-02", 15), delay))
    assertEquals(init("2026-04-02", 12), RunArchive.latestRun(inits, init("2026-04-02", 16), delay))
    assertNull(RunArchive.latestRun(LongArray(0), init("2026-04-02", 16), delay))
  }

  @Test
  fun `la corsa troppo vecchia puo' essere scartata`() {
    val inits = longArrayOf(init("2026-04-02", 0))
    val delay = 4 * hour
    assertEquals(init("2026-04-02", 0), RunArchive.latestRun(inits, init("2026-04-02", 20), delay, maxAgeMillis = 24 * hour))
    assertNull(RunArchive.latestRun(inits, init("2026-04-03", 1), delay, maxAgeMillis = 24 * hour))
  }

  @Test
  fun `mai una corsa con init piu' ritardo oltre l'istante, e mai una piu' recente saltata`() {
    val random = Random(7)
    val inits = LongArray(40) { init("2026-04-02", 0) + it * 6 * hour }
    repeat(2_000) {
      val t = init("2026-04-01", 0) + (random.nextDouble() * 12 * 24 * hour).toLong()
      val delay = random.nextInt(14) * hour + random.nextInt(3_600_000)
      val chosen = RunArchive.latestRun(inits, t, delay)
      if (chosen != null) assertTrue("init + ritardo oltre t", chosen + delay <= t)
      // Nessuna corsa uscita fra la scelta e t: e' davvero la piu' recente.
      val newerButAvailable = inits.filter { (chosen == null || it > chosen) && it + delay <= t }
      assertTrue(newerButAvailable.isEmpty())
      if (chosen == null) assertTrue(inits.none { it + delay <= t })
    }
  }

  @Test
  fun `l'archivio su disco elenca le corse, le mancanti e legge i valori per localita' e istante`() {
    val root = tmp.root
    fun runFile(month: String, stamp: String) = File(root, "m/$month/$stamp.csv")
    Fixtures.write(runFile("2026-04", "2026040200"), Fixtures.runCsv(2, Fixtures.epoch("2026-04-02", 0), 24))
    Fixtures.write(runFile("2026-04", "2026040212"), Fixtures.runCsv(2, Fixtures.epoch("2026-04-02", 12), 24))
    Fixtures.write(runFile("2026-05", "2026050100"), Fixtures.runCsv(2, Fixtures.epoch("2026-05-01", 0), 24))
    Fixtures.write(runFile("2026-06", "2026060100"), Fixtures.runCsv(2, Fixtures.epoch("2026-06-01", 0), 24))
    // Qualcosa che non e' una corsa non deve rompere l'elenco.
    Fixtures.write(File(root, "m/2026-04/appunti.csv"), "niente")
    Fixtures.write(File(root, "m/missing.txt"), "2026040206\n2026040218\n\n")

    val archive = RunArchive("m", root, TestLocations)

    assertEquals(
      listOf(init("2026-04-02", 0), init("2026-04-02", 12), init("2026-05-01", 0), init("2026-06-01", 0)),
      archive.initTimesMillis.toList(),
    )
    assertEquals(setOf(init("2026-04-02", 6), init("2026-04-02", 18)), archive.missingInitMillis)

    // Con ritardo 4 h: alle 11:00 c'e' solo la corsa delle 00; alle 16:00 quella delle 12.
    assertEquals(init("2026-04-02", 0), archive.latestRunAvailableAt(init("2026-04-02", 11), 4 * hour))
    assertEquals(init("2026-04-02", 12), archive.latestRunAvailableAt(init("2026-04-02", 16), 4 * hour))

    val run = init("2026-04-02", 0)
    val at3 = init("2026-04-02", 3)
    // alfa (id 0): rain = 0 + 3*0.01; beta (id 1): 0.1 + 0.03.
    assertEquals(0.03, archive.valueAt(run, TestLocations[0], at3, "precipitation")!!, 1e-9)
    assertEquals(0.13, archive.valueAt(run, TestLocations[1], at3, "precipitation")!!, 1e-9)
    assertEquals(1000.3, archive.valueAt(run, TestLocations[0], at3, "pressure_msl")!!, 1e-9)
    assertEquals(1001.3, archive.valueAt(run, TestLocations[1], at3, "pressure_msl")!!, 1e-9)
    // L'ora 0 della precipitazione e' nulla; un istante fuori dalle 24 ore non c'e'; una corsa mancante nemmeno.
    assertNull(archive.valueAt(run, TestLocations[0], run, "precipitation"))
    assertNotNull(archive.valueAt(run, TestLocations[0], run, "pressure_msl"))
    assertNull(archive.valueAt(run, TestLocations[0], init("2026-04-03", 6), "precipitation"))
    assertNull(archive.valueAt(init("2026-04-02", 6), TestLocations[0], at3, "precipitation"))

    // Tre mesi in rotazione: il terzo fa uscire il primo dalla memoria, il ritorno lo ricarica uguale.
    val may = init("2026-05-01", 0)
    val june = init("2026-06-01", 0)
    assertEquals(0.05, archive.valueAt(may, TestLocations[0], may + 5 * hour, "precipitation")!!, 1e-9)
    assertEquals(0.05, archive.valueAt(june, TestLocations[0], june + 5 * hour, "precipitation")!!, 1e-9)
    assertEquals(0.03, archive.valueAt(run, TestLocations[0], at3, "precipitation")!!, 1e-9)
  }

  @Test
  fun `location_id si legge solo nell'ordine dichiarato da locations_txt`() {
    val root = tmp.root
    Fixtures.write(File(root, "m/2026-04/2026040200.csv"), Fixtures.runCsv(2, Fixtures.epoch("2026-04-02", 0), 24))
    val run = init("2026-04-02", 0)
    val at3 = init("2026-04-02", 3)

    // L'ordine giusto: si legge.
    Fixtures.write(File(root, SingleRunFetcher.LOCATIONS_FILE), SingleRunFetcher.locationsSidecar(TestLocations))
    assertEquals(0.03, RunArchive("m", root, TestLocations).valueAt(run, TestLocations[0], at3, "precipitation")!!, 1e-9)

    // L'archivio scaricato con beta prima di alfa, letto con alfa prima di beta: ci si ferma.
    Fixtures.write(File(root, SingleRunFetcher.LOCATIONS_FILE), SingleRunFetcher.locationsSidecar(TestLocations.reversed()))
    val failure = runCatching { RunArchive("m", root, TestLocations).valueAt(run, TestLocations[0], at3, "precipitation") }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
  }

  @Test
  fun `una localita' fuori dall'archivio e' un errore e un archivio vuoto non esplode`() {
    val archive = RunArchive("assente", tmp.root, TestLocations)
    assertEquals(0, archive.initTimesMillis.size)
    assertTrue(archive.missingInitMillis.isEmpty())
    assertNull(archive.latestRunAvailableAt(init("2026-04-02", 12), 0))

    val stranger = BenchLocation("altrove", 0.0, 0.0, "fixture")
    val failure = runCatching { archive.valueAt(init("2026-04-02", 0), stranger, init("2026-04-02", 1), "precipitation") }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
  }

  @Test
  fun `il nome di una corsa si legge solo se e' un'ora UTC valida`() {
    assertEquals(init("2026-04-02", 6), RunArchive.parseStamp("2026040206"))
    assertNull(RunArchive.parseStamp("20260402"))
    assertNull(RunArchive.parseStamp("2026040225"))
    assertNull(RunArchive.parseStamp("202613xx06"))
  }
}
