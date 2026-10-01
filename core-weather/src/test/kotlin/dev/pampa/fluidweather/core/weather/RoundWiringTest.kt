package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il grafo dell'app in piccolo: lo stesso collegamento che fa `AppGraph`, senza Android.
 *
 * Refresher -> gancio -> registratore -> magazzino della pioggia -> giudice -> esito
 * all'apprendimento. Ognuno di questi pezzi ha i suoi test; qui si prova che, cuciti come nell'app,
 * un giro del telefono arrivi in classifica con un istante solo e che il suo esito torni, chiavato
 * su quell'istante, a chi deve imparare.
 */
class RoundWiringTest {

  private val roundId = MIDNIGHT + 10 * HOUR + 20 * 60_000L

  private class Graph(val roundId: Long, hasBarometer: Boolean) {
    var clock = roundId
    val rainEvents = InMemoryRainEventStore()
    val issues = mutableListOf<NowcastIssueRecord>()
    val outcomes = mutableListOf<NowcastOutcomeRecord>()
    val http = RecordingHttp { "{}" }
    var evaluations = 0

    val registrar = LocalRoundRegistrar(
      evaluator = { _, _ ->
        evaluations++
        LocalEvaluation(
          verdict = NowcastVerdict(
            listOf(
              WindowVerdict("0-1h", 0.15, 0.1, 0.2, emptyList()),
              WindowVerdict("1-3h", 0.55, 0.4, 0.7, emptyList()),
              WindowVerdict("3-6h", 0.35, 0.2, 0.5, emptyList()),
            ),
            AlertLevel.QUIETE,
          ),
          rawVerdict = NowcastVerdict(
            listOf(
              WindowVerdict("0-1h", 0.10, 0.1, 0.1, emptyList()),
              WindowVerdict("1-3h", 0.50, 0.5, 0.5, emptyList()),
              WindowVerdict("3-6h", 0.30, 0.3, 0.3, emptyList()),
            ),
            AlertLevel.QUIETE,
          ),
          features = DoubleArray(FeatureExtractor.names.size),
          soloVerdict = null,
        )
      },
      store = rainEvents,
      recordIssue = { issues += it },
      climatology = { _, _ -> null },
      sensorAvailable = { hasBarometer },
      clock = { clock },
    )

    val snapshots = WeatherSnapshotStore(Files.createTempDirectory("snapshots").toFile())
    val refresher = WeatherSnapshotRefresher(
      coordinator = object : RoundSource {
        override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
          val hourly = listOf(11, 12, 13, 14, 15, 16, 17).map { HourlyPoint(MIDNIGHT + it * HOUR, precipitationProbabilityPercent = 40.0) }
          return WeatherRound(
            listOf(
              ProviderFetch(
                ProviderRegistry.all.first { it.id == ProviderRegistry.OPEN_METEO },
                ForecastBundle(ProviderRegistry.OPEN_METEO, clock, latitude, longitude, hourly),
                null,
              ),
            ),
            FusedForecast(emptyList(), emptyMap()),
          )
        }
      },
      store = snapshots,
      onGpsRoundRegistered = { round -> registrar.register(round) },
    ) { clock }

    val settler = TruthPanelSettler(
      http = providerHttpOf(http) { clock },
      store = rainEvents,
      observations = { emptyList() },
      onBarometerOutcome = { id, window, rained -> outcomes += NowcastOutcomeRecord(id, window, rained) },
      clock = { clock },
    )
  }

  /** L'archivio dei tre giudici: piove (0,5 mm) negli slot delle 13 e delle 14, asciutto altrove. */
  private fun rainyPanel(): String {
    val times = (0 until 48).map { MIDNIGHT + it * HOUR }
    val wet = setOf(MIDNIGHT + 13 * HOUR, MIDNIGHT + 14 * HOUR)
    return archiveJson(
      times,
      TruthPanel.MODELS.associate { model -> "precipitation_$model" to times.map { if (it in wet) 0.5 else 0.0 } },
    )
  }

  @Test
  fun `un giro del telefono entra in classifica con un istante solo e il suo esito torna all'apprendimento`() = runTest {
    val graph = Graph(roundId, hasBarometer = true)

    graph.refresher.refresh(WeatherSnapshot.GPS_KEY, 43.832, 11.199)

    // Provider, riferimenti e barometro: tutti sullo stesso giro, e l'archivio col giro del barometro.
    val rows = graph.rainEvents.pending
    assertTrue(rows.isNotEmpty() && rows.all { it.roundId == roundId && it.issuedAtMillis == roundId })
    assertEquals(
      setOf(ProviderRegistry.OPEN_METEO, RainBoardIds.ALWAYS_ZERO, RainBoardIds.BAROMETER),
      rows.map { it.providerId }.toSet(),
    )
    assertEquals(roundId, graph.issues.single().roundId)

    // Il giorno dopo il pannello giudica, e l'esito del barometro arriva con lo stesso giro.
    graph.clock = MIDNIGHT + 17 * HOUR + TruthPanel.FINALITY_MILLIS
    graph.http.respond = { rainyPanel() }
    val summary = graph.settler.settle()

    assertEquals(9, summary.settled)
    assertEquals(0, graph.rainEvents.pendingCount())
    assertEquals(
      setOf(
        NowcastOutcomeRecord(roundId, "0-1h", false),
        NowcastOutcomeRecord(roundId, "1-3h", true),
        NowcastOutcomeRecord(roundId, "3-6h", false),
      ),
      graph.outcomes.toSet(),
    )
  }

  @Test
  fun `senza barometro il giro iscrive provider e riferimenti e non valuta niente`() = runTest {
    val graph = Graph(roundId, hasBarometer = false)

    graph.refresher.refresh(WeatherSnapshot.GPS_KEY, 43.832, 11.199)

    assertEquals(0, graph.evaluations)
    assertTrue(graph.issues.isEmpty())
    assertEquals(setOf(ProviderRegistry.OPEN_METEO, RainBoardIds.ALWAYS_ZERO), graph.rainEvents.pending.map { it.providerId }.toSet())
  }

  @Test
  fun `una localita' salvata e un giro senza semina non iscrivono niente alla classifica del telefono`() = runTest {
    val graph = Graph(roundId, hasBarometer = true)

    graph.refresher.refresh(WeatherSnapshot.keyFor(1), 45.4, 9.2)
    assertTrue(graph.rainEvents.pending.isEmpty())

    graph.refresher.refresh(WeatherSnapshot.GPS_KEY, 43.832, 11.199)
    val rows = graph.rainEvents.pending.size
    // Venti minuti dopo il refresher non semina, quindi il gancio non parte: niente righe doppie.
    graph.clock += 20 * 60_000L
    graph.refresher.refresh(WeatherSnapshot.GPS_KEY, 43.832, 11.199)

    assertEquals(rows, graph.rainEvents.pending.size)
    assertEquals(1, graph.evaluations)
    assertEquals(1, graph.issues.size)
  }
}
