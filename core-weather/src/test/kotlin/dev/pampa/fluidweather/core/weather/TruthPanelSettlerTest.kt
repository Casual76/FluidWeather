package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.RoomRainEventStore
import dev.pampa.fluidweather.core.model.Observation
import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.core.model.RainEventStore
import dev.pampa.fluidweather.core.model.RainEventVerification
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il giudice della classifica pioggia contro un archivio finto.
 *
 * Il giro e' emesso alle 10:20 del 1° settembre: la finestra 1-3h somma gli slot che si chiudono
 * alle 13:00 e alle 14:00, e si puo' giudicare dalle 14:00 del giorno dopo.
 */
class TruthPanelSettlerTest {

  private val issued = MIDNIGHT + 10 * HOUR + 20 * 60_000L
  private val finalAt = MIDNIGHT + 14 * HOUR + TruthPanel.FINALITY_MILLIS

  private fun at(hour: Int, minute: Int = 0) = MIDNIGHT + hour * HOUR + minute * 60_000L

  private fun row(
    providerId: String = RainBoardIds.BAROMETER,
    window: String = "1-3h",
    latitude: Double = 43.832,
    longitude: Double = 11.199,
    issuedAt: Long = issued,
  ) = RainEventPending("gps", latitude, longitude, issuedAt, providerId, window, issuedAt, 0.4, ModelVersions.TAG, "FRESH")

  /** L'archivio dei tre giudici: [slots] da fine dello slot a (Météo-France, UKMO, GEM); il resto e' asciutto. */
  private fun panel(slots: Map<Long, List<Double?>> = emptyMap()): String {
    val times = (0 until 48).map { MIDNIGHT + it * HOUR }
    return archiveJson(
      times,
      TruthPanel.MODELS.withIndex().associate { (index, model) ->
        "precipitation_$model" to times.map { time -> slots[time]?.get(index) ?: if (slots[time] == null) 0.0 else null }
      },
    )
  }

  private fun observation(timestamp: Long, condition: ObservedCondition, latitude: Double?, longitude: Double?) =
    Observation(timestamp, timestamp, condition, latitude, longitude, null)

  private inner class Bench(reply: String, var observations: List<Observation> = emptyList()) {
    val store = InMemoryRainEventStore()
    var now = finalAt
    var downloadClock: (() -> Long)? = null
    val http = RecordingHttp { reply }
    val outcomes = mutableListOf<Triple<Long, String, Boolean>>()
    val settler = TruthPanelSettler(
      http = providerHttpOf(http) { downloadClock?.invoke() ?: now },
      store = store,
      observations = { since -> observations.filter { it.timestampMillis >= since } },
      onBarometerOutcome = { roundId, window, rained -> outcomes += Triple(roundId, window, rained) },
      clock = { now },
    )
  }

  @Test
  fun `prima della finalita' non si chiede niente, dopo si giudica`() = runTest {
    val bench = Bench(panel())
    bench.store.addPending(listOf(row()))

    bench.now = finalAt - 60_000L
    val early = bench.settler.settle()
    assertEquals(0, early.due)
    assertTrue("nessuna richiesta prima che le ore siano definitive", bench.http.urls.isEmpty())
    assertEquals(1, bench.store.pendingCount())

    bench.now = finalAt
    val summary = bench.settler.settle()
    assertEquals(1, summary.settled)
    assertEquals(0, bench.store.pendingCount())
  }

  @Test
  fun `la richiesta va all'archivio, coi tre giudici, nel punto della riga`() = runTest {
    val bench = Bench(panel())
    bench.store.addPending(listOf(row()))
    bench.settler.settle()

    val url = bench.http.urls.single()
    assertTrue(url.startsWith("https://historical-forecast-api.open-meteo.com/v1/forecast?"))
    assertTrue(url.contains("latitude=43.832&longitude=11.199&start_date=2026-09-01&end_date=2026-09-01&"))
    assertTrue(url.contains("models=meteofrance_seamless,ukmo_seamless,gem_seamless&"))
  }

  @Test
  fun `decide la mediana dei tre, non il modello che da solo inventa un temporale`() = runTest {
    val bench = Bench(
      panel(
        mapOf(
          // 1-3h: mediane 0,1 e 0,1 -> 0,2 mm, bagnata (la media sarebbe molto piu' alta).
          at(13) to listOf(0.0, 0.1, 2.0),
          at(14) to listOf(0.1, 0.0, 3.0),
          // 3-6h: un temporale lo vede solo Météo-France -> mediana zero, asciutta.
          at(15) to listOf(5.0, 0.0, 0.0),
        ),
      ),
    )
    bench.store.addPending(listOf(row(window = "1-3h"), row(window = "3-6h")))
    bench.now = MIDNIGHT + 17 * HOUR + TruthPanel.FINALITY_MILLIS

    bench.settler.settle()

    val wet = bench.store.verified.single { it.prediction.window == "1-3h" }
    assertTrue(wet.rained)
    assertEquals(0.2, wet.truthSumMm!!, 1e-9)
    assertEquals(3, wet.truthVoters)
    assertEquals(TruthPanel.VERSION, wet.truthSource)
    assertEquals(finalAt + 3 * HOUR, wet.settledAtMillis)
    val dry = bench.store.verified.single { it.prediction.window == "3-6h" }
    assertTrue(!dry.rained)
    assertEquals(0.0, dry.truthSumMm!!, 1e-9)
  }

