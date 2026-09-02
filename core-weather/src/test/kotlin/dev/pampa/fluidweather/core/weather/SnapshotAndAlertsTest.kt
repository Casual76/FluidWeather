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
    val coordinator = object : RoundSource {
      override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
        registered += registerPredictions
        return WeatherRound(emptyList(), FusedForecast(emptyList(), emptyMap()))
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
