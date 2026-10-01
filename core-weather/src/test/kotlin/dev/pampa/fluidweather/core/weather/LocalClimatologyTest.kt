package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.nowcast.climatology.LocalBaselines
import dev.pampa.fluidweather.nowcast.climatology.WindowClimatology
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La climatologia del posto: una cella, due richieste, sei mesi di vita, e mai un tentativo al
 * giro quando la rete non c'e'.
 */
class LocalClimatologyTest {

  private val day = 86_400_000L

  /** Tre giorni di storia: pioggia al pomeriggio del secondo giorno, per tutti i giudici. */
  private val times = (1..72).map { MIDNIGHT + it * HOUR }
  private fun rain(time: Long): Double = if (time in MIDNIGHT + 36 * HOUR..MIDNIGHT + 40 * HOUR) 1.2 else 0.0

  private val precipitationReply = archiveJson(
    times,
    (TruthPanel.MODELS + LocalClimatology.BEST_MATCH).associate { model -> "precipitation_$model" to times.map { rain(it) } },
  )
  private val pressureReply = archiveJson(times, mapOf("pressure_msl" to times.map { 1015.0 - (it - MIDNIGHT) / HOUR * 0.05 }))

  private fun reply(url: String): String = if ("hourly=pressure_msl" in url) pressureReply else precipitationReply

  private inner class Bench {
    val records = InMemoryLocalClimatologyRecords()
    var now = MIDNIGHT + 400 * day
    val http = RecordingHttp { reply(it) }
    val climatology = LocalClimatology(providerHttpOf(http) { now }, records) { now }
  }

  @Test
  fun `la cella e il suo centro`() {
    assertEquals("c175_44", LocalClimatology.cellKeyOf(43.832, 11.199))
    assertEquals(43.875 to 11.125, LocalClimatology.cellCenterOf(43.832, 11.199))
    // A sud e a ovest il pavimento scende, non si avvicina allo zero.
    assertEquals("c-136_-234", LocalClimatology.cellKeyOf(-33.9, -58.4))
    assertEquals(-33.875 to -58.375, LocalClimatology.cellCenterOf(-33.9, -58.4))
    // Un bordo esatto sta nella cella che comincia li'.
    assertEquals("c175_44", LocalClimatology.cellKeyOf(43.75, 11.0))
  }

  @Test
  fun `si costruisce dai giudici, si conserva codificata e si rilegge uguale`() = runTest {
    val bench = Bench()

    assertTrue(bench.climatology.ensure(43.832, 11.199))

    // Due richieste all'archivio, al centro della cella, sui due anni che finiscono una settimana fa.
    assertEquals(2, bench.http.urls.size)
    val precipitation = bench.http.urls[0]
    assertTrue(precipitation.contains("latitude=43.875&longitude=11.125"))
    assertTrue(precipitation.contains("hourly=precipitation&models=meteofrance_seamless,ukmo_seamless,gem_seamless,best_match&"))
    // Adesso e' il 2027-10-06: fine sette giorni prima, inizio 730 giorni (inclusi) prima della fine.
    assertTrue(precipitation.contains("start_date=2025-09-30&end_date=2027-09-29"))
    assertTrue(bench.http.urls[1].contains("hourly=pressure_msl&models=meteofrance_seamless&"))

    val record = bench.records.get("c175_44")!!
    val truth = TruthPanel.combine(TruthPanel.MODELS.associateWith { times.associateWith { rain(it) } })
    val expected = WindowClimatology.build(truth, 11.125)!!
    assertEquals(expected.encode(), record.climatology)
    assertEquals(bench.now, record.builtAtMillis)

    val cached = bench.climatology.cached(43.84, 11.2)!!
    assertEquals("c175_44", cached.cellKey)
    assertEquals(record.climatology, cached.climatology.encode())
    assertEquals(record.baselines, cached.baselines.encode())
    assertEquals(LocalBaselines.decode(record.baselines, cached.climatology)!!.encode(), cached.baselines.encode())
  }

  @Test
  fun `senza climatologia su disco il registratore non ne vede e non va in rete`() = runTest {
    val bench = Bench()

    assertNull(bench.climatology.cached(43.832, 11.199))
    assertTrue(bench.http.urls.isEmpty())
  }