  @Test
  fun `un giudice che manca lascia la riga in attesa`() = runTest {
    val bench = Bench(panel(mapOf(at(14) to listOf(0.0, 0.0, null))))
    bench.store.addPending(listOf(row()))

    val summary = bench.settler.settle()

    assertEquals(1, summary.due)
    assertEquals(0, summary.settled)
    assertEquals(1, bench.store.pendingCount())
  }

  @Test
  fun `pioggia vista entro tre km - la finestra e' bagnata anche se i giudici dicono di no`() = runTest {
    // 43,84 e' meno di un chilometro dalla riga; le 13:30 stanno nello slot (13:00, 14:00].
    val bench = Bench(panel(), listOf(observation(at(13, 30), ObservedCondition.RAIN, 43.84, 11.2)))
    bench.store.addPending(listOf(row()))

    bench.settler.settle()

    val verification = bench.store.verified.single()
    assertTrue(verification.rained)
    assertEquals(0.0, verification.truthSumMm!!, 1e-9)
    assertEquals(TruthPanelSettler.OBSERVATION_SOURCE, verification.truthSource)
  }

  @Test
  fun `asciutto visto azzera il suo slot`() = runTest {
    // Senza l'osservazione lo slot delle 13 da solo (0,3 mm) farebbe la finestra bagnata.
    val slots = mapOf(at(13) to listOf(0.3, 0.3, 0.3))
    val senza = Bench(panel(slots))
    senza.store.addPending(listOf(row()))
    senza.settler.settle()
    assertTrue(senza.store.verified.single().rained)

    val con = Bench(panel(slots), listOf(observation(at(12, 40), ObservedCondition.CLEAR, 43.832, 11.199)))
    con.store.addPending(listOf(row()))
    con.settler.settle()

    val verification = con.store.verified.single()
    assertTrue(!verification.rained)
    assertEquals(0.0, verification.truthSumMm!!, 1e-9)
    assertEquals(TruthPanelSettler.OBSERVATION_SOURCE, verification.truthSource)
  }

  @Test
  fun `osservazioni a dieci km o senza coordinate non contano`() = runTest {
    val bench = Bench(
      panel(),
      listOf(
        observation(at(13, 30), ObservedCondition.RAIN, 43.92, 11.199),
        observation(at(13, 40), ObservedCondition.HEAVY_RAIN, null, null),
      ),
    )
    bench.store.addPending(listOf(row()))

    bench.settler.settle()

    val verification = bench.store.verified.single()
    assertTrue(!verification.rained)
    assertEquals(TruthPanel.VERSION, verification.truthSource)
  }

  @Test
  fun `i votanti sono quelli presenti in tutti gli slot`() = runTest {
    // GEM manca alle 14; la finestra la decide comunque la pioggia vista alle 12:30 (slot delle 13).
    val bench = Bench(
      panel(mapOf(at(14) to listOf(0.0, 0.0, null))),
      listOf(observation(at(12, 30), ObservedCondition.DRIZZLE, 43.832, 11.199)),
    )
    bench.store.addPending(listOf(row()))

    bench.settler.settle()

    val verification = bench.store.verified.single()
    assertTrue(verification.rained)
    assertNull("con un buco la somma non si conosce", verification.truthSumMm)
    assertEquals(2, verification.truthVoters)
  }

  @Test
  fun `l'esito del barometro torna all'apprendimento, quello dei provider no`() = runTest {
    val bench = Bench(panel(mapOf(at(13) to listOf(0.5, 0.5, 0.5))))
    bench.store.addPending(listOf(row(), row(providerId = ProviderRegistry.OPEN_METEO), row(providerId = RainBoardIds.ALWAYS_ZERO)))

    bench.settler.settle()

    assertEquals(3, bench.store.verified.size)
    assertEquals(listOf(Triple(issued, "1-3h", true)), bench.outcomes)
  }

  @Test
  fun `un errore di rete lascia le righe in attesa`() = runTest {
    val bench = Bench(panel())
    bench.http.respond = { error("niente rete") }
    bench.store.addPending(listOf(row()))

    val summary = bench.settler.settle()

    assertEquals(1, summary.requests)
    assertEquals(1, summary.failedRequests)
    assertEquals(1, bench.store.pendingCount())
    assertTrue(bench.outcomes.isEmpty())
  }

