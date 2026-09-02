package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarClientsTest {

  private class FakeHttp(private val body: String) : EngineHttp() {
    override suspend fun readText(url: String, headers: Map<String, String>): String = body
  }

  private fun http(body: String) = ProviderHttp(FakeHttp(body), UrlCache(Files.createTempDirectory("cache").toFile()))

  @Test
  fun `il JSON vero di RainViewer diventa fotogrammi e URL dei tile`() = runTest {
    val fixture = javaClass.getResource("/rainviewer.json")!!.readText()
    val frames = RainViewerClient(http(fixture)).frames()
    assertNotNull(frames)
    assertEquals("https://tilecache.rainviewer.com", frames!!.host)
    assertEquals(13, frames.past.size)
    assertTrue(frames.past.zipWithNext().all { (a, b) -> b.timeMillis - a.timeMillis == 10 * 60_000L })
    assertEquals(frames.past.size - 1, frames.nowIndex)
    val last = frames.past.last()
    assertEquals(
      "https://tilecache.rainviewer.com${last.path}/256/6/33/23/2/1_1.png",
      frames.tileUrl(last, zoom = 6, x = 33, y = 23),
    )
    assertEquals(
      "https://tilecache.rainviewer.com${last.path}/512/7/1/2/8/0_0.png",
      frames.tileUrl(last, 7, 1, 2, colorScheme = 8, smooth = false, snow = false, size = 512),
    )
  }

  @Test
  fun `senza fotogrammi passati non c'e' radar`() = runTest {
    assertNull(RainViewerClient(http("""{"version":"2.0","host":"https://x","radar":{"past":[],"nowcast":[]}}""")).frames())
    assertNull(RainViewerClient(http("non json")).frames())
  }

  @Test
  fun `la legenda segue la tabella ufficiale, dal debole al forte`() {
    val legend = RainViewerPalette.legend
    assertTrue(legend.size >= 5)
    assertTrue(legend.zipWithNext().all { (a, b) -> b.dbz > a.dbz })
    assertEquals("debole", legend.first { it.dbz == 20 }.label)
    assertEquals(0xFF00A3E0L, legend.first { it.dbz == 20 }.argb)
  }

  @Test
  fun `tre punti in una chiamata, nello stesso ordine`() = runTest {
    val fixture = javaClass.getResource("/openmeteo-multi.json")!!.readText()
    val points = PointWeatherClient(http(fixture)).current(listOf(43.8 to 11.2, 44.0 to 11.4, 45.5 to 9.2))
    assertEquals(3, points.size)
    assertEquals(43.8, points[0].latitude, 0.01)
    assertEquals(9.2, points[2].longitude, 0.01)
    assertTrue(points.all { it.temperatureC != null })
    assertTrue(points.all { it.kind != null })
    assertTrue(PointWeatherClient(http(fixture)).current(emptyList()).isEmpty())
  }
}
