package dev.pampa.fluidweather.core.weather.radar

import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.weather.WeatherWipeEpoch
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RadarTileWipeTest {
  private class Http : EngineHttp() {
    var calls = 0
    var before: suspend () -> Unit = {}
    override suspend fun download(url: String, target: File, expectedBytes: Long, headers: Map<String, String>,
      onProgress: (Float, Long, Long) -> Unit): File {
      calls++; before(); target.writeBytes(byteArrayOf(1)); return target
    }
  }
  private val decoder = TileDecoder { Raster(2, 2, IntArray(4)) }
  private fun empty(dir: File) = !dir.exists() || dir.listFiles()!!.isEmpty()

  @Test(timeout = 10_000) fun `late noncooperative downloads cannot recreate tiles or stamps after clear or wipe`() = runBlocking {
    for (shared in listOf(false, true)) {
      val root = Files.createTempDirectory("radar-wipe").toFile()
      try {
        val dir = File(root, "tiles"); val guard = DataWipeGuard(); val http = Http(); var now = 1_800_000_000_000L
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        http.before = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        val store = RadarTileStore(dir, http, decoder, { now }, guard)
        val old = async { store.raster("https://invalid/tile") }
        entered.await()
        if (shared) guard.wipe { store.clear() } else store.clear()
        release.complete(Unit)
        try { old.await(); fail("pre-clear raster returned") } catch (_: CancellationException) { }
        assertTrue(empty(dir))
        assertFalse(root.listFiles()!!.any { it.name.startsWith("fluid-radar-") })
        http.before = {}; now += 5_000L
        assertEquals(now, (store.raster("https://invalid/tile") as TileResult.Ok).downloadedAtMillis)
        now += 90_000L
        assertEquals(now - 90_000L, (store.raster("https://invalid/tile") as TileResult.Ok).downloadedAtMillis)
        assertEquals(2, http.calls)
      } finally { root.deleteRecursively() }
    }
  }

  @Test(timeout = 10_000) fun `late unavailable response cannot recreate empty coverage markers`() = runBlocking {
    for (message in listOf("HTTP 404", "network failed")) {
      val root = Files.createTempDirectory("radar-marker-wipe").toFile()
      try {
        val dir = File(root, "tiles"); val guard = DataWipeGuard(); val http = Http()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        http.before = { withContext(NonCancellable) { entered.complete(Unit); release.await() }; error(message) }
        val store = RadarTileStore(dir, http, decoder, wipeGuard = guard)
        val old = async { store.raster("https://invalid/v2/coverage/mask") }
        entered.await(); guard.wipe { store.clear() }; release.complete(Unit)
        try { old.await(); fail("pre-wipe failure published") } catch (_: CancellationException) { }
        assertTrue(empty(dir))
        assertFalse(root.listFiles()!!.any { it.name.startsWith("fluid-radar-") })
      } finally { root.deleteRecursively() }
    }
  }

  @Test(timeout = 10_000) fun `cached decoder completing after wipe cannot return stale raster`() = runBlocking {
    val root = Files.createTempDirectory("radar-read-wipe").toFile()
    val unblock = CountDownLatch(1)
    try {
      val dir = File(root, "tiles"); val guard = DataWipeGuard(); val http = Http()
      val block = AtomicBoolean(false); val entered = CompletableDeferred<Unit>()
      val decoder = TileDecoder {
        if (block.getAndSet(false)) { entered.complete(Unit); unblock.await() }
        Raster(2, 2, IntArray(4))
      }
      val store = RadarTileStore(dir, http, decoder, wipeGuard = guard)
      store.raster("https://invalid/tile"); block.set(true)
      val old = async { store.raster("https://invalid/tile") }
      entered.await(); guard.wipe { store.clear() }; unblock.countDown()
      try { old.await(); fail("cached pre-wipe raster returned") } catch (_: CancellationException) { }
      assertTrue(empty(dir)); assertEquals(1, http.calls)
    } finally { unblock.countDown(); root.deleteRecursively() }
  }

  @Test(timeout = 10_000) fun `inherited old weather epoch is rejected before a new tile request`() = runBlocking {
    val root = Files.createTempDirectory("radar-origin").toFile()
    try {
      val guard = DataWipeGuard(); val origin = WeatherWipeEpoch(guard, guard.generation()); val http = Http()
      val store = RadarTileStore(File(root, "tiles"), http, decoder, wipeGuard = guard)
      guard.wipe { store.clear() }
      try { withContext(origin) { store.raster("https://invalid/tile") }; fail("epoch renewed") }
      catch (_: CancellationException) { }
      assertEquals(0, http.calls)
    } finally { root.deleteRecursively() }
  }
}
