package dev.pampa.fluidweather.testbench.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class HistoricalForecastFetcherTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val variables = listOf("precipitation", "pressure_msl")

  private val product = HfProduct(
    name = "prova",
    models = listOf(HfModelRange("mod_a", LocalDate.of(2024, 1, 1))),
    variables = variables,
    locations = listOf(TestLocations[0]),
    to = LocalDate.of(2024, 1, 10),
    chunkDays = 4,
  )

  private fun api(url: String): HttpReply {
    val start = LocalDate.parse(Fixtures.queryParam(url, "start_date"))
    val end = LocalDate.parse(Fixtures.queryParam(url, "end_date"))
    val quarter = url.contains("minutely_15=")
    val names = Fixtures.queryParam(url, if (quarter) "minutely_15" else "hourly").split(",")
    return FetcherHarness.ok(Fixtures.hfCsv(start, end, names, if (quarter) 900L else 3_600L))
  }

  private fun fetcher(harness: FetcherHarness) =
    HistoricalForecastFetcher(harness.http, File(tmp.root, "data")) { harness.logLines += it }

  private fun series(model: String = "mod_a"): HourlySeries =
    HourlySeries.load(File(tmp.root, "data/hf/alfa/$model.csv"))

  @Test
  fun `i pezzi coprono il periodo senza buchi ne' sovrapposizioni`() {
    val chunks = HistoricalForecastFetcher.chunks(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10), 4)
    assertEquals(
      listOf(
        LocalDate.of(2024, 1, 1) to LocalDate.of(2024, 1, 4),
        LocalDate.of(2024, 1, 5) to LocalDate.of(2024, 1, 8),
        LocalDate.of(2024, 1, 9) to LocalDate.of(2024, 1, 10),
      ),
      chunks,
    )

    // Il caso vero: quasi quattro anni a pezzi di un anno.
    val years = HistoricalForecastFetcher.chunks(HfProducts.FIRST_DAY, HfProducts.LAST_DAY, 365)
    assertEquals(4, years.size)
    assertEquals(HfProducts.FIRST_DAY, years.first().first)
    assertEquals(HfProducts.LAST_DAY, years.last().second)
    for (i in 1 until years.size) assertEquals(years[i - 1].second.plusDays(1), years[i].first)
    assertTrue(years.all { (a, b) -> java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1 <= 365 })
  }

  @Test
  fun `una serie e' un file normalizzato con i pezzi riattaccati, e riscaricarla non chiede niente`() {
    val harness = FetcherHarness(tmp.root, ::api)
    val fetcher = fetcher(harness)

    val summary = fetcher.fetch(listOf(product))

    assertEquals(1, summary.downloaded)
    assertEquals(0, summary.failed)
    assertEquals(3, harness.urls.size)
    assertEquals(listOf("2024-01-01", "2024-01-05", "2024-01-09"), harness.urls.map { Fixtures.queryParam(it, "start_date") })
    assertEquals(listOf("2024-01-04", "2024-01-08", "2024-01-10"), harness.urls.map { Fixtures.queryParam(it, "end_date") })
    val first = harness.urls.first()
    assertEquals("43.83", Fixtures.queryParam(first, "latitude"))
    assertEquals("11.2", Fixtures.queryParam(first, "longitude"))
    assertEquals("precipitation,pressure_msl", Fixtures.queryParam(first, "hourly"))
    assertEquals("mod_a", Fixtures.queryParam(first, "models"))
    assertEquals("csv", Fixtures.queryParam(first, "format"))
    assertEquals("unixtime", Fixtures.queryParam(first, "timeformat"))
    assertEquals("UTC", Fixtures.queryParam(first, "timezone"))

    val file = File(tmp.root, "data/hf/alfa/mod_a.csv")
    val lines = file.readLines()
    assertEquals("time,precipitation,pressure_msl", lines.first())
    assertEquals(1 + 10 * 24, lines.size)
    val series = series()
    assertEquals(240, series.size)
    // Contigua: ogni ora esiste, e il valore e' quello della funzione dell'istante (i pezzi combaciano).
    for (i in 0 until series.size) {
      assertEquals(Fixtures.epoch("2024-01-01") * 1_000L + i * 3_600_000L, series.timeAt(i))
      assertEquals(((series.timeAt(i) / 3_600_000L) % 100).toDouble(), series.at(series.timeAt(i), "precipitation")!!, 1e-9)
    }
    assertFalse(File(tmp.root, "data/hf/alfa/mod_a.csv.part").exists())
    assertFalse("i pezzi intermedi vanno via a file completo", File(tmp.root, "data/hf/alfa/mod_a.chunks").exists())

    val again = fetcher.fetch(listOf(product))
    assertEquals(1, again.alreadyPresent)
    assertEquals(3, harness.urls.size)
  }

  @Test
  fun `un'interruzione riprende dal pezzo dopo, non dall'inizio`() {
    var failLast = true
    val harness = FetcherHarness(tmp.root) { url ->
      if (failLast && Fixtures.queryParam(url, "start_date") == "2024-01-09") {
        HttpReply(400, """{"reason":"boom","error":true}""")
      } else {
        api(url)
      }
    }
    val fetcher = fetcher(harness)

    val broken = fetcher.fetch(listOf(product))

    assertEquals(1, broken.failed)
    assertFalse("un file che esiste e' completo", File(tmp.root, "data/hf/alfa/mod_a.csv").exists())
    assertEquals(2, File(tmp.root, "data/hf/alfa/mod_a.chunks").listFiles()!!.size)
    assertEquals(3, harness.urls.size)

    failLast = false
    val resumed = fetcher.fetch(listOf(product))

    assertEquals(1, resumed.downloaded)
    assertEquals("ha chiesto solo il pezzo mancante", 4, harness.urls.size)
    assertEquals("2024-01-09", Fixtures.queryParam(harness.urls.last(), "start_date"))
    assertEquals(240, series().size)
  }

  @Test
  fun `un errore di streaming dentro un 200 non finisce nel file, si riprova`() {
    var first = true
    val harness = FetcherHarness(tmp.root) { url ->
      if (first) {
        first = false
        FetcherHarness.ok("Unexpected error while streaming data: timeoutReached")
      } else {
        api(url)
      }
    }

    val summary = fetcher(harness).fetch(listOf(product))

    assertEquals(1, summary.downloaded)
    assertEquals(0, summary.failed)
    assertEquals(4, harness.urls.size)
    assertEquals(240, series().size)
  }

  @Test
  fun `un pezzo con meno righe del dovuto e' un fallimento, non un file monco`() {
    val harness = FetcherHarness(tmp.root) { url ->
      // Risponde con un giorno in meno di quello chiesto.
      val end = LocalDate.parse(Fixtures.queryParam(url, "end_date")).minusDays(1)
      FetcherHarness.ok(Fixtures.hfCsv(LocalDate.parse(Fixtures.queryParam(url, "start_date")), end, variables))
    }

    val summary = fetcher(harness).fetch(listOf(product))

    assertEquals(1, summary.failed)
    assertFalse(File(tmp.root, "data/hf/alfa/mod_a.csv").exists())
  }

  @Test
  fun `il quarto d'ora va in hf15 con il suo meta`() {
    val quarter = product.copy(
      name = "quarto",
      variables = listOf("precipitation"),
      resolution = HfResolution.QUARTER_HOURLY,
      chunkDays = 2,
      to = LocalDate.of(2024, 1, 3),
    )
    val harness = FetcherHarness(tmp.root, ::api)

    val summary = fetcher(harness).fetch(listOf(quarter))

    assertEquals(1, summary.downloaded)
    assertTrue(Fixtures.queryParam(harness.urls.first(), "minutely_15") == "precipitation")
    val file = File(tmp.root, "data/hf15/alfa/mod_a.csv")
    assertEquals(1 + 3 * 96, file.readLines().size)
    assertTrue(File(tmp.root, "data/hf15/alfa/mod_a.meta.txt").readText().contains("righe,288"))
    assertEquals(288, HourlySeries.load(file).size)
  }

  @Test
  fun `le ore non ancora assestate non si scaricano`() {
    val harness = FetcherHarness(tmp.root, ::api)
    val fetcher = HistoricalForecastFetcher(harness.http, File(tmp.root, "data"), { harness.now }) { harness.logLines += it }
    val today = java.time.Instant.ofEpochMilli(harness.now).atOffset(java.time.ZoneOffset.UTC).toLocalDate()

    // Fino a ieri: l'ultimo slot chiude a mezzanotte e le 24 ore di finalita' non sono passate.
    val yesterday = product.copy(models = listOf(HfModelRange("mod_a", today.minusDays(1))), to = today.minusDays(1))
    val refused = fetcher.fetch(listOf(yesterday))
    assertEquals(1, refused.failed)
    assertEquals(0, refused.downloaded)
    assertTrue("nessuna richiesta per ore che sono ancora previsioni", harness.urls.isEmpty())
    assertFalse(File(tmp.root, "data/hf/alfa/mod_a.csv").exists())
    assertTrue(harness.logLines.any { "RIFIUTATO" in it })

    // Fino all'altro ieri: assestato (mezzanotte di ieri + 24 h <= adesso).
    val settled = product.copy(models = listOf(HfModelRange("mod_a", today.minusDays(2))), to = today.minusDays(2))
    assertEquals(1, fetcher.fetch(listOf(settled)).downloaded)
    assertEquals(24, series().size)
  }

  @Test
  fun `un pezzo salvato per un altro intervallo non si riusa`() {
    // Il primo tentativo si ferma al secondo pezzo: resta su disco il pezzo 1-4 gennaio.
    var failFrom5 = true
    val harness = FetcherHarness(tmp.root) { url ->
      if (failFrom5 && Fixtures.queryParam(url, "start_date") == "2024-01-05") HttpReply(400, """{"reason":"boom","error":true}""") else api(url)
    }
    fetcher(harness).fetch(listOf(product))
    assertEquals(1, File(tmp.root, "data/hf/alfa/mod_a.chunks").listFiles()!!.size)

    // Ora il prodotto finisce il 3: il pezzo 1-3 non e' il pezzo 1-4, e il file non ha il giorno in piu'.
    failFrom5 = false
    val shorter = product.copy(to = LocalDate.of(2024, 1, 3))
    assertEquals(1, fetcher(harness).fetch(listOf(shorter)).downloaded)
    assertEquals("2024-01-03", Fixtures.queryParam(harness.urls.last(), "end_date"))
    assertEquals(72, series().size)
  }

  @Test
  fun `il pannello scaricato e' quello della verita'`() {
    assertEquals(dev.pampa.fluidweather.nowcast.truth.TruthPanel.MODELS, HfProducts.PANEL.models.map { it.model })
    assertEquals(
      dev.pampa.fluidweather.nowcast.truth.TruthPanel.AVAILABLE_FROM_MILLIS,
      HfProducts.FIRST_DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
  }

  @Test
  fun `i preset dicono quello che la sonda ha stabilito`() {
    assertEquals(3, HfProducts.PANEL.models.size)
    assertEquals(10, HfProducts.PANEL.locations.size)
    assertEquals(listOf("precipitation", "pressure_msl"), HfProducts.PANEL.variables)
    assertTrue(HfProducts.PANEL.models.all { it.from == LocalDate.of(2022, 11, 24) })

    assertEquals(10, HfProducts.CONTEXT.variables.size)
    assertEquals(listOf("best_match"), HfProducts.CONTEXT.models.map { it.model })

    assertEquals(LocalDate.of(2024, 2, 3), HfProducts.PROVIDERS.models.single { it.model == "ecmwf_ifs025" }.from)
    assertEquals(LocalDate.of(2022, 11, 24), HfProducts.PROVIDERS.models.single { it.model == "icon_seamless" }.from)

    assertEquals(HfResolution.QUARTER_HOURLY, HfProducts.MINUTELY.resolution)
    assertEquals(
      setOf("sesto-fiorentino", "milano", "genova", "innsbruck"),
      HfProducts.MINUTELY.locations.map { it.name }.toSet(),
    )
    assertEquals(3, HfProducts.byName("all")!!.size)
    assertEquals(null, HfProducts.byName("boh"))
    // Un anno a richiesta pesa 27 a modello per localita': il conto del budget parte da qui.
    assertEquals(27, CallBudget.weightOf(HfProducts.PANEL.variables.size, 365))
  }
}
