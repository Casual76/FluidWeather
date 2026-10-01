package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Cancella tutti i dati" contro chi scrive in background: una cancellazione che arriva mentre il
 * giudice aspetta la rete o il registratore valuta non deve essere seguita dalle loro scritture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WipeRaceTest {

  private val issued = MIDNIGHT + 10 * HOUR + 20 * 60_000L
  private val finalAt = MIDNIGHT + 14 * HOUR + TruthPanel.FINALITY_MILLIS

  private fun panel(): String {
    val times = (0 until 48).map { MIDNIGHT + it * HOUR }
    return archiveJson(times, TruthPanel.MODELS.associate { "precipitation_$it" to times.map { 0.0 } })
  }

  private fun pending() = RainEventPending(
    "gps", 43.832, 11.199, issued, RainBoardIds.BAROMETER, "1-3h", issued, 0.4, ModelVersions.TAG, "FRESH",
  )

  @Test
  fun `una cancellazione durante il giudizio non lascia ne' verifiche ne' esiti`() = runTest(UnconfinedTestDispatcher()) {
    val guard = DataWipeGuard()
    val store = InMemoryRainEventStore().also { it.addPending(listOf(pending())) }
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val slow = object : EngineHttp() {
      override suspend fun readText(url: String, headers: Map<String, String>): String {
        entered.complete(Unit)
        release.await()
        return panel()
      }
    }
    val outcomes = mutableListOf<Triple<Long, String, Boolean>>()
    val settler = TruthPanelSettler(
      http = providerHttpOf(slow) { finalAt },
      store = store,
      observations = { emptyList() },
      onBarometerOutcome = { roundId, window, rained -> outcomes += Triple(roundId, window, rained) },
      clock = { finalAt },
      wipeGuard = guard,
    )

    val settling = async { settler.settle() }
    entered.await()
    // L'utente cancella tutto mentre la rete e' ancora in volo.
    guard.wipe { store.pending.clear() }
    release.complete(Unit)
    val summary = settling.await()

    assertEquals(0, summary.settled)
    assertTrue("nessuna verifica resuscitata", store.verified.isEmpty())
    assertTrue("nessun esito all'apprendimento", outcomes.isEmpty())
  }

  @Test
  fun `senza cancellazione il giudizio scrive come prima`() = runTest(UnconfinedTestDispatcher()) {
    val store = InMemoryRainEventStore().also { it.addPending(listOf(pending())) }
    val outcomes = mutableListOf<Triple<Long, String, Boolean>>()
    val settler = TruthPanelSettler(
      http = providerHttpOf(RecordingHttp { panel() }) { finalAt },
      store = store,
      observations = { emptyList() },
      onBarometerOutcome = { roundId, window, rained -> outcomes += Triple(roundId, window, rained) },
      clock = { finalAt },
      wipeGuard = DataWipeGuard(),
    )

    val summary = settler.settle()

    assertEquals(1, summary.settled)
    assertEquals(1, outcomes.size)
  }

  @Test
  fun `una cancellazione durante l'iscrizione non lascia righe ne' archivio`() = runTest(UnconfinedTestDispatcher()) {
    val guard = DataWipeGuard()
    val store = InMemoryRainEventStore()
    val issues = mutableListOf<NowcastIssueRecord>()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val verdict = NowcastVerdict(
      listOf("0-1h", "1-3h", "3-6h").map { WindowVerdict(it, 0.3, 0.3, 0.3, emptyList()) },
      AlertLevel.QUIETE,
    )
    val registrar = LocalRoundRegistrar(
      evaluator = { _, _ ->
        entered.complete(Unit)
        release.await()
        LocalEvaluation(verdict, verdict, DoubleArray(FeatureExtractor.names.size), verdict)
      },
      store = store,
      recordIssue = { issues += it },
      climatology = { _, _ -> null },
      sensorAvailable = { true },
      clock = { issued + 5_000L },
      wipeGuard = guard,
    )
    val snapshot = WeatherSnapshot(
      placeKey = WeatherSnapshot.GPS_KEY,
      latitude = 43.8321,
      longitude = 11.1994,
      fetchedAtMillis = issued,
      fetches = emptyList(),
      fused = FusedForecast(emptyList(), emptyMap()),
      context = ForecastBundle("open-meteo", issued, 43.8321, 11.1994, listOf(HourlyPoint(issued, temperatureC = 20.0))),
    )

    val registering = async { registrar.register(RegisteredRound(issued, snapshot, emptyList())) }
    entered.await()
    guard.wipe { }
    release.complete(Unit)

    assertNull(registering.await())
    assertTrue("nessuna riga in attesa", store.pending.isEmpty())
    assertTrue("nessuna emissione nell'archivio", issues.isEmpty())
  }
}
