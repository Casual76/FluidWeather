package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Uses the existing age/refresher API so the original implementation can demonstrate RED. */
class WeatherSourceAgeRegressionTest {
  private val time = 1_788_264_000_000L
  private val hour = 3_600_000L
  private val lat = 43.83
  private val lon = 11.2

  private fun fetch(id: String, downloaded: Long, temperature: Double = 20.0) = ProviderFetch(
    ProviderRegistry.all.first { it.id == id },
    ForecastBundle(id, downloaded, lat, lon, listOf(HourlyPoint(time + hour, temperatureC = temperature))), null)

  private suspend fun snapshot(fetches: List<ProviderFetch>): WeatherSnapshot {
    val fusion = ForecastFusion(ProviderScoreboard(InMemoryVerificationStore(), clock = { time }))
    return WeatherSnapshot.from("gps", lat, lon, WeatherRound(fetches, fusion.fuse(fetches, lat, lon, time)), time)
  }

  @Test
  fun `a cached forecast does not become newly acquired when a new round is made`() = runTest {
    val snapshot = snapshot(listOf(fetch(ProviderRegistry.OPEN_METEO, time - 25 * 60_000L)))
    assertEquals(time, snapshot.fetchedAtMillis) // the round keeps its identity
    assertEquals(25 * 60_000L, snapshot.ageMillis(time))
  }

  @Test
  fun `actual contributor acquisition age survives snapshot serialization`() = runTest {
    val snapshot = snapshot(listOf(fetch(ProviderRegistry.OPEN_METEO_ICON, time - 25 * 60_000L)))
    val persisted = WeatherSnapshotCodec.decode(WeatherSnapshotCodec.encode(snapshot))!!
    assertEquals(time, persisted.fetchedAtMillis)
    assertEquals(25 * 60_000L, persisted.ageMillis(time))
  }

  @Test
  fun `the oldest contributing source controls conservative fused age`() = runTest {
    val snapshot = snapshot(listOf(fetch(ProviderRegistry.OPEN_METEO, time),
      fetch(ProviderRegistry.OPEN_METEO_ICON, time - 20 * 60_000L)))
    assertEquals(20 * 60_000L, snapshot.ageMillis(time))
  }

  @Test
  fun `legacy contributor with unknown acquisition age is not silently fresh`() {
    val old = WeatherSnapshot("gps", lat, lon, time,
      listOf(ProviderFetchSummary(ProviderRegistry.OPEN_METEO_ICON, 1, null)),
      FusedForecast(listOf(FusedHour(time + hour,
        mapOf(FusionVariables.TEMPERATURE to FusedValue(20.0, listOf(Contribution(ProviderRegistry.OPEN_METEO_ICON, 1.0, 20.0)))), null)), emptyMap()), null)
    assertTrue(old.ageMillis(time) > hour)
  }

  @Test
  fun `a future acquisition timestamp is never accepted as a fresh cached snapshot`() = runTest {
    val snapshot = snapshot(listOf(fetch(ProviderRegistry.OPEN_METEO, time + hour)))
    assertTrue(snapshot.ageMillis(time) > hour)
  }

  @Test
  fun `an old successful provider that did not contribute cannot age a new fused value`() {
    val used = fetch(ProviderRegistry.OPEN_METEO_ICON, time)
    val unused = fetch(ProviderRegistry.OPEN_METEO_ECMWF, time - hour)
    val fused = FusedForecast(listOf(FusedHour(time + hour,
      mapOf(FusionVariables.TEMPERATURE to FusedValue(20.0, listOf(Contribution(used.descriptor.id, 1.0, 20.0)))), null)), emptyMap())
    val snapshot = WeatherSnapshot.from("gps", lat, lon, WeatherRound(listOf(used, unused), fused), time)
    assertEquals(time, snapshot.sourceDataAtMillis)
    assertEquals(0L, snapshot.ageMillis(time))
  }

  @Test
  fun `categorical contributor acquisition and identity survive persistence`() {
    val scalar = fetch(ProviderRegistry.OPEN_METEO_ICON, time)
    val categorical = fetch(ProviderRegistry.MET_NORWAY, time - 20 * 60_000L)
    val fused = FusedForecast(listOf(FusedHour(time + hour,
      mapOf(FusionVariables.TEMPERATURE to FusedValue(20.0, listOf(Contribution(scalar.descriptor.id, 1.0, 20.0)))),
      WeatherKind.RAIN, kindProviderId = categorical.descriptor.id,
      precipitationInterval = WeatherInterval(time, time + hour))), emptyMap())
    val snapshot = WeatherSnapshot.from("gps", lat, lon, WeatherRound(listOf(scalar, categorical), fused), time)
    val decoded = WeatherSnapshotCodec.decode(WeatherSnapshotCodec.encode(snapshot))!!
    assertEquals(categorical.descriptor.id, decoded.fused.hours.single().kindProviderId)
    assertEquals(WeatherInterval(time, time + hour), decoded.fused.hours.single().precipitationInterval)
    assertEquals(20 * 60_000L, decoded.ageMillis(time))
  }
}
