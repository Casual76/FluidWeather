package dev.pampa.fluidweather.core.weather.radar

import dev.antigravity.fluidengine.net.EngineHttp
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RadarTileStoreTest {
  private class Http : EngineHttp() {
    var calls = 0
    var cancelled = false
    override suspend fun download(url: String, target: File, expectedBytes: Long, headers: Map<String, String>, onProgress: (Float, Long, Long) -> Unit): File {
      calls++
      if (cancelled) throw CancellationException("download")
      target.writeBytes(byteArrayOf(1))
      return target
    }
  }
  private fun name(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
    .joinToString("") { "%02x".format(it) }.take(32)
  private val decoder = TileDecoder { Raster(256, 256, IntArray(256 * 256)) }

  @Test fun `cached tiles preserve original download while legacy files stay unknown`() = runBlocking {
    val directory = Files.createTempDirectory("radar-store").toFile()
    val http = Http()
    var now = 1_700_000_000_000L
    val store = RadarTileStore(directory, http, decoder, { now })
    val url = "https://invalid/radar.png"
    assertEquals(now, (store.raster(url) as TileResult.Ok).downloadedAtMillis)
    now += 90_000L
    assertEquals(now - 90_000L, (store.raster(url) as TileResult.Ok).downloadedAtMillis)
    assertEquals(1, http.calls)
    val oldUrl = "https://invalid/legacy.png"
    val legacy = File(directory, "${name(oldUrl)}.png").apply { writeBytes(byteArrayOf(1)); setLastModified(1_600_000_000_000L) }
    val oldModified = legacy.lastModified()
    assertNull((store.raster(oldUrl) as TileResult.Ok).downloadedAtMillis)
    assertEquals(oldModified, legacy.lastModified())
    assertFalse(File(directory, "${name(oldUrl)}.downloaded").exists())
  }

  @Test fun `coverage mask cached across samples and refreshed after a day`() = runBlocking {
    val directory = Files.createTempDirectory("radar-mask").toFile()
    val http = Http()
    var now = System.currentTimeMillis()
    val store = RadarTileStore(directory, http, decoder, { now })
    val url = "https://invalid/v2/coverage/0/256/7/1/2/0/0_0.png"
    store.raster(url); store.raster(url)
    assertEquals(1, http.calls)
    now += 86_400_001L
    assertEquals(now, (store.raster(url) as TileResult.Ok).downloadedAtMillis)
    assertEquals(2, http.calls)
  }

  @Test fun `download cancellation is not missing and creates no metadata`() = runBlocking {
    val directory = Files.createTempDirectory("radar-cancel").toFile()
    val http = Http().apply { cancelled = true }
    try { RadarTileStore(directory, http, decoder).raster("https://invalid/cancel"); fail("swallowed") }
    catch (_: CancellationException) { }
    assertTrue(directory.listFiles()!!.isEmpty())
  }
}
