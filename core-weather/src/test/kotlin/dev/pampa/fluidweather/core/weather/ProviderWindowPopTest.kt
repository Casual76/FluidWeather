package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La PoP di un provider sulla finestra: gli slot esatti di RainWindows, non "l'ora piu' vicina".
 *
 * Emissione alle 10:20: l'ancora e' le 11:00, lo slot della 0-1h e' (11:00, 12:00], quelli della
 * 1-3h si chiudono alle 13:00 e alle 14:00.
 */
class ProviderWindowPopTest {

  private val issuedAt = MIDNIGHT + 10 * HOUR + 20 * 60_000L

  private fun bundle(vararg pops: Pair<Long, Double?>) = ForecastBundle(
    providerId = "test",
    fetchedAtMillis = issuedAt,
    latitude = 43.832,
    longitude = 11.199,
    hourly = pops.map { (at, pop) -> HourlyPoint(at, precipitationProbabilityPercent = pop) },
  )

  private fun at(hour: Int, minute: Int = 0) = MIDNIGHT + hour * HOUR + minute * 60_000L

  @Test
  fun `un'emissione alle 10 e 20 guarda lo slot che si chiude alle 12`() {
    val openMeteo = bundle(at(11) to 90.0, at(12) to 30.0, at(13) to 50.0, at(14) to 70.0)

    // Il 90% delle 11:00 e' l'ora (10:00, 11:00]: gia' iniziata all'emissione, non e' della finestra.
    assertEquals(0.30, ProviderWindowPop.of(openMeteo, PopConvention.HOUR_ENDING, issuedAt, RainWindows.ZERO_ONE)!!, 1e-9)
    // La 1-3h e' il massimo degli slot delle 13 e delle 14.
    assertEquals(0.70, ProviderWindowPop.of(openMeteo, PopConvention.HOUR_ENDING, issuedAt, RainWindows.ONE_THREE)!!, 1e-9)
  }

  @Test
  fun `i timestamp devono essere esatti - un punto alle 12 e 30 non vale per lo slot delle 12`() {
    val shifted = bundle(at(11, 30) to 40.0, at(12, 30) to 40.0)

    assertNull(ProviderWindowPop.of(shifted, PopConvention.HOUR_ENDING, issuedAt, RainWindows.ZERO_ONE))
  }

  @Test
  fun `NWS etichetta il periodo dal suo inizio`() {
    // Il periodo che comincia alle 11:00 e' lo slot (11:00, 12:00].
    val nws = bundle(at(11) to 25.0, at(12) to 80.0)

    assertEquals(0.25, ProviderWindowPop.of(nws, PopConvention.HOUR_STARTING, issuedAt, RainWindows.ZERO_ONE)!!, 1e-9)
  }

  @Test
  fun `OpenWeatherMap - vale il passo di tre ore che contiene lo slot`() {
    // Passi che si chiudono alle 12 (9-12) e alle 15 (12-15).
    val owm = bundle(at(12) to 20.0, at(15) to 60.0)

    assertEquals(0.20, ProviderWindowPop.of(owm, PopConvention.THREE_HOUR_ENDING, issuedAt, RainWindows.ZERO_ONE)!!, 1e-9)
    // Gli slot delle 13 e delle 14 stanno entrambi nel passo che si chiude alle 15.
    assertEquals(0.60, ProviderWindowPop.of(owm, PopConvention.THREE_HOUR_ENDING, issuedAt, RainWindows.ONE_THREE)!!, 1e-9)
  }

  @Test
  fun `uno slot senza PoP rende la finestra senza probabilita'`() {
    val holed = bundle(at(12) to 30.0, at(13) to null, at(14) to 50.0)

    assertEquals(0.30, ProviderWindowPop.of(holed, PopConvention.HOUR_ENDING, issuedAt, RainWindows.ZERO_ONE)!!, 1e-9)
    assertNull(ProviderWindowPop.of(holed, PopConvention.HOUR_ENDING, issuedAt, RainWindows.ONE_THREE))
  }

  @Test
  fun `chi non ha PoP non ha una riga`() {
    assertEquals(PopConvention.NONE, ProviderWindowPop.conventionOf(ProviderRegistry.MET_NORWAY))
    assertEquals(PopConvention.NONE, ProviderWindowPop.conventionOf(ProviderRegistry.METEOSOURCE))
    assertNull(ProviderWindowPop.of(bundle(at(12) to 50.0), PopConvention.NONE, issuedAt, RainWindows.ZERO_ONE))
  }

  @Test
  fun `le convenzioni dei provider`() {
    assertEquals(PopConvention.HOUR_ENDING, ProviderWindowPop.conventionOf(ProviderRegistry.OPEN_METEO))
    assertEquals(PopConvention.HOUR_ENDING, ProviderWindowPop.conventionOf(ProviderRegistry.OPEN_METEO_ICON))
    assertEquals(PopConvention.HOUR_STARTING, ProviderWindowPop.conventionOf(ProviderRegistry.NWS))
    assertEquals(PopConvention.THREE_HOUR_ENDING, ProviderWindowPop.conventionOf(ProviderRegistry.OPENWEATHERMAP))
  }

  @Test
  fun `ogni client che e' anche un giudice del pannello sta fra gli esclusi`() {
    val clients = buildWeatherClients(providerHttpOf(RecordingHttp { "{}" }) { 0L })
    val judges = clients.values.filterIsInstance<OpenMeteoClient>().filter { it.model in TruthPanel.MODELS }

    assertFalse("almeno AROME e' un giudice: se sparisce, il test non protegge niente", judges.isEmpty())
    assertTrue(judges.all { it.descriptor.id in ProviderWindowPop.JUDGE_PROVIDER_IDS })
    // E non si esclude chi giudice non e'.
    assertTrue(ProviderRegistry.OPEN_METEO !in ProviderWindowPop.JUDGE_PROVIDER_IDS)
  }
}
