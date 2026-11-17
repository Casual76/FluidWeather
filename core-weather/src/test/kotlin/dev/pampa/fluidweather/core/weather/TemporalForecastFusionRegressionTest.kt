package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Prepared against the production public boundary; fixtures are explicitly synthetic. */
class TemporalForecastFusionRegressionTest {
  private val time = 1_788_264_000_000L
  private val hour = 3_600_000L
  private val lat = 43.83
  private val lon = 11.2
  private val precedingHour = PrecipitationAccumulation(hour, AccumulationAnchor.PRECEDING)

  private fun fetch(id: String, points: List<HourlyPoint>, metadata: PrecipitationAccumulation?) = ProviderFetch(
    ProviderRegistry.all.first { it.id == id },
    ForecastBundle(id, time, lat, lon, points, hourlyPrecipitation = metadata), null,
  )

  private suspend fun fuse(fetches: List<ProviderFetch>) =
    ForecastFusion(ProviderScoreboard(InMemoryVerificationStore(), clock = { time })).fuse(fetches, lat, lon, time)

  @Test
  fun `following hourly rain contributes only at its actual interval end while its temperature stays at source time`() = runTest {
    val model = fetch(ProviderRegistry.OPEN_METEO,
      (1..3).map { HourlyPoint(time + it * hour, temperatureC = 20.0, precipitationMm = 0.0) }, precedingHour)
    val following = fetch(ProviderRegistry.MET_NORWAY,
      listOf(HourlyPoint(time + hour, temperatureC = 25.0, precipitationMm = 4.0)),
      PrecipitationAccumulation(hour, AccumulationAnchor.FOLLOWING))
    val fused = fuse(listOf(model, following))
    val beforeEnd = fused.hours.single { it.timestampMillis == time + hour }
    val atEnd = fused.hours.single { it.timestampMillis == time + 2 * hour }
    assertEquals(0.0, beforeEnd.values.getValue(FusionVariables.PRECIPITATION).value, 0.0)
    assertEquals(setOf(ProviderRegistry.OPEN_METEO), beforeEnd.values.getValue(FusionVariables.PRECIPITATION).contributions.map { it.providerId }.toSet())
    assertTrue(atEnd.values.getValue(FusionVariables.PRECIPITATION).value > 0.0)
    assertEquals(WeatherInterval(time + hour, time + 2 * hour), atEnd.precipitationInterval)
    assertEquals(4.0, atEnd.values.getValue(FusionVariables.PRECIPITATION).contributions.single { it.providerId == ProviderRegistry.MET_NORWAY }.value, 0.0)
    assertTrue(beforeEnd.values.getValue(FusionVariables.TEMPERATURE).contributions.any { it.providerId == ProviderRegistry.MET_NORWAY && it.value == 25.0 })
  }

  @Test
  fun `three hour rain is preserved raw but never allocated or repeated into one hour fusion`() = runTest {
    val model = fetch(ProviderRegistry.OPEN_METEO,
      (1..3).map { HourlyPoint(time + it * hour, temperatureC = 20.0, precipitationMm = 1.0) }, precedingHour)
    val coarse = fetch(ProviderRegistry.OPENWEATHERMAP,
      listOf(HourlyPoint(time + 3 * hour, temperatureC = 28.0, precipitationMm = 9.0)),
      PrecipitationAccumulation(3 * hour, AccumulationAnchor.PRECEDING))
    val fused = fuse(listOf(model, coarse))
    for (step in 1..3) {
      val rain = fused.hours.single { it.timestampMillis == time + step * hour }.values.getValue(FusionVariables.PRECIPITATION)
      assertEquals(1.0, rain.value, 0.0)
      assertEquals(listOf(ProviderRegistry.OPEN_METEO), rain.contributions.map { it.providerId })
    }
    val end = fused.hours.single { it.timestampMillis == time + 3 * hour }
    assertTrue(end.values.getValue(FusionVariables.TEMPERATURE).contributions.any { it.providerId == ProviderRegistry.OPENWEATHERMAP && it.value == 28.0 })
    assertEquals(9.0, coarse.bundle!!.hourly.single().precipitationMm!!, 0.0)
  }

  @Test
  fun `unknown accumulation metadata cannot become an hourly amount and does not discard other variables`() = runTest {
    val unknown = fetch(ProviderRegistry.METEOSOURCE,
      listOf(HourlyPoint(time + hour, temperatureC = 27.0, windSpeedKmh = 14.0, precipitationMm = 6.0)),
      PrecipitationAccumulation())
    val fused = fuse(listOf(unknown)).hours.single { it.timestampMillis == time + hour }
    assertNull(fused.values[FusionVariables.PRECIPITATION])
    assertEquals(27.0, fused.values.getValue(FusionVariables.TEMPERATURE).value, 0.0)
    assertEquals(14.0, fused.values.getValue(FusionVariables.WIND_SPEED).value, 0.0)
  }

