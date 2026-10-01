package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Come le righe mature diventano richieste all'archivio: poche, nel posto giusto, sui giorni giusti. */
class TruthRequestsTest {

  private fun row(
    latitude: Double,
    longitude: Double,
    issuedAt: Long = MIDNIGHT + 10 * HOUR + 20 * 60_000L,
    window: String = "1-3h",
    providerId: String = RainBoardIds.BAROMETER,
  ) = RainEventPending("gps", latitude, longitude, issuedAt, providerId, window, issuedAt, 0.3, ModelVersions.TAG, null)

  @Test
  fun `celle da un decimo di grado - vicini ma di la' dal bordo fanno due richieste`() {
    // 43,79 e 43,81 distano poco piu' di due chilometri ma stanno in celle diverse.
    val requests = TruthRequests.plan(listOf(row(43.79, 11.2), row(43.81, 11.2)))

    assertEquals(2, requests.size)
  }

  @Test
  fun `nella stessa cella, a cinque km si apre un grappolo nuovo, a due no`() {
    val lontani = TruthRequests.plan(listOf(row(43.801, 11.2), row(43.846, 11.2, issuedAt = MIDNIGHT + 11 * HOUR)))
    val vicini = TruthRequests.plan(listOf(row(43.801, 11.2), row(43.82, 11.2, issuedAt = MIDNIGHT + 11 * HOUR)))

    assertEquals(2, lontani.size)
    assertEquals(1, vicini.size)
    assertEquals(2, vicini.single().rows.size)
  }

  @Test
  fun `la richiesta va al punto della riga piu' vecchia`() {
    val older = row(43.82, 11.21, issuedAt = MIDNIGHT + 8 * HOUR)
    val newer = row(43.801, 11.2, issuedAt = MIDNIGHT + 9 * HOUR)

    val request = TruthRequests.plan(listOf(newer, older)).single()

    assertEquals(43.82, request.latitude, 0.0)
    assertEquals(11.21, request.longitude, 0.0)
  }

  @Test
  fun `le date comprendono lo slot che si chiude a mezzanotte`() {
    // 20:20, finestra 1-3h: ancora alle 21, slot che si chiudono alle 23 e alle 00:00 del 2.
    val acavallo = TruthRequests.plan(listOf(row(43.832, 11.199, issuedAt = MIDNIGHT + 20 * HOUR + 20 * 60_000L))).single()
    // 22:20, finestra 0-1h: l'unico slot si chiude alle 00:00 del 2, che per l'archivio e' il 2.
    val dopo = TruthRequests.plan(listOf(row(43.832, 11.199, issuedAt = MIDNIGHT + 22 * HOUR + 20 * 60_000L, window = "0-1h"))).single()

    assertEquals(LocalDate.of(2026, 9, 1), acavallo.startDate)
    assertEquals(LocalDate.of(2026, 9, 2), acavallo.endDate)
    assertEquals(LocalDate.of(2026, 9, 2), dopo.startDate)
    assertEquals(LocalDate.of(2026, 9, 2), dopo.endDate)
  }

  @Test
  fun `l'URL chiede i tre giudici nel punto arrotondato della riga`() {
    val url = TruthRequests.url(TruthRequests.plan(listOf(row(43.832, 11.199))).single())

    assertTrue(url.startsWith(OpenMeteoArchive.BASE_URL))
    assertTrue(url.contains("latitude=43.832&longitude=11.199"))
    assertTrue(url.contains("hourly=precipitation&"))
    assertTrue(url.contains("models=meteofrance_seamless,ukmo_seamless,gem_seamless&"))
  }

  @Test
  fun `una finestra sconosciuta non genera richieste`() {
    assertTrue(TruthRequests.plan(listOf(row(43.832, 11.199, window = "0-12h"))).isEmpty())
  }
}
