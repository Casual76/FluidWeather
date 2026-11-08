package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.model.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderCurrentContractTest {
  private val time = Instant.parse("2026-09-02T00:00:00Z").toEpochMilli()
  private fun descriptor(id: String) = ProviderRegistry.all.first { it.id == id }
  private fun http(body: String) = ProviderHttp(object : EngineHttp() {
    override suspend fun readText(url: String, headers: Map<String, String>) = body
  }, UrlCache(Files.createTempDirectory("current-contract").toFile()) { time })

  @Test fun `current seconds become preceding milliseconds and cache preserves original download`() = runTest {
    var clock = time
    var calls = 0
    val engine = object : EngineHttp() {
      override suspend fun readText(url: String, headers: Map<String, String>): String {
        calls++
        assertTrue(url.contains("&current=temperature_2m,weather_code,precipitation"))
        return """{"timezone":"Europe/Rome","utc_offset_seconds":7200,
          "current":{"time":${time / 1000},"interval":900,"temperature_2m":20.5,"weather_code":61,"precipitation":0.4}}"""
      }
    }
    val client = OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO), ProviderHttp(engine,
      UrlCache(Files.createTempDirectory("current-download").toFile()) { clock }) { clock })
    val first = client.fetch(43.0, 11.0, null)
    clock += 60_000
    val second = client.fetch(43.0, 11.0, null)
    val current = second.current!!
    assertEquals(1, calls)
    assertEquals(first.current, current)
    assertEquals(time, current.timestampMillis)
    assertEquals(time, current.downloadedAtMillis)
    assertEquals(900_000L, current.accumulationIntervalMillis)
    assertEquals(WeatherInterval(time - 900_000, time), current.precipitationInterval)
    assertEquals(WeatherEvidenceType.MODEL, current.evidenceType)
    assertEquals(WeatherKind.RAIN, current.kind)
    assertEquals(20.5, current.temperatureC!!, 0.0)
    assertEquals(true, second.minutelyPossiblyInterpolated)
  }

  @Test fun `missing current and invalid values stay unknown`() = runTest {
    suspend fun parse(current: String) = OpenMeteoClient(descriptor(ProviderRegistry.OPEN_METEO),
      http("""{"current":$current}""")).fetch(43.0, 11.0, null).current
    assertNull(parse("null"))
    assertNull(parse("""{"interval":900,"precipitation":0}"""))
    assertNull(parse("""{"time":${time / 1000},"precipitation":-1,"temperature_2m":null}"""))
    val partial = parse("""{"time":${time / 1000},"interval":-5,"temperature_2m":20}""")!!
    assertNull(partial.precipitationMm)
    assertNull(partial.accumulationIntervalMillis)
    assertNull(partial.precipitationInterval)
    val halfHour = parse("""{"time":${time / 1000},"interval":1800,"precipitation":0.4}""")!!
    assertEquals(WeatherInterval(time - 1_800_000, time), halfHour.precipitationInterval)
    assertNull(parse("""{"time":${time / 1000},"precipitation":"NaN","temperature_2m":"Infinity"}"""))
  }

  private fun snapshot(bundle: ForecastBundle) = WeatherSnapshot("gps", 43.0, 11.0, time + 60_000,
    emptyList(), FusedForecast(emptyList(), emptyMap()), bundle, contextCarriedFromMillis = time - 60_000)

  @Test fun `codec v1 roundtrips current metadata and original timestamps and reads legacy unknowns`() {
    val bundle = ForecastBundle("open-meteo", time - 60_000, 43.0, 11.0, emptyList(),
      current = ProviderCurrent(time, 20.0, WeatherKind.CLEAR, 0.0, 900_000, time - 60_000, true),
      hourlyPrecipitation = PrecipitationAccumulation(3_600_000, AccumulationAnchor.PRECEDING),
      minutelyPrecipitation = PrecipitationAccumulation(900_000, AccumulationAnchor.PRECEDING),
      minutelyPossiblyInterpolated = true)
    val original = snapshot(bundle)
    val text = WeatherSnapshotCodec.encode(original)
    assertEquals(original, WeatherSnapshotCodec.decode(text))
    val root = Json.parseToJsonElement(text).jsonObject
    assertEquals(1, root.getValue("version").jsonPrimitive.int)
    val legacyContext = JsonObject(root.getValue("context").jsonObject - setOf("current",
      "hourlyPrecipitation", "minutelyPrecipitation", "minutelyPossiblyInterpolated"))
    val decoded = WeatherSnapshotCodec.decode(JsonObject(root + ("context" to legacyContext)).toString())!!
    assertNull(decoded.context!!.current)
    assertNull(decoded.context!!.hourlyPrecipitation)
    assertNull(decoded.context!!.minutelyPossiblyInterpolated)
    assertEquals(time - 60_000, decoded.context!!.fetchedAtMillis)
    assertEquals(original.contextCarriedFromMillis, decoded.contextCarriedFromMillis)
  }

  @Test fun `fallback skips null best match and null first variant`() {
    val descriptors = ProviderRegistry.all.filter { it.id.startsWith(ProviderRegistry.OPEN_METEO) }
    assertTrue(descriptors.size >= 3)
    val valid = ForecastBundle(descriptors[2].id, time, 43.0, 11.0, emptyList())
    val round = WeatherRound(listOf(ProviderFetch(descriptors[0], null, "offline"),
      ProviderFetch(descriptors[1], null, "offline"), ProviderFetch(descriptors[2], valid, null)),
      FusedForecast(emptyList(), emptyMap()))
    assertEquals(valid, WeatherSnapshot.from("gps", 43.0, 11.0, round, time).context)
  }

  @Test fun `OWM volume retains preceding three hours without division or current inference`() = runTest {
    val body = """{"list":[{"dt":${time / 1000},"rain":{"3h":6.0}}]}"""
    val b = OpenWeatherMapClient(descriptor(ProviderRegistry.OPENWEATHERMAP), http(body)).fetch(43.0, 11.0, "fixture")
    assertEquals(6.0, b.hourly.single().precipitationMm!!, 0.0)
    assertEquals(PrecipitationAccumulation(10_800_000, AccumulationAnchor.PRECEDING), b.hourlyPrecipitation)
    assertNull(b.current)
    assertNull(b.rainNowMm(time))
  }

  @Test fun `Meteosource missing invalid timezone and DST ambiguous dates do not become UTC`() = runTest {
    for (zone in listOf("null", "\"invalid\"", "\"Europe/Rome\"")) {
      val b = MeteosourceClient(descriptor(ProviderRegistry.METEOSOURCE), http(
        """{"timezone":$zone,"hourly":{"data":[{"date":"2026-10-25T02:30:00","precipitation":{"total":1}}]}}"""
      )).fetch(43.0, 11.0, "fixture")
      assertTrue(b.hourly.isEmpty())
      assertEquals(PrecipitationAccumulation(), b.hourlyPrecipitation)
    }
  }
}