  @Test
  fun `a missing one hour interval is not fabricated from an adjacent known dry interval`() = runTest {
    val dense = fetch(ProviderRegistry.OPEN_METEO,
      (1..3).map { HourlyPoint(time + it * hour, temperatureC = 20.0) }, precedingHour)
    val sparse = fetch(ProviderRegistry.OPEN_METEO_ICON,
      listOf(HourlyPoint(time + 2 * hour, precipitationMm = 0.0)), precedingHour)
    val fused = fuse(listOf(dense, sparse))
    assertNull(fused.hours.single { it.timestampMillis == time + hour }.values[FusionVariables.PRECIPITATION])
    assertEquals(0.0, fused.hours.single { it.timestampMillis == time + 2 * hour }.values.getValue(FusionVariables.PRECIPITATION).value, 0.0)
    assertNull(fused.hours.single { it.timestampMillis == time + 3 * hour }.values[FusionVariables.PRECIPITATION])
  }

  @Test
  fun `duplicate source endpoints cannot silently pick a precipitation value`() = runTest {
    val duplicate = fetch(ProviderRegistry.OPEN_METEO,
      listOf(HourlyPoint(time + hour, precipitationMm = 0.0), HourlyPoint(time + hour, precipitationMm = 8.0)), precedingHour)
    val other = fetch(ProviderRegistry.OPEN_METEO_ICON,
      listOf(HourlyPoint(time + hour, temperatureC = 21.0, precipitationMm = 2.0)), precedingHour)
    val rain = fuse(listOf(duplicate, other)).hours.single().values.getValue(FusionVariables.PRECIPITATION)
    assertEquals(2.0, rain.value, 0.0)
    assertEquals(listOf(ProviderRegistry.OPEN_METEO_ICON), rain.contributions.map { it.providerId })
  }

  @Test
  fun `a nonfinite scalar cannot poison other valid variables or contributors`() = runTest {
    val invalid = fetch(ProviderRegistry.OPEN_METEO,
      listOf(HourlyPoint(time + hour, temperatureC = Double.NaN, windSpeedKmh = 14.0)), precedingHour)
    val valid = fetch(ProviderRegistry.OPEN_METEO_ICON,
      listOf(HourlyPoint(time + hour, temperatureC = 21.0)), precedingHour)
    val result = fuse(listOf(invalid, valid)).hours.single()
    assertEquals(21.0, result.values.getValue(FusionVariables.TEMPERATURE).value, 0.0)
    assertEquals(listOf(ProviderRegistry.OPEN_METEO_ICON), result.values.getValue(FusionVariables.TEMPERATURE).contributions.map { it.providerId })
    assertEquals(14.0, result.values.getValue(FusionVariables.WIND_SPEED).value, 0.0)
  }

  @Test
  fun `hour starting NWS probability shifts to its interval end and keeps the original temperature timestamp`() = runTest {
    val nws = fetch(ProviderRegistry.NWS,
      listOf(HourlyPoint(time + hour, temperatureC = 22.0, precipitationProbabilityPercent = 80.0)), null)
    val result = fuse(listOf(nws))
    assertNull(result.hours.single { it.timestampMillis == time + hour }.values[FusionVariables.PRECIP_PROBABILITY])
    assertEquals(22.0, result.hours.single { it.timestampMillis == time + hour }.values.getValue(FusionVariables.TEMPERATURE).value, 0.0)
    assertEquals(80.0, result.hours.single { it.timestampMillis == time + 2 * hour }.values.getValue(FusionVariables.PRECIP_PROBABILITY).value, 0.0)
    assertEquals(WeatherInterval(time + hour, time + 2 * hour), result.hours.single { it.timestampMillis == time + 2 * hour }.precipitationInterval)
  }

  @Test
  fun `three hour probability is not relabelled as a one hour probability`() = runTest {
    val coarse = fetch(ProviderRegistry.OPENWEATHERMAP,
      listOf(HourlyPoint(time + 3 * hour, temperatureC = 22.0, precipitationProbabilityPercent = 80.0)),
      PrecipitationAccumulation(3 * hour, AccumulationAnchor.PRECEDING))
    val result = fuse(listOf(coarse)).hours.single()
    assertNull(result.values[FusionVariables.PRECIP_PROBABILITY])
    assertEquals(22.0, result.values.getValue(FusionVariables.TEMPERATURE).value, 0.0)
  }
}
