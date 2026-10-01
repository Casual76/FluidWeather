package dev.pampa.fluidweather.testbench.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class SingleRunFetcherTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun init(day: String, hour: Int): Instant = Instant.ofEpochSecond(Fixtures.epoch(day, hour))

  private val preset = RunPreset("m", init("2026-04-02", 0), init("2026-04-02", 18))

  /** Risponde come l'API: la corsa delle 06 non c'e', le altre si'. */
  private fun api(url: String): HttpReply = when (val run = Fixtures.queryParam(url, "run")) {
    "2026-04-02T06:00" -> FetcherHarness.notAvailable()
    else -> FetcherHarness.ok(Fixtures.runCsv(2, Fixtures.epoch(run.substringBefore('T'), run.substringAfter('T').substringBefore(':').toInt()), 24))
  }

  private fun fetcher(harness: FetcherHarness, now: Long = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()) =
    SingleRunFetcher(harness.http, File(tmp.root, "runs"), TestLocations, { now }, { harness.logLines += it })

  @Test
  fun `una richiesta per corsa su tutte le localita', salva, annota le mancanti e non rifa nulla`() {
    val harness = FetcherHarness(tmp.root, ::api)
    val fetcher = fetcher(harness)

    val summary = fetcher.fetch(preset)

    assertEquals(3, summary.downloaded)
    assertEquals(1, summary.newlyMissing)
    assertEquals(0, summary.failed)
    assertEquals(4, harness.urls.size)

    // L'URL: tutte le localita' insieme, 24 ore, solo variabili deterministiche, CSV in UTC.
    val url = harness.urls.first()
    assertEquals("43.83,45.46", Fixtures.queryParam(url, "latitude"))
    assertEquals("11.2,9.19", Fixtures.queryParam(url, "longitude"))
    assertEquals("precipitation,pressure_msl", Fixtures.queryParam(url, "hourly"))
    assertEquals("m", Fixtures.queryParam(url, "models"))
    assertEquals("24", Fixtures.queryParam(url, "forecast_hours"))
    assertEquals("csv", Fixtures.queryParam(url, "format"))
    assertEquals("UTC", Fixtures.queryParam(url, "timezone"))
    assertEquals("unixtime", Fixtures.queryParam(url, "timeformat"))
    assertFalse("la PoP non esiste nelle corse passate", url.contains("probability"))

    val runs = File(tmp.root, "runs")
    assertTrue(File(runs, "m/2026-04/2026040200.csv").exists())
    assertFalse(File(runs, "m/2026-04/2026040206.csv").exists())
    assertTrue(File(runs, "m/2026-04/2026040212.csv").exists())
    assertTrue(File(runs, "m/2026-04/2026040218.csv").exists())
    assertEquals(listOf("2026040206"), File(runs, "m/missing.txt").readLines())
    // La risposta e' salvata com'e' arrivata, location_id compreso.
    assertTrue(File(runs, "m/2026-04/2026040200.csv").readText().contains("location_id,time,precipitation (mm)"))
    assertEquals("0,alfa,43.83,11.2\n1,beta,45.46,9.19\n", File(runs, "locations.txt").readText())
    assertTrue("nessun .part rimasto", runs.walkTopDown().none { it.name.endsWith(".part") })

    // Riprendendo non si chiede piu' niente: ne' le corse salvate, ne' quella nota come mancante.
    val again = fetcher.fetch(preset)
    assertEquals(4, harness.urls.size)
    assertEquals(3, again.alreadyPresent)
    assertEquals(1, again.knownMissing)
    assertEquals(0, again.downloaded)
  }

  @Test
  fun `una corsa appena uscita non si marchia come mancante`() {
    val harness = FetcherHarness(tmp.root, ::api)
    // "Adesso" e' un'ora dopo l'emissione: la corsa e' in ritardo, non assente.
    val now = preset.firstInit.toEpochMilli() + 3_600_000L
    val summary = fetcher(harness, now).fetch(RunPreset("m", preset.firstInit, preset.firstInit))

    assertEquals(1, summary.tooRecent)
    assertEquals(0, harness.urls.size)
    assertFalse(File(tmp.root, "runs/m/missing.txt").exists())
  }

  @Test
  fun `una risposta senza tutte le localita' non si salva`() {
    val harness = FetcherHarness(tmp.root) {
      FetcherHarness.ok(Fixtures.runCsv(1, Fixtures.epoch("2026-04-02", 0), 24))
    }
    val summary = fetcher(harness).fetch(RunPreset("m", preset.firstInit, preset.firstInit))

    assertEquals(1, summary.failed)
    assertEquals(0, summary.downloaded)
    assertFalse(File(tmp.root, "runs/m/2026-04/2026040200.csv").exists())
  }

  @Test
  fun `un 400 con un'altra ragione e' un fallimento, non una corsa mancante`() {
    val harness = FetcherHarness(tmp.root) {
      HttpReply(400, """{"reason":"Cannot initialize SurfacePressureVariable from invalid String value precipitation_probability","error":true}""")
    }
    val summary = fetcher(harness).fetch(RunPreset("m", preset.firstInit, preset.firstInit))

    assertEquals(1, summary.failed)
    assertEquals(0, summary.newlyMissing)
    assertFalse(File(tmp.root, "runs/m/missing.txt").exists())
  }

  @Test
  fun `il limite ferma il fetch e from e to restringono il preset`() {
    val harness = FetcherHarness(tmp.root, ::api)
    val summary = fetcher(harness).fetch(preset, from = init("2026-04-02", 12), to = init("2026-04-02", 18), limit = 1)

    assertEquals(1, summary.downloaded)
    assertEquals(1, harness.urls.size)
    assertEquals("2026-04-02T12:00", Fixtures.queryParam(harness.urls.single(), "run"))
  }

  @Test
  fun `un ordine delle localita' diverso da quello gia' scritto ferma tutto`() {
    val runs = File(tmp.root, "runs")
    Fixtures.write(File(runs, "locations.txt"), "0,beta,45.46,9.19\n1,alfa,43.83,11.2\n")
    val harness = FetcherHarness(tmp.root, ::api)

    val failure = runCatching { fetcher(harness).fetch(preset) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals(0, harness.urls.size)
  }

  @Test
  fun `un errore di rete si riprova, un 429 si aspetta, e tutto passa dal budget`() {
    var calls = 0
    val harness = FetcherHarness(tmp.root) { url ->
      calls++
      when (calls) {
        1 -> FetcherHarness.io()
        2 -> HttpReply(429, """{"reason":"Minutely API request limit exceeded. Please try again in the next minute.","error":true}""")
        3 -> HttpReply(503, "giu'")
        else -> api(url)
      }
    }
    val summary = fetcher(harness).fetch(RunPreset("m", preset.firstInit, preset.firstInit))

    assertEquals(1, summary.downloaded)
    assertEquals(4, harness.urls.size)
    // Attese: rete (15 s), 429 al minuto (65 s), 503 (backoff cresciuto: 60 s).
    assertEquals(listOf(15_000L, 65_000L, 60_000L), harness.sleeps)
    // Ogni tentativo e' una chiamata del budget: 4 tentativi x peso 2 (due localita').
    assertEquals(4, harness.budgetFile.readLines().size)
    assertTrue(harness.budgetFile.readLines().all { it.endsWith(",2") })
  }

  @Test
  fun `un 200 con l'errore di streaming nel corpo si riprova come un 5xx`() {
    var calls = 0
    val harness = FetcherHarness(tmp.root) { url ->
      calls++
      if (calls == 1) {
        // Meta' CSV e poi l'errore: lo stato era gia' partito.
        FetcherHarness.ok(Fixtures.runCsv(2, Fixtures.epoch("2026-04-02", 0), 5) + "Unexpected error while streaming data: timeoutReached")
      } else {
        api(url)
      }
    }
    val summary = fetcher(harness).fetch(RunPreset("m", preset.firstInit, preset.firstInit))

    assertEquals(1, summary.downloaded)
    assertEquals(2, harness.urls.size)
    assertEquals(listOf(15_000L), harness.sleeps)
    assertFalse(File(tmp.root, "runs/m/2026-04/2026040200.csv").readText().contains("streaming"))
  }

  @Test
  fun `un 200 con modelRunUnavailable nel corpo e' una corsa mancante, non un errore da ritentare`() {
    val harness = FetcherHarness(tmp.root) { _ ->
      FetcherHarness.ok("Unexpected error while streaming data: modelRunUnavailable(model: App.DomainRegistry.ecmwf_ifs)")
    }
    val summary = fetcher(harness).fetch(RunPreset("m", preset.firstInit, preset.firstInit))

    assertEquals(0, summary.downloaded)
    assertEquals(1, summary.newlyMissing)
    assertEquals(0, summary.failed)
    assertEquals(1, harness.urls.size)
    assertTrue("nessuna attesa: non si riprova", harness.sleeps.isEmpty())
    assertEquals(listOf("2026040200"), File(tmp.root, "runs/m/missing.txt").readLines())
  }

  @Test
  fun `una risposta troncata con l'ultima localita' corta non si salva`() {
    val harness = FetcherHarness(tmp.root) {
      FetcherHarness.ok(Fixtures.runCsv(2, Fixtures.epoch("2026-04-02", 0), 24).trimEnd().lines().dropLast(3).joinToString("\n"))
    }
    val summary = fetcher(harness).fetch(RunPreset("m", preset.firstInit, preset.firstInit))

    assertEquals(1, summary.failed)
    assertFalse(File(tmp.root, "runs/m/2026-04/2026040200.csv").exists())
  }

  @Test
  fun `la sonda stampa il formato e non salva niente`() {
    val harness = FetcherHarness(tmp.root, ::api)
    fetcher(harness).probe(listOf(preset), preset.firstInit)

    val text = harness.logLines.joinToString("\n")
    assertTrue(text, text.contains("HTTP 200"))
    assertTrue(text, text.contains("location_id presente"))
    assertTrue(text, text.contains("forecast_hours ha funzionato (24 righe)"))
    assertTrue(text, text.contains("ora 0 nulla in 2/2"))
    assertFalse(File(tmp.root, "runs/m").exists())
  }
}
