package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.model.WeatherKind
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ogni client contro la risposta VERA del suo provider (registrata il 2026-09-01), piu' la
 * fixture documentata per OWM (chiave in attivazione al momento della registrazione). Se un
 * provider cambia formato, e' qui che si rompe qualcosa — non in mano all'utente.
 */
class WeatherClientsTest {

  private class FakeHttp(private val routes: Map<String, String>) : EngineHttp() {
    override suspend fun readText(url: String, headers: Map<String, String>): String =
      routes.entries.firstOrNull { url.contains(it.key) }?.value
        ?: error("nessuna rotta finta per $url")
  }

  private fun fixture(name: String): String =
    javaClass.getResource("/$name")!!.readText()

  private fun http(vararg routes: Pair<String, String>): ProviderHttp {
    val cacheDir = Files.createTempDirectory("cache").toFile()
    return ProviderHttp(FakeHttp(routes.toMap()), UrlCache(cacheDir))
  }

  private fun descriptor(id: String) = ProviderRegistry.all.first { it.id == id }

  @Test
  fun `open-meteo si normalizza dai valori veri`() = runTest {
    val client = OpenMeteoClient(
      descriptor(ProviderRegistry.OPEN_METEO),
      http("api.open-meteo.com" to fixture("openmeteo.json")),
    )
    val bundle = client.fetch(43.83, 11.2, null)

    assertTrue(bundle.hourly.size > 48)
    val first = bundle.hourly.first()
    assertEquals(1_788_220_800_000L, first.timestampMillis)
    assertEquals(1017.9, first.pressureMslHpa!!, 1e-9)
    assertEquals(WeatherKind.PARTLY_CLOUDY, first.kind)
  }

  @Test
  fun `met norway converte il vento e legge la pioggia dell'ora dopo`() = runTest {
    val client = MetNorwayClient(
      descriptor(ProviderRegistry.MET_NORWAY),
      http("api.met.no" to fixture("metno.json")),
    )
    val bundle = client.fetch(43.83, 11.2, null)

    val first = bundle.hourly.first()
    assertEquals(Instant.parse("2026-09-01T19:00:00Z").toEpochMilli(), first.timestampMillis)
    assertEquals(1018.1, first.pressureMslHpa!!, 1e-9)
    assertEquals(25.3, first.temperatureC!!, 1e-9)
    assertEquals(1.5 * 3.6, first.windSpeedKmh!!, 1e-9)
    assertEquals(0.0, first.precipitationMm!!, 1e-9)
    assertEquals(WeatherKind.CLEAR, first.kind)
  }

  @Test
  fun `nws fa due passi e traduce fahrenheit, mph e bussola`() = runTest {
    val client = NwsClient(
      descriptor(ProviderRegistry.NWS),
      http(
        "api.weather.gov/points" to fixture("nws-points.json"),
        "forecast/hourly" to fixture("nws-hourly.json"),
      ),
    )
    val bundle = client.fetch(39.74, -104.98, null)

    val first = bundle.hourly.first()
    assertEquals((84.0 - 32) * 5 / 9, first.temperatureC!!, 1e-6)
    assertEquals(9.444444444444445, first.dewPointC!!, 1e-6)
    assertEquals(30.0, first.relativeHumidityPercent!!, 1e-9)
    assertEquals(12.0, first.precipitationProbabilityPercent!!, 1e-9)
    assertEquals(5.0 * 1.609344, first.windSpeedKmh!!, 1e-6)
    assertEquals(90.0, first.windDirectionDeg!!, 1e-9) // "E"
    assertEquals(WeatherKind.MOSTLY_CLEAR, first.kind)
  }

  @Test
  fun `meteosource ancora le date locali al fuso dichiarato`() = runTest {
    val client = MeteosourceClient(
      descriptor(ProviderRegistry.METEOSOURCE),
      http("meteosource.com" to fixture("meteosource.json")),
    )
    val bundle = client.fetch(43.83, 11.2, "chiave")

    val first = bundle.hourly.first()
    // 21:00 a Roma (CEST) sono le 19:00Z.
    assertEquals(Instant.parse("2026-09-01T19:00:00Z").toEpochMilli(), first.timestampMillis)
    assertEquals(24.2, first.temperatureC!!, 1e-9)
    assertEquals(2.7 * 3.6, first.windSpeedKmh!!, 1e-9)
    assertEquals(12.0, first.cloudCoverPercent!!, 1e-9)
    assertEquals(WeatherKind.MOSTLY_CLEAR, first.kind)
  }

  @Test
  fun `openweathermap legge i passi di tre ore con la chiave`() = runTest {
    val client = OpenWeatherMapClient(
      descriptor(ProviderRegistry.OPENWEATHERMAP),
      http("openweathermap.org" to fixture("owm.json")),
    )
    val bundle = client.fetch(43.83, 11.2, "chiave")

    assertEquals(2, bundle.hourly.size)
    val first = bundle.hourly.first()
    assertEquals(24.5, first.temperatureC!!, 1e-9)
    assertEquals(1018.0, first.pressureMslHpa!!, 1e-9)
    assertEquals(2.5 * 3.6, first.windSpeedKmh!!, 1e-9)
    assertEquals(35.0, first.precipitationProbabilityPercent!!, 1e-9)
    assertEquals(0.6, first.precipitationMm!!, 1e-9)
    assertEquals(WeatherKind.RAIN, first.kind)
    // Il secondo passo non ha pioggia ne' sea_level: ripiega su pressure, e il kind e' CLEAR.
    assertEquals(1019.0, bundle.hourly[1].pressureMslHpa!!, 1e-9)
    assertEquals(WeatherKind.CLEAR, bundle.hourly[1].kind)
  }
}
