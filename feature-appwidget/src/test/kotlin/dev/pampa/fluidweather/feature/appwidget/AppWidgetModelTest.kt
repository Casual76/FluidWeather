package dev.pampa.fluidweather.feature.appwidget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.DataAge
import dev.pampa.fluidweather.core.model.DataFreshness
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NowcastVerdictRecord
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il contenuto del widget, deciso fuori dalla composable.
 *
 * La composable Glance resta un renderer senza decisioni proprio perche' qui si possa chiedere
 * tutto a un test: sul telefono un widget si prova solo appoggiandolo e guardandolo.
 */
class AppWidgetModelTest {

  private val adesso = 1_781_517_600_000L
  private val ora = 3_600_000L

  private fun ore(count: Int, from: Long = adesso) = (0 until count).map { h ->
    FusedHour(
      timestampMillis = from + h * ora,
      values = mapOf(FusionVariables.TEMPERATURE to FusedValue(18.0 + h, emptyList())),
      kind = WeatherKind.RAIN,
    )
  }

  private fun istantanea(fetchedAtMillis: Long = adesso, hours: List<FusedHour> = ore(8, adesso - ora)) =
    WeatherSnapshot(
      placeKey = WeatherSnapshot.GPS_KEY,
      latitude = 43.8,
      longitude = 11.2,
      fetchedAtMillis = fetchedAtMillis,
      fetches = emptyList(),
      fused = FusedForecast(hours, emptyMap()),
      context = null,
    )

  private fun verdetto(level: AlertLevel = AlertLevel.ALLERTA) = NowcastVerdictRecord(
    timestampMillis = adesso,
    probability01 = 0.2,
    probability13 = 0.7,
    probability36 = 0.4,
    level = level.name,
  )

  // ------------------------------------------------------------------------------- le taglie

  @Test
  fun `le tre taglie dichiarate cadono dove devono`() {
    assertEquals(AppWidgetTier.SMALL, tierFor(DpSize(110.dp, 110.dp)))
    assertEquals(AppWidgetTier.MEDIUM, tierFor(DpSize(250.dp, 110.dp)))
    assertEquals(AppWidgetTier.LARGE, tierFor(DpSize(250.dp, 200.dp)))
  }

  @Test
  fun `largo e basso non e' grande`() {
    // 4x1: c'e' larghezza per il verdetto ma non altezza per la striscia delle ore.
    assertEquals(AppWidgetTier.MEDIUM, tierFor(DpSize(320.dp, 70.dp)))
  }

  @Test
  fun `alto e stretto resta piccolo`() {
    // 2x4: l'altezza non basta a fare spazio, se il testo non ci sta in larghezza.
    assertEquals(AppWidgetTier.SMALL, tierFor(DpSize(110.dp, 280.dp)))
  }

  // ------------------------------------------------------------------- cosa entra nel modello

  @Test
  fun `la piccola non calcola quello che non disegnera'`() {
    val model = AppWidgetModelBuilder.of(istantanea(), null, verdetto(), adesso, AppWidgetTier.SMALL)

    assertTrue("la piccola non ha spazio per le ore", model.hours.isEmpty())
    assertNull("ne' per il verdetto", model.verdictLevel)
    assertNotNull(model.temperatureC)
  }

  @Test
  fun `la media porta il verdetto ma non la striscia`() {
    val model = AppWidgetModelBuilder.of(istantanea(), null, verdetto(), adesso, AppWidgetTier.MEDIUM)

    assertEquals(AlertLevel.ALLERTA, model.verdictLevel)
    assertEquals(70, model.verdictProbabilityPercent)
    assertEquals("1-3h", model.verdictWindow)
    assertTrue(model.hours.isEmpty())
  }

  @Test
  fun `la grande porta quattro ore, tutte future`() {
    val model = AppWidgetModelBuilder.of(istantanea(), null, verdetto(), adesso, AppWidgetTier.LARGE)

    assertEquals(AppWidgetModelBuilder.STRIP_HOURS, model.hours.size)
    assertTrue("un'ora passata nella striscia", model.hours.all { it.timestampMillis > adesso })
  }

  @Test
  fun `la temperatura e' quella dell'ora piu' vicina, come nella home`() {
    // Stessa funzione (`nearestHour`), quindi widget e app non possono dire due numeri diversi.
    val model = AppWidgetModelBuilder.of(istantanea(), null, null, adesso, AppWidgetTier.MEDIUM)

    assertEquals(19.0, model.temperatureC!!, 1e-9)
  }

  @Test
  fun `un'istantanea di nove ore fa e' molto vecchia, ma si mostra`() {
    val vecchia = istantanea(fetchedAtMillis = adesso - 9 * ora)

    val model = AppWidgetModelBuilder.of(vecchia, null, null, adesso, AppWidgetTier.MEDIUM)

    assertEquals(DataFreshness.VERY_STALE, model.freshness)
    assertNotNull("il dato c'e': si mostra e si data", model.temperatureC)
    assertEquals(adesso - 9 * ora, model.dataAtMillis)
  }

  @Test
  fun `senza istantanea il widget lo dice`() {
    val model = AppWidgetModelBuilder.of(null, null, null, adesso, AppWidgetTier.LARGE)

    assertTrue(model.isEmpty)
    assertEquals(DataFreshness.NONE, model.freshness)
    assertNull(model.dataAtMillis)
  }

  @Test
  fun `un livello sconosciuto non fa cadere il widget`() {
    // Lo storico salva il livello come stringa: una versione futura potrebbe scriverne uno nuovo.
    val strano = verdetto().copy(level = "TEMPESTA_DI_RANE")

    val model = AppWidgetModelBuilder.of(istantanea(), null, strano, adesso, AppWidgetTier.MEDIUM)

    assertNull(model.verdictLevel)
  }

  @Test
  fun `un verdetto a probabilita' zero non dice niente`() {
    val muto = verdetto().copy(probability01 = 0.0, probability13 = 0.0, probability36 = 0.0)

    val model = AppWidgetModelBuilder.of(istantanea(), null, muto, adesso, AppWidgetTier.MEDIUM)

    assertNull(model.verdictProbabilityPercent)
    assertNull(model.verdictWindow)
  }

  // -------------------------------------------------------------------------------- le icone

  @Test
  fun `ogni tipo di tempo ha il suo disegno`() {
    // `when` esaustivo nel codice, ma un ramo puo' sempre puntare a una risorsa sbagliata.
    WeatherKind.entries.forEach {
      assertNotEquals("$it senza icona", 0, it.appWidgetIconRes())
    }
    assertNotEquals(0, null.appWidgetIconRes())
  }

  @Test
  fun `pioggia e pioggia forte condividono il disegno, non la storia`() {
    // Nove vettori per tredici tipi: a 17 dp la differenza non si vede, e la porta la tinta.
    assertEquals(WeatherKind.RAIN.appWidgetIconRes(), WeatherKind.HEAVY_RAIN.appWidgetIconRes())
    assertNotEquals(WeatherKind.RAIN.appWidgetIconRes(), WeatherKind.SNOW.appWidgetIconRes())
  }

  @Test
  fun `l'eta' del widget usa la stessa scala della home`() {
    val model = AppWidgetModelBuilder.of(istantanea(adesso - 2 * ora), null, null, adesso, AppWidgetTier.MEDIUM)

    assertEquals(DataAge.of(adesso - 2 * ora, adesso), model.freshness)
  }

  private fun assertNotNull(value: Any?) = assertTrue(value != null)

  private fun assertNotNull(message: String, value: Any?) = assertTrue(message, value != null)
}
