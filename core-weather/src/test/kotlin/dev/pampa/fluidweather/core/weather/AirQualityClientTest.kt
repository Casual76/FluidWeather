package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirQualityClientTest {

  private class FakeHttp(private val body: String) : EngineHttp() {
    override suspend fun readText(url: String, headers: Map<String, String>): String = body
  }

  @Test
  fun `la risposta vera di Open-Meteo diventa indice, inquinanti e previsione`() = runTest {
    val fixture = javaClass.getResource("/openmeteo-air.json")!!.readText()
    val root = Json.parseToJsonElement(fixture)
    val firstHourSeconds = root["hourly"]["time"].at(0).double()!!.toLong()
    val clock = { (firstHourSeconds + 2 * 3_600) * 1_000 }
    val client = AirQualityClient(ProviderHttp(FakeHttp(fixture), UrlCache(Files.createTempDirectory("cache").toFile())), clock)

    val air = client.now(43.83, 11.2)
    assertNotNull(air)
    assertEquals(root["current"]["european_aqi"].double()!!.toInt(), air!!.europeanAqi)
    // Tanti inquinanti quanti il servizio ne ha davvero riportati (un null nel fixture non e' uno zero).
    val reported = listOf("pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide")
      .count { root["current"][it].double() != null }
    assertEquals(reported, air.pollutants.size)
    assertTrue(air.pollutants.size >= 4)
    assertTrue(air.pollutants.all { it.subIndex >= 0.0 })
    val worst = air.pollutants.maxByOrNull { it.subIndex }!!
    assertEquals(worst.name, air.dominantPollutant)
    assertEquals(worst.valueUgm3, air.dominantValue!!, 1e-9)
    assertTrue(air.forecast.isNotEmpty())
    assertTrue(air.forecast.all { it.timestampMillis >= clock() - 3_600_000L })
    assertTrue(air.forecast.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis })
  }
}
