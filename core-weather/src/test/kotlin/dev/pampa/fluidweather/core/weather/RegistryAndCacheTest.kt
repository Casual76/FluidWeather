package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryAndCacheTest {

  @Test
  fun `a Sesto Fiorentino rispondono i mondiali e AROME, non JMA ne' NWS`() {
    val ids = ProviderRegistry.available(43.83, 11.2, emptyMap()).map { it.id }

    assertTrue(ProviderRegistry.OPEN_METEO in ids)
    assertTrue(ProviderRegistry.OPEN_METEO_AROME in ids)
    assertTrue(ProviderRegistry.MET_NORWAY in ids)
    assertTrue(ProviderRegistry.OPEN_METEO_JMA !in ids)
    assertTrue(ProviderRegistry.NWS !in ids)
  }

  @Test
  fun `a Denver entra NWS, a Tokyo entra JMA`() {
    assertTrue(
      ProviderRegistry.available(39.74, -104.98, emptyMap()).any { it.id == ProviderRegistry.NWS },
    )
    assertTrue(
      ProviderRegistry.available(35.68, 139.69, emptyMap()).any { it.id == ProviderRegistry.OPEN_METEO_JMA },
    )
  }

  @Test
  fun `senza chiave i provider a chiave restano fuori, con la chiave entrano`() {
    val without = ProviderRegistry.available(43.83, 11.2, emptyMap()).map { it.id }
    assertTrue(ProviderRegistry.OPENWEATHERMAP !in without)
    assertTrue(ProviderRegistry.METEOSOURCE !in without)

    val keys = mapOf(ProviderRegistry.OPENWEATHERMAP to "k1", ProviderRegistry.METEOSOURCE to "k2")
    val with = ProviderRegistry.available(43.83, 11.2, keys).map { it.id }
    assertTrue(ProviderRegistry.OPENWEATHERMAP in with)
    assertTrue(ProviderRegistry.METEOSOURCE in with)
  }

  @Test
  fun `ogni provider dichiara la sua attribuzione`() {
    assertTrue(ProviderRegistry.all.all { it.attribution.isNotBlank() })
  }

  @Test
  fun `la cache serve entro il TTL e scade dopo`() {
    val dir = Files.createTempDirectory("cache").toFile()
    var now = 1_000_000L
    val cache = UrlCache(dir) { now }

    cache.write("https://esempio/api", "corpo")
    assertEquals("corpo", cache.read("https://esempio/api", maxAgeMillis = 60_000))

    now += 61_000
    assertNull(cache.read("https://esempio/api", maxAgeMillis = 60_000))
    // Ma con un TTL piu' generoso il file c'e' ancora.
    assertEquals("corpo", cache.read("https://esempio/api", maxAgeMillis = 120_000))
  }

  @Test
  fun `url diversi non si pestano i piedi`() {
    val dir = Files.createTempDirectory("cache").toFile()
    val cache = UrlCache(dir) { 0L }
    cache.write("https://a", "primo")
    cache.write("https://b", "secondo")
    assertEquals("primo", cache.read("https://a", 1_000))
    assertEquals("secondo", cache.read("https://b", 1_000))
  }

  @Test
  fun `il contesto dal bundle - adesso, tre ore fa, pioggia recente`() {
    val base = 1_700_000_000_000L
    val bundle = ForecastBundle(
      providerId = "test",
      fetchedAtMillis = base,
      latitude = 43.83,
      longitude = 11.2,
      hourly = (-6..6).map { h ->
        HourlyPoint(
          timestampMillis = base + h * 3_600_000L,
          temperatureC = 20.0,
          dewPointC = 15.0,
          relativeHumidityPercent = 80.0,
          cloudCoverPercent = 50.0,
          windSpeedKmh = 10.0,
          windDirectionDeg = if (h <= -3) 180.0 else 200.0,
          precipitationMm = if (h in -2..0) 0.5 else 0.0,
        )
      },
    )
    val context = bundle.toContext(base)!!

    assertEquals(5.0, context.dewPointSpreadC!!, 1e-9)
    assertEquals(200.0, context.windDirectionDeg!!, 1e-9)
    assertEquals(180.0, context.windDirectionDeg3hAgo!!, 1e-9)
    assertEquals(0.5, context.rainLastHourMm!!, 1e-9)
    assertEquals(1.5, context.rainLast3hMm!!, 1e-9)
  }
}
