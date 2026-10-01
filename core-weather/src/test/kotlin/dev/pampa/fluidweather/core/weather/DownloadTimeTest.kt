package dev.pampa.fluidweather.core.weather

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'eta' vera di un dato: un colpo di cache non deve ringiovanirlo.
 *
 * Il difetto chiuso qui: il client metteva `fetchedAtMillis = adesso` anche quando il JSON veniva
 * dalla cache di mezz'ora, quindi un contesto di venticinque minuti fa sembrava appena scaricato.
 * I valori sono a secondi interi perche' alcuni filesystem tengono la data dei file al secondo.
 */
class DownloadTimeTest {

  private val t0 = 1_788_220_800_000L

  @Test
  fun `la cache restituisce il timbro con cui ha scritto`() {
    var now = t0
    val cache = UrlCache(Files.createTempDirectory("cache").toFile()) { now }

    val stamp = cache.write("https://esempio/api", "corpo")
    now += 5 * 60_000L
    val cached = cache.readTimed("https://esempio/api", maxAgeMillis = 30 * 60_000L)!!

    assertEquals(t0, stamp)
    assertEquals("corpo", cached.text)
    assertEquals(t0, cached.writtenAtMillis)
  }

  @Test
  fun `un colpo di cache dieci minuti dopo porta l'ora del primo download`() = runTest {
    var now = t0
    var calls = 0
    val fixture = javaClass.getResource("/openmeteo.json")!!.readText()
    val http = RecordingHttp { calls++; fixture }
    val client = OpenMeteoClient(
      ProviderRegistry.all.first { it.id == ProviderRegistry.OPEN_METEO },
      providerHttpOf(http) { now },
    )

    val first = client.fetch(43.83, 11.2, null)
    now += 10 * 60_000L
    val second = client.fetch(43.83, 11.2, null)

    assertEquals("la seconda doveva venire dalla cache", 1, calls)
    assertEquals(t0, first.fetchedAtMillis)
    assertEquals("un dato di dieci minuti fa non e' di adesso", t0, second.fetchedAtMillis)
  }

  @Test
  fun `senza cache si va in rete, si timbra adesso e non si scrive niente`() = runTest {
    val directory = Files.createTempDirectory("cache").toFile()
    var now = t0
    val http = ProviderHttp(RecordingHttp { "{}" }, UrlCache(directory) { now }) { now }

    val first = http.readTextTimed("https://esempio/verita", maxAgeMillis = 0L, useCache = false)
    now += 60_000L
    val second = http.readTextTimed("https://esempio/verita", maxAgeMillis = 60 * 60_000L, useCache = false)

    assertEquals(t0, first.downloadedAtMillis)
    assertEquals(t0 + 60_000L, second.downloadedAtMillis)
    assertTrue("la verita' non deve finire in cache", directory.listFiles().isNullOrEmpty())
  }
}
