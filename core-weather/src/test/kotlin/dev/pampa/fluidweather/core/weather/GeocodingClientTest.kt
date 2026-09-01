package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodingClientTest {

  private class FakeHttp(private val body: String) : EngineHttp() {
    override suspend fun readText(url: String, headers: Map<String, String>): String = body
  }

  private fun client(body: String): GeocodingClient {
    val cacheDir = Files.createTempDirectory("cache").toFile()
    return GeocodingClient(ProviderHttp(FakeHttp(body), UrlCache(cacheDir)))
  }

  @Test
  fun `la risposta vera del geocoder diventa un Place completo`() = runTest {
    val fixture = javaClass.getResource("/geocoding.json")!!.readText()
    val results = client(fixture).search("Sesto Fiorentino")

    assertEquals(1, results.size)
    val place = results.single()
    assertEquals(3166601L, place.id)
    assertEquals("Sesto Fiorentino", place.name)
    assertEquals("Toscana, Italia", place.region)
    assertEquals(43.83193, place.latitude, 1e-6)
    assertEquals(11.19924, place.longitude, 1e-6)
  }

  @Test
  fun `query vuota, nessuna chiamata e nessun risultato`() = runTest {
    assertTrue(client("{}").search("  ").isEmpty())
  }

  @Test
  fun `una risposta senza risultati non e' un errore`() = runTest {
    assertTrue(client("""{"generationtime_ms":0.1}""").search("Xyzzy").isEmpty())
  }
}
