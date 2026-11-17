package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises real provider decoding, fusion, refresher and persisted snapshot boundaries with synthetic input. */
class WeatherRefreshValidityRegressionTest {
  private val time = 1_788_264_000_000L
  private val hour = 3_600_000L
  private val lat = 43.83
  private val lon = 11.2
  private val id = ProviderRegistry.OPEN_METEO
  private val descriptor = ProviderRegistry.all.first { it.id == id }

  private fun bundle(points: List<HourlyPoint>, downloaded: Long = time) = ForecastBundle(id, downloaded, lat, lon, points,
    hourlyPrecipitation = PrecipitationAccumulation(hour, AccumulationAnchor.PRECEDING))

  private inner class Scenario {
    var clock = time
    var response = bundle(listOf(HourlyPoint(time + hour, temperatureC = 20.0)))
    var hooks = 0
    val store = WeatherSnapshotStore(Files.createTempDirectory("weather-validity").toFile())
    private val fusion = ForecastFusion(ProviderScoreboard(InMemoryVerificationStore(), clock = { clock }))
    val source = object : RoundSource {
      override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
        val fetches = listOf(ProviderFetch(descriptor, response, null))
        return WeatherRound(fetches, fusion.fuse(fetches, latitude, longitude, clock))
      }
    }
    val refresher = WeatherSnapshotRefresher(source, store, onGpsRoundRegistered = { hooks++ }, clock = { clock })
    suspend fun refresh() = refresher.refresh(WeatherSnapshot.GPS_KEY, lat, lon)
  }

  @Test
  fun `empty decoded provider payload preserves the last usable snapshot and registration clock`() = runTest {
    val scenario = Scenario()
    val saved = scenario.refresh()
    scenario.clock += hour
    val http = RecordingHttp { "{}" }
    scenario.response = OpenMeteoClient(descriptor, providerHttpOf(http) { scenario.clock }).fetch(lat, lon, null)
    val failed = runCatching { scenario.refresh() }.exceptionOrNull()
    assertTrue("An empty decoded response must be a failed round", failed is EmptyRoundException)
    assertEquals(saved, scenario.store.read(WeatherSnapshot.GPS_KEY))
    assertEquals(1, scenario.hooks)
  }

  @Test
  fun `all null series cannot replace a persisted useful forecast`() = runTest {
    val scenario = Scenario()
    val saved = scenario.refresh()
    scenario.clock += hour
    scenario.response = bundle(listOf(HourlyPoint(scenario.clock + hour)), scenario.clock)
    assertTrue(runCatching { scenario.refresh() }.exceptionOrNull() is EmptyRoundException)
    assertEquals(saved, scenario.store.read(WeatherSnapshot.GPS_KEY))
    assertEquals(1, scenario.hooks)
  }

  @Test
  fun `rows wholly outside current and forecast coverage cannot replace the usable cache`() = runTest {
    val scenario = Scenario()
    val saved = scenario.refresh()
    scenario.clock += hour
    scenario.response = bundle(listOf(HourlyPoint(scenario.clock - 4 * hour, temperatureC = 21.0)), scenario.clock)
    assertTrue(runCatching { scenario.refresh() }.exceptionOrNull() is EmptyRoundException)
    assertEquals(saved, scenario.store.read(WeatherSnapshot.GPS_KEY))
  }

  @Test
  fun `useful provider current and quarters survive even without an hourly product`() = runTest {
    val scenario = Scenario()
    scenario.response = bundle(emptyList()).copy(
      current = ProviderCurrent(time, temperatureC = 23.0, kind = WeatherKind.CLEAR, downloadedAtMillis = time),
      minutely = listOf(MinutePoint(time, 0.0), MinutePoint(time + 900_000L, 0.2)),
      minutelyPrecipitation = PrecipitationAccumulation(900_000L, AccumulationAnchor.PRECEDING))
    val result = scenario.refresh()
    assertEquals(23.0, result.context!!.current!!.temperatureC!!, 0.0)
    assertEquals(scenario.response.minutely, result.context!!.minutely)
    assertEquals(time, result.context!!.fetchedAtMillis)
    assertEquals(result, scenario.store.read(WeatherSnapshot.GPS_KEY))
  }

  @Test
  fun `the first valid response after a rejected empty round replaces the saved forecast`() = runTest {
    val scenario = Scenario()
    scenario.refresh()
    scenario.clock += hour
    scenario.response = bundle(emptyList(), scenario.clock)
    runCatching { scenario.refresh() }
    scenario.clock += 60_000L
    scenario.response = bundle(listOf(HourlyPoint(scenario.clock + hour, temperatureC = 26.0)), scenario.clock)
    val recovered = scenario.refresh()
    assertEquals(26.0, recovered.fused.hours.single().values.getValue(FusionVariables.TEMPERATURE).value, 0.0)
    assertEquals(scenario.clock, recovered.fetchedAtMillis)
    assertEquals(2, scenario.hooks)
  }
}
