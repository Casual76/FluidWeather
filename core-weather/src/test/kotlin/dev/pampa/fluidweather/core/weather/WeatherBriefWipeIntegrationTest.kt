package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WeatherBriefWipeIntegrationTest {
  private val f = AssessmentFixture()

  @Test(timeout = 10_000) fun `registrar callback cannot renew its originating epoch in shared radar service`() = runBlocking {
    val root = Files.createTempDirectory("weather-nested-wipe").toFile()
    try {
      val guard = DataWipeGuard(); val inputs = RadarAssessmentCache(File(root, "radar-inputs"), guard) { f.noon }
      var loads = 0
      val service = WeatherBriefService(this, { _, _ -> loads++; f.radar(30) }, { f.noon }, radarCache = inputs, wipeGuard = guard)
      val store = WeatherSnapshotStore(File(root, "snapshots"), wipeGuard = guard)
      val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
      val source = object : RoundSource {
        override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean) = WeatherRound(
          listOf(ProviderFetch(ProviderRegistry.all.first { it.id == ProviderRegistry.OPEN_METEO }, f.snapshot().context, null)),
          FusedForecast(emptyList(), emptyMap()))
      }
      val refresher = WeatherSnapshotRefresher(source, store, onGpsRoundRegistered = { round ->
        assertEquals(0L, round.originatingWipeGeneration)
        entered.complete(Unit); release.await()
        service.radarSample(f.point.latitude, f.point.longitude)
      }, clock = { f.noon }, wipeGuard = guard)
      val old = async { refresher.refresh(WeatherSnapshot.GPS_KEY, f.point.latitude, f.point.longitude) }
      entered.await(); guard.wipe { service.clear(); store.clear() }; release.complete(Unit)
      try { old.await(); fail("nested old refresh returned") } catch (_: CancellationException) { }
      assertEquals(0, loads); assertNull(inputs.read(f.point)); assertNull(store.read(WeatherSnapshot.GPS_KEY))
      service.radarSample(f.point.latitude, f.point.longitude)
      assertEquals(1, loads); assertEquals(f.radar(30), inputs.read(f.point))
    } finally { root.deleteRecursively() }
  }

  @Test(timeout = 10_000) fun `cache only and brief calls also reject an inherited pre-wipe epoch`() = runBlocking {
    val root = Files.createTempDirectory("brief-origin-wipe").toFile()
    try {
      val guard = DataWipeGuard(); val origin = WeatherWipeEpoch(guard, guard.generation()); var loads = 0
      val inputs = RadarAssessmentCache(root, guard) { f.noon }
      val service = WeatherBriefService(this, { _, _ -> loads++; f.radar(0) }, { f.noon }, radarCache = inputs, wipeGuard = guard)
      guard.wipe { service.clear() }
      for (cachedOnly in listOf(false, true)) {
        try {
          withContext(origin) {
            if (cachedOnly) service.cachedBrief(f.place, f.point, null) else service.brief(f.place, f.point, null)
          }
          fail("origin renewed")
        } catch (_: CancellationException) { }
      }
      assertEquals(0, loads)
    } finally { root.deleteRecursively() }
  }
}
