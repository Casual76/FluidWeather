package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.core.model.RainEventVerification
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La classifica pioggia: Brier, stessi giri per tutti, incertezza a blocchi di giorni.
 *
 * I giri finti sono cinque al giorno per otto giorni (quaranta casi): abbastanza per una posizione
 * in classifica, e con un esito che si alterna cosi' ogni riga ha bagnati e asciutti.
 */
class RainBoardTest {

  private val day = 86_400_000L
  private val now = MIDNIGHT + 30 * day

  private fun verification(
    providerId: String,
    roundId: Long,
    probability: Double,
    rained: Boolean,
    window: String = "1-3h",
    modelVersion: String = ModelVersions.TAG,
  ) = RainEventVerification(
    prediction = RainEventPending("gps", 43.832, 11.199, roundId, providerId, window, roundId, probability, modelVersion, "FRESH"),
    rained = rained,
    truthSumMm = if (rained) 1.0 else 0.0,
    truthVoters = 3,
    truthSource = TruthPanel.VERSION,
    settledAtMillis = roundId + 2 * day,
  )

  /** [days] giorni prima di adesso, [perDay] giri al giorno: gli istanti dei giri. */
  private fun rounds(days: Int = 8, perDay: Int = 5): List<Long> =
    (0 until days).flatMap { d -> (0 until perDay).map { r -> now - (d + 1) * day + r * 3 * HOUR } }

  private fun rainedAt(index: Int) = index % 3 == 0

  /** Una riga per giro con la probabilita' data dall'indice del giro e dall'esito. */
  private fun row(providerId: String, roundIds: List<Long>, probability: (index: Int, rained: Boolean) -> Double) =
    roundIds.mapIndexed { i, id -> verification(providerId, id, probability(i, rainedAt(i)), rainedAt(i)) }

  private fun board(verifications: List<RainEventVerification>, hasBarometer: Boolean = true) =
    RainBoard.build(verifications, now, hasBarometer)

  @Test
  fun `in testa chi ha il Brier piu' basso, e i riferimenti stanno in classifica`() {
    val ids = rounds()
    val report = board(
      row(RainBoardIds.BAROMETER, ids) { _, wet -> if (wet) 0.7 else 0.2 } +
        row(ProviderRegistry.OPEN_METEO, ids) { _, wet -> if (wet) 0.1 else 0.6 } +
        row(RainBoardIds.ALWAYS_ZERO, ids) { _, _ -> 0.0 },
    )

    val window = report.windows.single { it.window == "1-3h" }
    assertEquals(RainBoardIds.BAROMETER, window.anchorId)
    assertEquals(40, window.rounds)
    assertEquals(8, window.days)
    assertEquals(listOf(RainBoardIds.BAROMETER, RainBoardIds.ALWAYS_ZERO, ProviderRegistry.OPEN_METEO), window.rows.map { it.providerId })
    assertEquals(listOf(1, 2, 3), window.rows.map { it.rank })
    assertEquals(RainRowKind.REFERENCE, window.rows[1].kind)
    // Brier del barometro: 14 bagnati a (0,3)², 26 asciutti a (0,2)².
    assertEquals((14 * 0.09 + 26 * 0.04) / 40, window.rows[0].brier, 1e-9)
    assertEquals((14 * 0.3 + 26 * 0.2) / 40, window.rows[0].mae, 1e-9)
    // Le altre finestre ci sono anche vuote: la pagina sa dire "niente ancora" per ognuna.
    assertEquals(listOf("0-1h", "1-3h", "3-6h"), report.windows.map { it.window })
    assertTrue(report.windows.first().rows.isEmpty())
    assertEquals(40, report.totalCases)
  }

  @Test
  fun `a parita' di Brier decide la MAE`() {
    val ids = rounds()
    // Tutti asciutti: 0,5 fisso vale Brier 0,25 e MAE 0,5; "1 una volta su quattro, se no 0"
    // vale lo stesso Brier 0,25 ma MAE 0,25.
    val dry = ids.map { verification(RainBoardIds.BAROMETER, it, 0.1, false) } +
      ids.map { verification("tentenna", it, 0.5, false) } +
      ids.mapIndexed { i, id -> verification("netto", id, if (i % 4 == 0) 1.0 else 0.0, false) }

    val rows = board(dry).windows.single { it.window == "1-3h" }.rows

    assertEquals(rows.single { it.providerId == "tentenna" }.brier, rows.single { it.providerId == "netto" }.brier, 1e-12)
    assertTrue(rows.indexOfFirst { it.providerId == "netto" } < rows.indexOfFirst { it.providerId == "tentenna" })
  }

  @Test
  fun `i giri senza il barometro non contano per nessuno`() {
    val ids = rounds()
    val extra = (1..10).map { now - it * HOUR / 2 }
    val report = board(
      row(RainBoardIds.BAROMETER, ids) { _, _ -> 0.3 } +
        row(ProviderRegistry.OPEN_METEO, ids + extra) { _, _ -> 0.3 },
    )

    val openMeteo = report.windows.single { it.window == "1-3h" }.rows.single { it.providerId == ProviderRegistry.OPEN_METEO }
    assertEquals(40, openMeteo.cases)
  }

