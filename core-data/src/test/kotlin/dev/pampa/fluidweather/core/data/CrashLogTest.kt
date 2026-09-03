package dev.pampa.fluidweather.core.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Il quaderno degli errori e, soprattutto, l'avviso che ne parla una volta sola.
 *
 * Il baco che questi test tengono chiuso (visto sul telefono il 3 settembre 2026): il quaderno
 * resta pieno finche' non lo si svuota — e deve, la traccia si copia a mente fredda — ma l'avviso
 * all'apertura guardava solo "c'e' qualcosa in cima". Cosi' una caduta di giorni prima, gia'
 * corretta, si ripresentava ogni volta che il sistema chiudeva il processo e si riapriva l'app.
 */
class CrashLogTest {

  private lateinit var dir: File
  private lateinit var file: File
  private var now = 1_000_000L

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("crashlog").toFile()
    file = File(dir, "crashes.txt")
    now = 1_000_000L
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  /** Un avvio dell'app: legge il quaderno da disco, come fa `install`. */
  private fun avvio() = CrashLog(file, clock = { now }).apply { load() }

  private fun CrashLog.cade(messaggio: String) {
    now += 1_000L
    record(IllegalStateException(messaggio), label = "prova")
  }

  @Test
  fun `una caduta si annuncia una volta`() {
    val log = avvio()
    log.cade("boom")

    val primo = log.unannounced()
    assertNotNull(primo)
    log.markAnnounced(primo!!.atMillis)

    assertNull("annunciata due volte", log.unannounced())
  }

  @Test
  fun `il segno sopravvive al processo`() {
    // Il punto di tutto: il quaderno si rilegge, ma l'avviso non si ripete.
    val primo = avvio()
    primo.cade("boom")
    primo.markAnnounced(primo.unannounced()!!.atMillis)

    val secondoAvvio = avvio()

    assertEquals(1, secondoAvvio.records.value.size)
    assertNull("riannunciata al riavvio", secondoAvvio.unannounced())
  }

  @Test
  fun `una caduta nuova si annuncia anche se la vecchia era annunciata`() {
    val log = avvio()
    log.cade("vecchia")
    log.markAnnounced(log.unannounced()!!.atMillis)

    log.cade("nuova")

    val nuova = log.unannounced()
    assertNotNull("la caduta nuova e' stata zittita dal segno", nuova)
    assertTrue(nuova!!.summary.contains("nuova"))
  }

  @Test
  fun `svuotare il quaderno porta via anche il segno`() {
    val log = avvio()
    log.cade("boom")
    log.markAnnounced(log.unannounced()!!.atMillis)

    log.clear()

    assertTrue(log.records.value.isEmpty())
    assertNull(log.unannounced())
    assertFalse("il file del segno e' rimasto", File(dir, "crashes.txt.announced").exists())
  }

  @Test
  fun `senza cadute non c'e' niente da annunciare`() {
    assertNull(avvio().unannounced())
  }

  @Test
  fun `il quaderno tiene al massimo cinque tracce, le piu' recenti`() {
    val log = avvio()
    repeat(CrashLog.MAX_RECORDS + 3) { i -> log.cade("caduta $i") }

    val records = log.records.value

    assertEquals(CrashLog.MAX_RECORDS, records.size)
    assertTrue(records.first().summary.contains("caduta ${CrashLog.MAX_RECORDS + 2}"))
  }

  @Test
  fun `le tracce si rileggono dal file, traccia compresa`() {
    avvio().cade("una riga e poi un'altra")

    val riletto = avvio().records.value

    assertEquals(1, riletto.size)
    assertEquals("prova", riletto.first().label)
    assertTrue(riletto.first().stackTrace.contains("IllegalStateException"))
    assertTrue(riletto.first().summary.contains("una riga e poi un'altra"))
  }
}