  @Test
  fun `un archivio scaricato prima della finalita' non giudica`() = runTest {
    val bench = Bench(panel(mapOf(at(13) to listOf(0.5, 0.5, 0.5))))
    // L'orologio del giudice dice "maturo", ma il download (per esempio da un proxy) e' di un'ora prima.
    bench.downloadClock = { finalAt - HOUR }
    bench.store.addPending(listOf(row()))

    bench.settler.settle()

    assertTrue(bench.store.verified.isEmpty())
    assertEquals(1, bench.store.pendingCount())
  }

  @Test
  fun `dopo sette giorni senza verita' la riga si butta`() = runTest {
    val bench = Bench(panel())
    bench.http.respond = { error("niente rete") }
    bench.store.addPending(listOf(row()))

    bench.now = issued + TruthPanelSettler.PENDING_MAX_AGE_MILLIS + 60_000L
    val summary = bench.settler.settle()

    assertEquals(1, summary.expired)
    assertEquals(0, bench.store.pendingCount())
    assertTrue(bench.store.verified.isEmpty())
  }

  @Test
  fun `un secondo giro non duplica i giudizi`() = runTest {
    val bench = Bench(panel())
    bench.store.addPending(listOf(row()))
    bench.settler.settle()
    // Un crash fra la scrittura del giudizio e la rimozione della pendente la lascerebbe li'.
    bench.store.addPending(listOf(row()))

    bench.settler.settle()

    assertEquals(1, bench.store.verificationCount())
    assertEquals(0, bench.store.pendingCount())
  }

  @Test
  fun `due posti lontani fanno due richieste`() = runTest {
    val bench = Bench(panel())
    bench.store.addPending(listOf(row(), row(latitude = 43.95, longitude = 11.3, providerId = ProviderRegistry.OPEN_METEO)))

    val summary = bench.settler.settle()

    assertEquals(2, summary.requests)
    assertEquals(2, bench.http.urls.size)
    assertEquals(2, summary.settled)
  }

  @Test
  fun `se il giudizio non si salva l'esito e' gia' all'apprendimento e la riga resta da giudicare`() = runTest {
    // Il processo che muore (o il tetto di tempo del ciclo) fra le due scritture: prima l'esito si
    // scriveva DOPO il giudizio, e una morte in mezzo lo perdeva per sempre (la riga non era piu'
    // in attesa). Qui il magazzino fallisce la prima volta.
    val inner = InMemoryRainEventStore()
    var failSettle = true
    val store = object : RainEventStore by inner {
      override suspend fun settle(verifications: List<RainEventVerification>) {
        check(!failSettle) { "processo ucciso fra l'esito e il giudizio" }
        inner.settle(verifications)
      }
    }
    val outcomes = mutableListOf<Triple<Long, String, Boolean>>()
    val http = RecordingHttp { panel(mapOf(at(13) to listOf(0.4, 0.3, 0.5))) }
    val settler = TruthPanelSettler(
      http = providerHttpOf(http) { finalAt },
      store = store,
      observations = { emptyList() },
      onBarometerOutcome = { roundId, window, rained -> outcomes += Triple(roundId, window, rained) },
      clock = { finalAt },
    )
    inner.addPending(listOf(row()))

    assertTrue(runCatching { settler.settle() }.isFailure)
    assertEquals(listOf(Triple(issued, "1-3h", true)), outcomes)
    assertEquals("la riga resta in attesa", 1, inner.pendingCount())
    assertEquals(0, inner.verificationCount())

    failSettle = false
    settler.settle()

    assertEquals(0, inner.pendingCount())
    assertEquals(1, inner.verificationCount())
    // L'esito ripetuto e' identico: l'archivio lo chiava su (giro, finestra) e lo sostituisce.
    assertEquals(setOf(Triple(issued, "1-3h", true)), outcomes.toSet())
  }

  @Test
  fun `un esito che non si scrive non ferma i giudizi`() = runTest {
    val bench = Bench(panel())
    val settler = TruthPanelSettler(
      http = providerHttpOf(bench.http) { finalAt },
      store = bench.store,
      observations = { emptyList() },
      onBarometerOutcome = { _, _, _ -> error("archivio dell'apprendimento pieno") },
      clock = { finalAt },
    )
    bench.store.addPending(listOf(row(), row(providerId = ProviderRegistry.OPEN_METEO)))

    val summary = settler.settle()

    assertEquals(2, summary.settled)
    assertEquals(0, bench.store.pendingCount())
  }

  @Test
  fun `il giudice pota anche i giudizi oltre la ritenzione`() = runTest {
    // Sui telefoni senza barometro il ciclo in background non gira: la potatura deve passare di qui.
    val bench = Bench(panel())
    val old = issued - RoomRainEventStore.KEEP_MILLIS - HOUR
    bench.store.verified += RainEventVerification(row(issuedAt = old), false, 0.0, 3, TruthPanel.VERSION, old + HOUR)
    bench.store.addPending(listOf(row()))

    bench.settler.settle()

    assertEquals(listOf(issued), bench.store.verified.map { it.prediction.issuedAtMillis })
  }
}
