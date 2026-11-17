package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.ProviderFetchSummary
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HomeForecastLoadTest {
  private val now = 1_800_000_000_000L
  private val cached = WeatherSnapshot("place-7", 44.0, 11.0, now - 120_000L,
    listOf(ProviderFetchSummary("fixture", 1, null, now - 120_000L)),
    FusedForecast(listOf(FusedHour(now,
      mapOf(FusionVariables.TEMPERATURE to FusedValue(10.0, listOf(Contribution("fixture", 1.0, 10.0)))),
      null)), emptyMap()), null)

  @Test fun explicitRefreshPerformsRealLoadDespiteFreshCacheAndSilentResumeKeepsIt() = runTest {
    var calls = 0
    val newer = cached.copy(fetchedAtMillis = now,
      fetches = cached.fetches.map { it.copy(downloadedAtMillis = now) })
    val load: suspend () -> WeatherSnapshot = { calls++; newer }
    assertSame(cached, loadHomeForecast(cached, now, 900_000L, true, false, load))
    assertEquals(0, calls)
    assertSame(newer, loadHomeForecast(cached, now, 900_000L, true, true, load))
    assertEquals(1, calls)
    assertSame(newer, loadHomeForecast(newer, now, 900_000L, true, false, load))
    assertEquals("A consumed user request must not make later resumes force refresh", 1, calls)
  }

  @Test fun failedExplicitLoadRetainsPreviousSnapshotAndCancellationPropagates() = runTest {
    assertSame(cached, loadHomeForecast(cached, now, 900_000L, true, true) {
      throw IllegalStateException("offline")
    })
    try {
      loadHomeForecast(cached, now, 900_000L, true, true) { throw CancellationException("left Home") }
      fail("cancellation swallowed")
    } catch (_: CancellationException) { }
  }

  @Test fun unavailableGpsNeverRefreshesYesterdaysPointEvenAfterExplicitGesture() = runTest {
    assertSame(cached, loadHomeForecast(cached, now, 900_000L, false, true) {
      fail("No acquisition at an unresolved current point"); cached
    })
  }

  @Test fun staleAutomaticLoadAndEmptyCacheUseAcquisition() = runTest {
    var calls = 0
    val load: suspend () -> WeatherSnapshot = { calls++; cached }
    loadHomeForecast(cached, now + 900_000L, 900_000L, true, false, load)
    loadHomeForecast(null, now, 900_000L, true, false, load)
    assertEquals(2, calls)
  }
}