  @Test
  fun `fuori la versione vecchia, fuori i giri di piu' di sessanta giorni, fuori i giudici`() {
    val ids = rounds()
    val old = now - RainBoard.WINDOW_MILLIS - HOUR
    val report = board(
      row(RainBoardIds.BAROMETER, ids) { _, _ -> 0.3 } +
        verification(RainBoardIds.BAROMETER, old, 0.3, true) +
        ids.map { verification(ProviderRegistry.OPEN_METEO, it, 0.3, false, modelVersion = "v1+panel-0+board-1") } +
        row(ProviderRegistry.OPEN_METEO_AROME, ids) { _, wet -> if (wet) 1.0 else 0.0 },
    )

    val window = report.windows.single { it.window == "1-3h" }
    assertEquals(40, window.rounds)
    assertEquals(listOf(RainBoardIds.BAROMETER), window.rows.map { it.providerId })
  }

  @Test
  fun `ventinove casi o quattro giorni sono pochi dati - si mostrano senza posizione`() {
    val ids = rounds()
    // Quaranta giri in soli quattro giorni, nel pomeriggio (i giri di [rounds] finiscono alle 12).
    val fourDays = (0 until 4).flatMap { d -> (0 until 10).map { r -> now - (d + 1) * day + 13 * HOUR + r * 60_000L } }
    val report = board(
      row(RainBoardIds.BAROMETER, ids + fourDays) { _, _ -> 0.3 } +
        row("ventinove", ids.take(29)) { _, wet -> if (wet) 0.9 else 0.1 } +
        row("quattro-giorni", fourDays) { _, _ -> 0.0 },
    )

    val rows = report.windows.single { it.window == "1-3h" }.rows
    val few29 = rows.single { it.providerId == "ventinove" }
    val few4 = rows.single { it.providerId == "quattro-giorni" }
    assertTrue(few29.fewData && few29.rank == null)
    assertEquals(29, few29.cases)
    assertTrue(few4.fewData && few4.rank == null)
    assertEquals(4, few4.days)
    // Chi ha pochi dati sta in fondo anche se il suo Brier e' migliore.
    assertEquals(RainBoardIds.BAROMETER, rows.first().providerId)
    assertEquals(1, rows.first().rank)
  }

  @Test
  fun `l'ombra sta a parte, sugli stessi giri`() {
    val ids = rounds()
    val report = board(
      row(RainBoardIds.BAROMETER, ids) { _, _ -> 0.3 } +
        row(RainBoardIds.BAROMETER_SOLO, ids) { _, _ -> 0.2 },
    )

    val window = report.windows.single { it.window == "1-3h" }
    assertEquals(listOf(RainBoardIds.BAROMETER), window.rows.map { it.providerId })
    val shadow = window.shadows.single()
    assertEquals(RainBoardIds.BAROMETER_SOLO, shadow.providerId)
    assertEquals(RainRowKind.SHADOW, shadow.kind)
    assertNull(shadow.rank)
    assertEquals(40, shadow.cases)
  }

  @Test
  fun `senza barometro l'ancora e' la climatologia`() {
    val ids = rounds()
    val report = board(
      row(RainBoardIds.CLIMATOLOGY, ids.take(35)) { _, _ -> 0.3 } +
        row(ProviderRegistry.OPEN_METEO, ids) { _, _ -> 0.4 } +
        row(RainBoardIds.ALWAYS_ZERO, ids) { _, _ -> 0.0 },
      hasBarometer = false,
    )

    val window = report.windows.single { it.window == "1-3h" }
    assertEquals(RainBoardIds.CLIMATOLOGY, report.anchorId)
    assertEquals(35, window.rounds)
    assertTrue(window.rows.all { it.cases == 35 })
    assertNull(window.rows.single { it.providerId == RainBoardIds.CLIMATOLOGY }.deltaVsAnchor)
  }

  @Test
  fun `l'intervallo contiene la media, e chi e' meglio in ogni caso ha il delta sotto zero`() {
    val ids = rounds()
    val report = board(
      row(RainBoardIds.BAROMETER, ids) { _, wet -> if (wet) 0.6 else 0.3 } +
        row(ProviderRegistry.OPEN_METEO, ids) { _, wet -> if (wet) 0.8 else 0.1 },
    )

    val rows = report.windows.single { it.window == "1-3h" }.rows
    rows.forEach { assertTrue(it.brierLow <= it.brier && it.brier <= it.brierHigh) }
    assertTrue(rows.all { it.halfWidth >= 0.0 })
    val delta = rows.single { it.providerId == ProviderRegistry.OPEN_METEO }.deltaVsAnchor
    assertNotNull(delta)
    assertTrue("migliore su ogni giro: tutto l'intervallo sotto zero", delta!!.high < 0.0)
    assertNull(rows.single { it.providerId == RainBoardIds.BAROMETER }.deltaVsAnchor)
  }

  @Test
  fun `stessi dati, stessa classifica - anche mescolati`() {
    val ids = rounds()
    val data = row(RainBoardIds.BAROMETER, ids) { i, wet -> if (wet) 0.5 + i * 0.005 else 0.2 } +
      row(ProviderRegistry.OPEN_METEO, ids) { i, _ -> (i % 7) / 10.0 }

    val first = board(data)
    val second = board(data.shuffled(java.util.Random(7)))

    assertEquals(first, second)
    assertFalse(first.windows.single { it.window == "1-3h" }.rows.isEmpty())
  }
}
