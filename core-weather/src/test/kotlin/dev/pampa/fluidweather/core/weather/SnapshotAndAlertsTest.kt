package dev.pampa.fluidweather.core.weather

import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.model.Contribution
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.OfficialAlertSource
import dev.pampa.fluidweather.core.model.WeatherKind
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAndAlertsTest {

  private val now = 1_781_431_200_000L

  private fun snapshot(): WeatherSnapshot {
    val hours = (0..3).map { h ->
      FusedHour(
        timestampMillis = now + h * 3_600_000L,
        values = mapOf(
          FusionVariables.TEMPERATURE to FusedValue(
            20.0 + h,
            listOf(Contribution("open-meteo", 0.6, 20.5 + h), Contribution("met-norway", 0.4, 19.25 + h)),
          ),
          FusionVariables.PRECIP_PROBABILITY to FusedValue(10.0 * h, emptyList()),
        ),
        kind = if (h == 2) WeatherKind.RAIN else WeatherKind.PARTLY_CLOUDY,
        windDirectionDeg = if (h == 0) 210.0 else null,
      )
    }
    val context = ForecastBundle(
      providerId = "open-meteo",
      fetchedAtMillis = now,
      latitude = 43.832,
      longitude = 11.199,
      hourly = listOf(
        HourlyPoint(now - 3_600_000L, temperatureC = 19.0, relativeHumidityPercent = 70.0, precipitationMm = 0.0, windDirectionDeg = 200.0),
        HourlyPoint(now, temperatureC = 20.0, dewPointC = 12.0, cloudCoverPercent = 40.0, windSpeedKmh = 12.0, kind = WeatherKind.CLEAR),
      ),
      minutely = listOf(
        dev.pampa.fluidweather.core.model.MinutePoint(now - 15 * 60_000L, precipitationMm = 0.4, precipitationProbabilityPercent = 80.0),
        dev.pampa.fluidweather.core.model.MinutePoint(now, precipitationMm = 0.1),
      ),
    )
    return WeatherSnapshot(
      placeKey = WeatherSnapshot.GPS_KEY,
      latitude = 43.832,
      longitude = 11.199,
      fetchedAtMillis = now,
      fetches = listOf(ProviderFetchSummary("open-meteo", 246, null), ProviderFetchSummary("nws", 0, "fuori copertura")),
      fused = FusedForecast(hours, mapOf("open-meteo" to 0.6, "met-norway" to 0.4)),
      context = context,
      predictionsRegisteredAtMillis = now - 10 * 60_000L,
    )
  }

  @Test
  fun `l'istantanea sopravvive intera al giro per il JSON`() {
    val original = snapshot()
    val decoded = WeatherSnapshotCodec.decode(WeatherSnapshotCodec.encode(original))!!
    assertEquals(original, decoded)
    assertEquals(1, decoded.providersResponding)
    assertTrue(decoded.distanceKmTo(43.832, 11.199) < 0.001)
    assertTrue(decoded.distanceKmTo(43.90, 11.199) > 7.0)
  }

  @Test
  fun `il quarto d'ora sopravvive al giro, e la sua assenza non rompe niente`() {
    val original = snapshot()
    val decoded = WeatherSnapshotCodec.decode(WeatherSnapshotCodec.encode(original))!!
    assertEquals(2, decoded.context!!.minutely.size)
    // Il punto marca la FINE del suo quarto d'ora: quello stampato "adesso" copre gli ultimi
    // quindici minuti, ed e' l'unica riga della risposta che parla del presente.
    assertEquals(0.1, decoded.context!!.rainNowMm(now)!!, 1e-9)

    // Un'istantanea scritta prima che il quarto d'ora esistesse: si legge lo stesso, e la chiave
    // semplicemente non c'e'. E' il motivo per cui non e' stata alzata la versione del formato:
    // alzarla avrebbe svuotato la home di tutti all'aggiornamento.
    val old = original.copy(context = original.context!!.copy(minutely = emptyList()))
    val text = WeatherSnapshotCodec.encode(old)
    assertFalse("la chiave non si scrive quando non c'e' niente da scrivere", text.contains("minutely"))
    assertEquals(old, WeatherSnapshotCodec.decode(text))
  }

  @Test
  fun `una versione ignota o un JSON rotto valgono null, non un crash`() {
    assertNull(WeatherSnapshotCodec.decode("{\"version\": 99}"))
    assertNull(WeatherSnapshotCodec.decode("non e' json"))
  }

  @Test
  fun `il magazzino scrive su disco, rilegge e avvisa chi osserva`() = runTest {
    val directory = Files.createTempDirectory("snapshots").toFile()
    val store = WeatherSnapshotStore(directory)
    assertNull(store.read(WeatherSnapshot.GPS_KEY))
    store.write(snapshot())
    assertEquals(now, store.updates.value[WeatherSnapshot.GPS_KEY])

    // Un secondo magazzino sulla stessa cartella legge il file, non la memoria.
    val reopened = WeatherSnapshotStore(directory)
    assertEquals(snapshot(), reopened.read(WeatherSnapshot.GPS_KEY))
  }

  @Test
  fun `il refresher semina le verifiche al piu' una volta l'ora`() = runTest {
    val registered = mutableListOf<Boolean>()
    val store = WeatherSnapshotStore(Files.createTempDirectory("snapshots").toFile())
    var clock = now
    // Un provider che risponde davvero: un giro a vuoto ora lancia (e non scrive), quindi un
    // finto vuoto qui misurerebbe il fallimento invece della semina.
    val coordinator = object : RoundSource {
      override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
        registered += registerPredictions
        return WeatherRound(listOf(fetchOf(latitude, longitude, clock)), FusedForecast(emptyList(), emptyMap()))
      }
    }
    val refresher = WeatherSnapshotRefresher(coordinator, store) { clock }
    refresher.refresh("gps", 43.8, 11.2)
    clock += 20 * 60_000L
    refresher.refresh("gps", 43.8, 11.2)
    clock += 40 * 60_000L
    refresher.refresh("gps", 43.8, 11.2)
    assertEquals(listOf(true, false, true), registered)

    assertNotNull(refresher.fresh("gps", 43.8, 11.2, maxAgeMillis = 60_000L))
    // Undici km piu' a nord e' un altro posto: l'istantanea non vale.
    assertNull(refresher.fresh("gps", 43.9, 11.2, maxAgeMillis = 60_000L))
    clock += 2 * 60_000L
    assertNull(refresher.fresh("gps", 43.8, 11.2, maxAgeMillis = 60_000L))
  }

  /** Un provider che ha risposto: quel che basta perche' il giro non sia a vuoto. */
  private fun fetchOf(
    latitude: Double,
    longitude: Double,
    fetchedAtMillis: Long,
    providerId: String = ProviderRegistry.OPEN_METEO,
    hourly: List<HourlyPoint> = listOf(HourlyPoint(fetchedAtMillis, temperatureC = 20.0)),
  ) = ProviderFetch(
    descriptor = ProviderRegistry.all.first { it.id == providerId },
    bundle = ForecastBundle(providerId, fetchedAtMillis, latitude, longitude, hourly),
    error = null,
  )

  /** Un provider che non ha risposto. */
  private fun failureOf(providerId: String) =
    ProviderFetch(ProviderRegistry.all.first { it.id == providerId }, bundle = null, error = "niente rete")

  /** Un finto coordinatore che restituisce sempre lo stesso giro. */
  private fun roundSourceOf(round: (Long) -> WeatherRound, clock: () -> Long) = object : RoundSource {
    override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean) = round(clock())
  }

  @Test
  fun `un giro senza risposte lascia l'istantanea dov'era`() = runTest {
    // Il difetto della 1.1.0: la fusione senza bundle restituisce una previsione VUOTA, non un
    // errore, e quella finiva salvata sopra l'ultima buona. Una volta sola, e la cache era persa.
    val store = WeatherSnapshotStore(Files.createTempDirectory("snapshots").toFile())
    var clock = now
    val buono = roundSourceOf({ at -> WeatherRound(listOf(fetchOf(43.8, 11.2, at)), FusedForecast(emptyList(), emptyMap())) }) { clock }
    WeatherSnapshotRefresher(buono, store) { clock }.refresh("gps", 43.8, 11.2)
    val salvata = store.read("gps")
    assertNotNull(salvata)

    clock += 60_000L
    val aVuoto = roundSourceOf({ WeatherRound(listOf(failureOf(ProviderRegistry.OPEN_METEO)), FusedForecast(emptyList(), emptyMap())) }) { clock }
    val refresher = WeatherSnapshotRefresher(aVuoto, store) { clock }

    val esito = runCatching { refresher.refresh("gps", 43.8, 11.2) }

    assertTrue("un giro a vuoto deve fallire, non restituire il vuoto", esito.exceptionOrNull() is EmptyRoundException)
    assertEquals("l'istantanea salvata e' cambiata", salvata!!.fetchedAtMillis, store.read("gps")?.fetchedAtMillis)
  }

  @Test
  fun `un giro senza risposte non consuma la semina delle verifiche`() = runTest {
    val store = WeatherSnapshotStore(Files.createTempDirectory("snapshots").toFile())
    var clock = now
    val aVuoto = roundSourceOf({ WeatherRound(listOf(failureOf(ProviderRegistry.OPEN_METEO)), FusedForecast(emptyList(), emptyMap())) }) { clock }
    runCatching { WeatherSnapshotRefresher(aVuoto, store) { clock }.refresh("gps", 43.8, 11.2) }

    // Se il fallimento avesse scritto, `predictionsRegisteredAtMillis` sarebbe avanzato e il giro
    // buono successivo non avrebbe seminato: un'ora di verifiche persa per una mancanza di rete.
    val seminato = mutableListOf<Boolean>()
    val buono = object : RoundSource {
      override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
        seminato += registerPredictions
        return WeatherRound(listOf(fetchOf(latitude, longitude, clock)), FusedForecast(emptyList(), emptyMap()))
      }
    }
    WeatherSnapshotRefresher(buono, store) { clock }.refresh("gps", 43.8, 11.2)

    assertEquals(listOf(true), seminato)
  }

  @Test
  fun `il contesto si riporta avanti quando Open-Meteo non risponde`() = runTest {
    val store = WeatherSnapshotStore(Files.createTempDirectory("snapshots").toFile())
    var clock = now
    val conContesto = roundSourceOf({ at ->
      WeatherRound(
        listOf(fetchOf(43.8, 11.2, at, hourly = listOf(HourlyPoint(at, temperatureC = 20.0)))),
        FusedForecast(emptyList(), emptyMap()),
      )
    }) { clock }
    WeatherSnapshotRefresher(conContesto, store) { clock }.refresh("gps", 43.8, 11.2)

    // Un'ora dopo Open-Meteo tace, MET Norway no: le ore fuse sono di adesso, il contesto e' quello
    // di prima — dichiarato, non spacciato.
    clock += 3_600_000L
    val senzaOpenMeteo = roundSourceOf({ at ->
      WeatherRound(
        listOf(failureOf(ProviderRegistry.OPEN_METEO), fetchOf(43.8, 11.2, at, providerId = "met-norway")),
        FusedForecast(emptyList(), emptyMap()),
      )
    }) { clock }
    val dopo = WeatherSnapshotRefresher(senzaOpenMeteo, store) { clock }.refresh("gps", 43.8, 11.2)

    assertNotNull("il contesto non doveva sparire", dopo.context)
    assertEquals(now, dopo.contextCarriedFromMillis)
    assertEquals(clock, dopo.fetchedAtMillis)
  }

  @Test
  fun `un contesto troppo vecchio non si riporta`() = runTest {
    val store = WeatherSnapshotStore(Files.createTempDirectory("snapshots").toFile())
    var clock = now
    val conContesto = roundSourceOf({ at -> WeatherRound(listOf(fetchOf(43.8, 11.2, at)), FusedForecast(emptyList(), emptyMap())) }) { clock }
    WeatherSnapshotRefresher(conContesto, store) { clock }.refresh("gps", 43.8, 11.2)

    clock += WeatherSnapshotRefresher.CONTEXT_CARRY_MAX_AGE_MILLIS + 60_000L
    val senzaOpenMeteo = roundSourceOf({ at ->
      WeatherRound(
        listOf(failureOf(ProviderRegistry.OPEN_METEO), fetchOf(43.8, 11.2, at, providerId = "met-norway")),
        FusedForecast(emptyList(), emptyMap()),
      )
    }) { clock }
    val dopo = WeatherSnapshotRefresher(senzaOpenMeteo, store) { clock }.refresh("gps", 43.8, 11.2)

    assertNull("un'analisi di quattro ore fa non e' piu' un'analisi", dopo.context)
    assertNull(dopo.contextCarriedFromMillis)
  }

  @Test
  fun `un giro completo non riporta avanti niente`() = runTest {
    val store = WeatherSnapshotStore(Files.createTempDirectory("snapshots").toFile())
    var clock = now
    val sorgente = roundSourceOf({ at -> WeatherRound(listOf(fetchOf(43.8, 11.2, at)), FusedForecast(emptyList(), emptyMap())) }) { clock }
    WeatherSnapshotRefresher(sorgente, store) { clock }.refresh("gps", 43.8, 11.2)
    clock += 3_600_000L
    val dopo = WeatherSnapshotRefresher(sorgente, store) { clock }.refresh("gps", 43.8, 11.2)

    assertNotNull(dopo.context)
    assertNull("il contesto e' di questo giro: non c'e' niente da dichiarare", dopo.contextCarriedFromMillis)
  }

  @Test
  fun `un JSON senza i campi nuovi si legge ancora`() {
    // La compatibilita' all'indietro non e' teoria: chi aggiorna ha su disco file scritti dalla
    // 1.1.0, e buttarli sarebbe proprio il danno che questa fase ripara.
    val vecchio = WeatherSnapshotCodec.encode(snapshot().copy(contextCarriedFromMillis = null))
    assertFalse(vecchio.contains("contextCarriedFromMillis"))
    val riletto = WeatherSnapshotCodec.decode(vecchio)

    assertNotNull(riletto)
    assertNull(riletto!!.contextCarriedFromMillis)
    assertEquals(snapshot().fetchedAtMillis, riletto.fetchedAtMillis)
  }

  @Test
  fun `il contesto riportato sopravvive al giro per il JSON`() {
    val con = snapshot().copy(contextCarriedFromMillis = now - 3_600_000L)
    val riletto = WeatherSnapshotCodec.decode(WeatherSnapshotCodec.encode(con))

    assertEquals(now - 3_600_000L, riletto?.contextCarriedFromMillis)
  }

  // ------------------------------------------------------------------- allerte ufficiali

  @Test
  fun `le allerte NWS vere si leggono con testo, area e scadenza`() {
    val root = Json.parseToJsonElement(javaClass.getResource("/nws-alerts.json")!!.readText())
    val alerts = NwsAlertsParser.parse(root)
    assertEquals(2, alerts.size)
    val flood = alerts.first()
    assertEquals(OfficialAlertSource.NWS, flood.source)
    assertEquals("Flash Flood Warning", flood.event)
    assertEquals("Severe", flood.severity)
    assertTrue(flood.areaDescription!!.contains("Jefferson, TX"))
    assertTrue(flood.description!!.contains("Flash Flood Warning"))
    assertEquals("Turn around, don't drown when encountering flooded roads. Most flood\ndeaths occur in vehicles.", flood.instruction)
    assertEquals("NWS Lake Charles LA", flood.sender)
    // 2026-09-02T04:00:00-05:00
    assertEquals(1_788_339_600_000L, flood.expiresMillis)
    assertTrue(flood.isActive(flood.expiresMillis!! - 1))
    assertFalse(flood.isActive(flood.expiresMillis!! + 1))
  }

  @Test
  fun `il feed ATOM di Meteoalarm diventa allerte per area`() {
    val xml = javaClass.getResource("/meteoalarm-italy.xml")!!.readText()
    val alerts = MeteoalarmFeedParser.parse(xml)
    assertEquals(3, alerts.size)
    val sardinia = alerts.first()
    assertEquals(OfficialAlertSource.METEOALARM, sardinia.source)
    assertEquals("Yellow High-temperature Warning", sardinia.event)
    assertEquals("Sardegna", sardinia.areaDescription)
    assertEquals("Moderate", sardinia.severity)
    assertEquals("2.49.0.0.380.3.IT.260901111745.042@Sardegna", sardinia.id)
    assertEquals("https://meteoalarm.org?geocode=EMMA_ID:IT019", sardinia.link)
    // 2026-09-03T17:59:00+00:00
    assertEquals(1_788_458_340_000L, sardinia.expiresMillis)
    assertTrue(alerts.map { it.id }.toSet().size == 3)
  }

  @Test
  fun `l'abbinamento per area perdona accenti, trattini e particelle`() {
    assertTrue(MeteoalarmFeedParser.matchesArea("Emilia e Romagna", listOf("Emilia-Romagna")))
    assertTrue(MeteoalarmFeedParser.matchesArea("Trentino Alto Adige", listOf("Trentino-Alto Adige/Südtirol")))
    assertTrue(MeteoalarmFeedParser.matchesArea("Hérault", listOf("Occitanie", "Herault", "Montpellier")))
    assertTrue(MeteoalarmFeedParser.matchesArea("Toscana", listOf("Toscana", "Firenze", "Sesto Fiorentino")))
    assertFalse(MeteoalarmFeedParser.matchesArea("Sardegna", listOf("Toscana", "Firenze")))
    assertFalse(MeteoalarmFeedParser.matchesArea(null, listOf("Toscana")))
  }

  @Test
  fun `il client sceglie il servizio dal paese e filtra per area`() = runTest {
    val xml = javaClass.getResource("/meteoalarm-italy.xml")!!.readText()
    val http = ProviderHttp(FakeHttp(xml), UrlCache(Files.createTempDirectory("cache").toFile()))
    val client = OfficialAlertsClient(http)
    assertEquals(2, client.forPoint(40.1, 9.0, "IT", listOf("Sardegna", "Cagliari")).size)
    assertTrue(client.forPoint(43.8, 11.2, "IT", listOf("Toscana")).isEmpty())
    assertTrue(client.forPoint(43.8, 11.2, null, listOf("Toscana")).isEmpty())
    assertTrue(client.forPoint(43.8, 11.2, "ZZ", listOf("Nowhere")).isEmpty())
    assertEquals("italy", MeteoalarmFeeds.slugFor("it"))
    assertNull(MeteoalarmFeeds.slugFor("US"))
  }

  @Test
  fun `le coordinate di un punto meteo si arrotondano a cento metri`() {
    assertEquals(43.832 to 11.199, WeatherPoint.round(43.8319876, 11.1991234))
  }

  private class FakeHttp(private val body: String) : EngineHttp() {
    override suspend fun readText(url: String, headers: Map<String, String>): String = body
  }
}
