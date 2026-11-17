package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Local learning, provenance and actual latest-forecast contribution contracts. */
class LocalLearningRegressionTest {
  private val now = 1_800_000_000_000L
  private val latitude = 43.83
  private val longitude = 11.2
  private val providers = listOf(ProviderRegistry.OPEN_METEO, ProviderRegistry.OPEN_METEO_ICON)

  private fun judgment(provider: String = providers.first(), error: Double = 0.0) = ForecastVerification(
    providerId = provider, variable = FusionVariables.TEMPERATURE, horizonBucket = HorizonBucket.SHORT,
    absoluteError = error, verifiedAtMillis = now, latitude = latitude, longitude = longitude,
    semanticsVersion = ForecastLearning.CURRENT_SEMANTICS,
  )

  @Test fun `learning in A never changes B and unknown legacy is not local`() = runTest {
    val store = InMemoryVerificationStore().apply {
      repeat(25) { verifications += judgment() }
      repeat(100) { verifications += judgment(providers.last()).copy(latitude = null, longitude = null) }
      repeat(100) { verifications += judgment(providers.last()).copy(semanticsVersion = ForecastLearning.LEGACY_SEMANTICS) }
    }
    val scoreboard = ProviderScoreboard(store, clock = { now })
    val local = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, latitude, longitude, providers)
    val away = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, 45.46, 9.19, providers)
    assertEquals(WeightSource.LEARNED, local.getValue(providers.first()).source)
    assertEquals(25, local.getValue(providers.first()).verificationCount)
    assertEquals(WeightSource.PRIOR, local.getValue(providers.last()).source)
    assertEquals(0, local.getValue(providers.last()).verificationCount)
    assertTrue(away.values.all { it.source == WeightSource.PRIOR && it.verificationCount == 0 })
  }

  @Test fun `invalid errors and future rows cannot train the cascade`() = runTest {
    val store = InMemoryVerificationStore().apply {
      repeat(25) {
        verifications += judgment(error = Double.NaN)
        verifications += judgment(error = Double.POSITIVE_INFINITY)
        verifications += judgment(error = -1.0)
        verifications += judgment().copy(verifiedAtMillis = now + 1)
      }
    }
    val weight = ProviderScoreboard(store, clock = { now })
      .weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, latitude, longitude, providers).getValue(providers.first())
    assertEquals(WeightSource.PRIOR, weight.source)
    assertEquals(0, weight.verificationCount)
  }

  @Test fun `benchmark shares are actual contributions with zero for missing cells`() {
    fun cell(vararg providers: Pair<String, Double>) = FusedValue(20.0, providers.map { Contribution(it.first, it.second, 20.0) })
    val fused = FusedForecast(listOf(
      FusedHour(now, mapOf(FusionVariables.TEMPERATURE to cell(providers[0] to 0.8, providers[1] to 0.2)), null),
      FusedHour(now + 3_600_000L, mapOf(FusionVariables.TEMPERATURE to cell(providers[1] to 1.0)), null),
    ), emptyMap())
    val report = Benchmark.build(listOf(judgment()), now, latitude, longitude, fused)
    assertEquals(0.4, report.ranking.first { it.providerId == providers[0] }.share, 1e-12)
    assertEquals(0.6, report.ranking.first { it.providerId == providers[1] }.share, 1e-12)
    assertEquals(1.0, report.ranking.sumOf { it.share }, 1e-12)
    assertFalse(report.byVariable.getValue(FusionVariables.TEMPERATURE).first().learned)
    assertEquals(0, report.ranking.first { it.providerId == providers[1] }.verifications)
  }

  @Test fun `benchmark excludes away and legacy errors even if actual contributor exists`() {
    val fused = FusedForecast(listOf(FusedHour(now, mapOf(FusionVariables.TEMPERATURE to
      FusedValue(20.0, listOf(Contribution(providers.first(), 1.0, 20.0)))), null)), emptyMap())
    val rows = listOf(judgment().copy(latitude = 45.46), judgment().copy(semanticsVersion = ForecastLearning.LEGACY_SEMANTICS))
    val report = Benchmark.build(rows, now, latitude, longitude, fused)
    assertEquals(0, report.totalVerifications)
    assertTrue(report.byVariable.isEmpty())
    assertEquals(1.0, report.ranking.single().share, 0.0)
  }

  @Test fun `three kilometre locality has no global fallback`() = runTest {
    val store = InMemoryVerificationStore().apply { repeat(25) { verifications += judgment() } }
    val scoreboard = ProviderScoreboard(store, clock = { now })
    val nearby = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, latitude + 0.02, longitude, providers)
    val outside = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, latitude + 0.04, longitude, providers)
    assertEquals(WeightSource.LEARNED, nearby.getValue(providers.first()).source)
    assertEquals(WeightSource.PRIOR, outside.getValue(providers.first()).source)
    assertEquals(0, outside.getValue(providers.first()).verificationCount)
  }

  private fun forecast(id: String, pointLatitude: Double = latitude, at: Long = now + 3_600_000L,
    downloaded: Long = now, temperature: Double = 20.0) = ProviderFetch(
    ProviderRegistry.all.first { it.id == id },
    ForecastBundle(id, downloaded, pointLatitude, longitude, listOf(HourlyPoint(at, temperatureC = temperature))), null)

  @Test fun `benchmark actual shares match fusion below and above threshold including providers without errors`() = runTest {
    for (count in listOf(1, 25)) {
      val store = InMemoryVerificationStore().apply { repeat(count) { verifications += judgment(providers.last()) } }
      val scoreboard = ProviderScoreboard(store, clock = { now })
      val raw = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, latitude, longitude, providers)
      assertEquals(if (count == 1) WeightSource.PRIOR else WeightSource.LEARNED, raw.getValue(providers.last()).source)
      val fused = ForecastFusion(scoreboard).fuse(providers.map { forecast(it) }, latitude, longitude, now)
      val report = Benchmark.build(store.verifications, now, latitude, longitude, fused)
      val contributions = fused.hours.single().values.getValue(FusionVariables.TEMPERATURE).contributions
      contributions.forEach { contribution ->
        assertEquals(contribution.weight, report.ranking.single { it.providerId == contribution.providerId }.share, 1e-12)
      }
      assertEquals(2, report.ranking.size)
      assertEquals(0, report.ranking.single { it.providerId == providers.first() }.verifications)
    }
  }

  @Test fun `actual benchmark includes override and its fallback at uncovered hour`() = runTest {
    val store = InMemoryVerificationStore()
    val long = forecast(providers.last()).let { fetch ->
      val original = requireNotNull(fetch.bundle)
      fetch.copy(bundle = original.copy(hourly = original.hourly + HourlyPoint(now + 2 * 3_600_000L, temperatureC = 21.0)))
    }
    val fused = ForecastFusion(ProviderScoreboard(store, clock = { now }))
      .fuse(listOf(forecast(providers.first()), long), latitude, longitude, now, onlyProviderId = providers.first())
    val report = Benchmark.build(emptyList(), now, latitude, longitude, fused)
    assertEquals(2, fused.hours.size)
    assertEquals(0.5, report.ranking.first { it.providerId == providers.first() }.share, 1e-12)
    assertEquals(0.5, report.ranking.first { it.providerId == providers.last() }.share, 1e-12)
  }

  @Test fun `current settlement stamps location and version and leaves other place and legacy pending`() = runTest {
    val store = InMemoryVerificationStore()
    val verifier = ForecastVerifier(store, clock = { now + 3_600_000L + 60_000L })
    val a = PendingPrediction(providers.first(), FusionVariables.TEMPERATURE, now + 3_600_000L, 20.0, now,
      latitude, longitude, semanticsVersion = ForecastLearning.CURRENT_SEMANTICS)
    val b = a.copy(latitude = latitude + 1.0, placeKey = ForecastLearning.placeKey(latitude + 1.0, longitude))
    val legacy = a.copy(semanticsVersion = ForecastLearning.LEGACY_SEMANTICS)
    store.addPending(listOf(a, b, legacy))
    val peers = listOf(ProviderRegistry.OPEN_METEO_ICON, ProviderRegistry.OPEN_METEO_ECMWF, ProviderRegistry.OPEN_METEO_GFS)
    verifier.settle(peers.map { forecast(it, at = a.targetTimestampMillis, downloaded = now + 3_600_000L + 60_000L, temperature = 24.0) })
    val result = store.verifications.single()
    assertEquals(4.0, result.absoluteError, 0.0)
    assertEquals(latitude, result.latitude!!, 0.0)
    assertEquals(longitude, result.longitude!!, 0.0)
    assertEquals(ForecastLearning.CURRENT_SEMANTICS, result.semanticsVersion)
    assertEquals(setOf(b, legacy), store.pending.toSet())
  }

  @Test fun `registering same provider hour in two places retains both identities`() = runTest {
    val store = InMemoryVerificationStore()
    val verifier = ForecastVerifier(store, clock = { now })
    verifier.registerPending(listOf(forecast(providers.first()), forecast(providers.first(), pointLatitude = latitude + 1)))
    assertEquals(2, store.pending.size)
    assertEquals(2, store.pending.map { it.placeKey }.distinct().size)
    assertTrue(store.pending.all { it.semanticsVersion == ForecastLearning.CURRENT_SEMANTICS })
  }
}
