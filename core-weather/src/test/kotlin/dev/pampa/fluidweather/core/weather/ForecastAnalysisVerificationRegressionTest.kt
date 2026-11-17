package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Model-analysis peer agreement is never asserted to be a measured observation. */
class ForecastAnalysisVerificationRegressionTest {
  private val target = 1_788_264_000_000L
  private val hour = 3_600_000L
  private val now = target + 5 * 60_000L
  private val subject = ProviderRegistry.OPEN_METEO
  private val peers = listOf(ProviderRegistry.OPEN_METEO_ICON, ProviderRegistry.OPEN_METEO_ECMWF, ProviderRegistry.OPEN_METEO_GFS)
  private val lat = 43.83
  private val lon = 11.2
  private val precedingHour = PrecipitationAccumulation(hour, AccumulationAnchor.PRECEDING)

  private fun fetch(id: String, temperature: Double = 24.0, at: Long = target, downloaded: Long = now,
    metadata: PrecipitationAccumulation? = precedingHour) = ProviderFetch(
      ProviderRegistry.all.first { it.id == id },
      ForecastBundle(id, downloaded, lat, lon, listOf(HourlyPoint(at, temperatureC = temperature, precipitationMm = 2.0)), hourlyPrecipitation = metadata), null)

  private fun pending(variable: String = FusionVariables.TEMPERATURE) =
    PendingPrediction(subject, variable, target, 20.0, target - hour, lat, lon,
      semanticsVersion = ForecastLearning.CURRENT_SEMANTICS)

  @Test
  fun `cached forecasts acquired before the target cannot settle it after time passes`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending() }
    ForecastVerifier(store, clock = { now }).settle(peers.map { fetch(it, downloaded = target - 60_000L) })
    assertTrue(store.verifications.isEmpty())
    assertEquals(listOf(pending()), store.pending)
  }

  @Test
  fun `future download clocks cannot count as acquired peer analyses`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending() }
    ForecastVerifier(store, clock = { now }).settle(peers.map { fetch(it, downloaded = now + 60_000L) })
    assertTrue(store.verifications.isEmpty())
    assertEquals(1, store.pending.size)
  }

  @Test
  fun `nearby forecast timestamps do not invent an exact target analysis`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending() }
    ForecastVerifier(store, clock = { target + 2 * hour }).settle(peers.map { fetch(it, at = target + hour, downloaded = target + 2 * hour) })
    assertTrue(store.verifications.isEmpty())
    assertEquals(1, store.pending.size)
  }

  @Test
  fun `subject cannot provide its own deciding quorum vote`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending() }
    ForecastVerifier(store, clock = { now }).settle(listOf(fetch(subject, 20.0), fetch(peers[0]), fetch(peers[1])))
    assertTrue(store.verifications.isEmpty())
    assertEquals(1, store.pending.size)
  }

  @Test
  fun `three copies of the same model are one opinion rather than a quorum`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending() }
    val duplicate = fetch(peers[0])
    ForecastVerifier(store, clock = { now }).settle(listOf(duplicate, duplicate, duplicate))
    assertTrue(store.verifications.isEmpty())
    assertEquals(1, store.pending.size)
  }

  @Test
  fun `ambiguous best match cannot be an extra independent vote beside named models`() = runTest {
    val store = InMemoryVerificationStore().apply {
      pending += pending().copy(providerId = ProviderRegistry.MET_NORWAY)
    }
    ForecastVerifier(store, clock = { now }).settle(listOf(fetch(subject), fetch(peers[0]), fetch(peers[1])))
    assertTrue(store.verifications.isEmpty())
    assertEquals(1, store.pending.size)
  }

  @Test
  fun `three declared suitable external analyses settle against their leave one out median`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending() }
    val analyses = listOf(fetch(subject, 20.0), fetch(peers[0], 22.0), fetch(peers[1], 24.0), fetch(peers[2], 26.0))
    ForecastVerifier(store, clock = { now }).settle(analyses)
    assertEquals(4.0, store.verifications.single().absoluteError, 0.0)
    assertTrue(store.pending.isEmpty())
  }

  @Test
  fun `one nonfinite analysis cannot poison the median or create a quorum`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending() }
    ForecastVerifier(store, clock = { now }).settle(listOf(fetch(peers[0], Double.NaN), fetch(peers[1]), fetch(peers[2])))
    assertTrue(store.verifications.isEmpty())
    assertEquals(1, store.pending.size)
  }

  @Test
  fun `three hour rain cannot be seeded as a one hour verification but its temperature remains usable`() = runTest {
    val store = InMemoryVerificationStore()
    val coarse = fetch(ProviderRegistry.OPENWEATHERMAP, at = target + hour, downloaded = target,
      metadata = PrecipitationAccumulation(3 * hour, AccumulationAnchor.PRECEDING))
    ForecastVerifier(store, clock = { target }).registerPending(listOf(coarse))
    assertTrue(store.pending.any { it.variable == FusionVariables.TEMPERATURE })
    assertTrue(store.pending.none { it.variable == FusionVariables.PRECIPITATION })
  }

  @Test
  fun `an explicit named model cannot vote in its own reference median`() = runTest {
    val store = InMemoryVerificationStore().apply { pending += pending().copy(providerId = peers[0]) }
    ForecastVerifier(store, clock = { now }).settle(listOf(fetch(peers[0], 20.0), fetch(peers[1], 22.0),
      fetch(peers[2], 24.0), fetch(ProviderRegistry.OPEN_METEO_AROME, 26.0)))
    assertEquals(4.0, store.verifications.single().absoluteError, 0.0)
  }

  @Test
  fun `cached prediction issue clock remains the original provider acquisition`() = runTest {
    val store = InMemoryVerificationStore()
    val originalDownload = target - 25 * 60_000L
    ForecastVerifier(store, clock = { target }).registerPending(listOf(fetch(subject, at = target + hour, downloaded = originalDownload)))
    assertTrue(store.pending.isNotEmpty())
    assertTrue(store.pending.all { it.issuedAtMillis == originalDownload })
  }
}