  @Test
  fun `si rifa' dopo centottanta giorni, non prima`() = runTest {
    val bench = Bench()
    val built = bench.now
    bench.climatology.ensure(43.832, 11.199)

    bench.now = built + 179 * day
    assertFalse(bench.climatology.ensure(43.832, 11.199))
    assertEquals(2, bench.http.urls.size)

    bench.now = built + 181 * day
    assertTrue(bench.climatology.ensure(43.832, 11.199))
    assertEquals(4, bench.http.urls.size)
    assertEquals(bench.now, bench.records.get("c175_44")!!.builtAtMillis)
  }

  @Test
  fun `una cella che non si sa piu' leggere si rifa' subito`() = runTest {
    // Il formato e' cambiato con un aggiornamento: il prefisso "WC1" non e' piu' quello atteso.
    val bench = Bench()
    bench.climatology.ensure(43.832, 11.199)
    val old = bench.records.get("c175_44")!!
    bench.records.put(old.copy(climatology = "WC0|vecchio"))
    assertNull(bench.climatology.cached(43.832, 11.199))

    bench.now += day
    assertTrue(bench.climatology.ensure(43.832, 11.199))
    assertNotNull(bench.climatology.cached(43.832, 11.199))
  }

  @Test
  fun `dopo un fallimento non si riprova per un giorno`() = runTest {
    val bench = Bench()
    bench.http.respond = { error("niente rete") }
    val failedAt = bench.now

    assertFalse(bench.climatology.ensure(43.832, 11.199))
    assertEquals(failedAt, bench.records.lastFailureMillis("c175_44"))
    val attempts = bench.http.urls.size

    bench.now = failedAt + 23 * HOUR
    assertFalse(bench.climatology.ensure(43.832, 11.199))
    assertEquals("dentro le 24 ore non si tocca la rete", attempts, bench.http.urls.size)

    bench.http.respond = { reply(it) }
    bench.now = failedAt + 25 * HOUR
    assertTrue(bench.climatology.ensure(43.832, 11.199))
    assertNull("una riuscita cancella il fallimento", bench.records.lastFailureMillis("c175_44"))
  }

  @Test
  fun `un download interrotto a meta' conta come tentativo del giorno`() = runTest {
    // Il tetto di tempo del ciclo in background cancella il lavoro mentre scarica: prima il
    // fallimento si segnava solo dopo, cioe' mai, e ogni ora ripartivano due anni di storia.
    val bench = Bench()
    val hanging = object : EngineHttp() {
      val urls = mutableListOf<String>()
      override suspend fun readText(url: String, headers: Map<String, String>): String {
        urls += url
        awaitCancellation()
      }
    }
    val climatology = LocalClimatology(providerHttpOf(hanging) { bench.now }, bench.records) { bench.now }
    val startedAt = bench.now

    assertNull(withTimeoutOrNull(3 * 60_000L) { climatology.ensure(43.832, 11.199) })
    assertEquals(1, hanging.urls.size)
    assertEquals(startedAt, bench.records.lastFailureMillis("c175_44"))

    bench.now = startedAt + HOUR
    assertFalse(climatology.ensure(43.832, 11.199))
    assertEquals("un'ora dopo non si riparte", 1, hanging.urls.size)
  }

  @Test
  fun `una risposta senza pressione e' un fallimento, non una baseline a meta'`() = runTest {
    val bench = Bench()
    bench.http.respond = { url -> if ("hourly=pressure_msl" in url) archiveJson(times, emptyMap()) else precipitationReply }

    assertFalse(bench.climatology.ensure(43.832, 11.199))
    assertNull(bench.records.get("c175_44"))
    assertNotNull(bench.records.lastFailureMillis("c175_44"))
  }

  @Test
  fun `al piu' tre celle - esce quella usata meno di recente`() = runTest {
    val bench = Bench()
    val start = bench.now
    bench.climatology.ensure(43.832, 11.199) // A
    bench.now = start + day
    bench.climatology.ensure(44.5, 11.3) // B
    bench.now = start + 2 * day
    bench.climatology.ensure(45.1, 9.2) // C
    bench.now = start + 3 * day
    // A si rilegge: e' la cella di casa, non deve uscire.
    assertNotNull(bench.climatology.cached(43.832, 11.199))
    bench.now = start + 4 * day
    bench.climatology.ensure(41.9, 12.5) // D

    val cells = bench.records.all().map { it.cellKey }.toSet()
    assertEquals(3, cells.size)
    assertTrue(LocalClimatology.cellKeyOf(43.832, 11.199) in cells)
    assertFalse("B era la meno usata", LocalClimatology.cellKeyOf(44.5, 11.3) in cells)
    assertTrue(LocalClimatology.cellKeyOf(41.9, 12.5) in cells)
  }
}
