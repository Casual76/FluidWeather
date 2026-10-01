package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.MinutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Il contesto "com'era al download": solo slot chiusi, mai un valore di un'ora che si chiude dopo
 * il riferimento. `S` e' l'ultima ora tonda chiusa a `ref = min(adesso, download)`.
 */
class ContextAtTest {

  /** Un'ora tonda: 2026-09-01T10:00Z. */
  private val s = MIDNIGHT + 10 * HOUR
  private val quarter = 15 * 60_000L

  /** Le righe dalle 05 alle 13: il valore di ogni campo dice a che ora appartiene (umidita' = ora). */
  private fun rows(rain: (Int) -> Double? = { 0.0 }) = (5..13).map { hour ->
    HourlyPoint(
      timestampMillis = MIDNIGHT + hour * HOUR,
      temperatureC = 20.0,
      dewPointC = 15.0,
      relativeHumidityPercent = hour.toDouble(),
      cloudCoverPercent = 50.0,
      windSpeedKmh = 10.0,
      windDirectionDeg = hour * 10.0,
      pressureMslHpa = 1000.0 + hour,
      precipitationMm = rain(hour),
    )
  }

  private fun bundle(
    fetchedAt: Long,
    hourly: List<HourlyPoint> = rows(),
    minutely: List<MinutePoint> = emptyList(),
  ) = ForecastBundle("open-meteo", fetchedAt, 43.83, 11.2, hourly, minutely)

  @Test
  fun `a quaranta minuti dall'ora si legge la riga dell'ora, non quella dopo`() {
    val context = bundle(fetchedAt = s + 40 * 60_000L).toContext(nowMillis = s + 40 * 60_000L)!!

    // Il valore istantaneo delle 11 e' nel futuro del download: non si legge.
    assertEquals(10.0, context.relativeHumidityPercent!!, 0.0)
    assertEquals(100.0, context.windDirectionDeg!!, 0.0)
    assertEquals(1010.0, context.pressureMslHpa!!, 0.0)
  }

  @Test
  fun `adesso dopo il download vale il riferimento del download`() {
    // Il contesto e' stato scaricato alle 10:40, ma lo si legge alle 12:10: il riferimento e' 10:40.
    val context = bundle(fetchedAt = s + 40 * 60_000L).toContext(nowMillis = s + 130 * 60_000L)!!

    assertEquals(10.0, context.relativeHumidityPercent!!, 0.0)
  }

  @Test
  fun `se l'orologio del telefono e' indietro al download vale adesso`() {
    // Il download dice 10:40 ma il telefono segna 09:20: il riferimento e' il minore, le 09.
    val context = bundle(fetchedAt = s + 40 * 60_000L).toContext(nowMillis = s - 40 * 60_000L)!!

    assertEquals(9.0, context.relativeHumidityPercent!!, 0.0)
  }

  @Test
  fun `la pioggia dell'ultima ora e' lo slot orario chiuso, anche quando i quarti d'ora ci sono`() {
    val ref = s + 40 * 60_000L
    // I quattro quarti in (ref-1h, ref]: 09:45, 10:00, 10:15, 10:30. Quello delle 10:45 e' dopo.
    val minutes = listOf(
      MinutePoint(s - quarter, precipitationMm = 0.1),
      MinutePoint(s, precipitationMm = 0.2),
      MinutePoint(s + quarter, precipitationMm = 0.3),
      MinutePoint(s + 2 * quarter, precipitationMm = 0.4),
      MinutePoint(s + 3 * quarter, precipitationMm = 9.0),
    )
    // Il v3 e' addestrato sullo slot orario (S-1h, S]: la parita' col banco vale piu' della freschezza
    // dei quarti d'ora, che qui direbbero 1,0 mm.
    val context = bundle(fetchedAt = ref, hourly = rows { if (it == 10) 0.6 else 0.0 }, minutely = minutes).toContext(ref)!!

    assertEquals(0.6, context.rainLastHourMm!!, 1e-9)
  }

  @Test
  fun `con meno di quattro quarti si torna allo slot orario chiuso`() {
    val ref = s + 40 * 60_000L
    val minutes = listOf(MinutePoint(ref - quarter, precipitationMm = 5.0))
    val context = bundle(fetchedAt = ref, hourly = rows { if (it == 10) 0.7 else 0.0 }, minutely = minutes).toContext(ref)!!

    assertEquals(0.7, context.rainLastHourMm!!, 1e-9)
  }

  @Test
  fun `tre ore fa e' la riga esatta, e la pioggia di tre ore somma gli slot che ci sono`() {
    val context = bundle(fetchedAt = s + 40 * 60_000L, hourly = rows { 0.5 }).toContext(s + 40 * 60_000L)!!

    assertEquals(70.0, context.windDirectionDeg3hAgo!!, 0.0)
    assertEquals(1007.0, context.pressureMsl3hAgoHpa!!, 0.0)
    assertEquals(1.5, context.rainLast3hMm!!, 1e-9)
  }

  @Test
  fun `una riga tre ore fa che manca non inventa la rotazione`() {
    val hourly = rows().filter { it.timestampMillis != s - 3 * HOUR }
    val context = bundle(fetchedAt = s, hourly = hourly).toContext(s)!!

    assertNull(context.windDirectionDeg3hAgo)
    assertNotNull(context.windDirectionDeg)
  }

  @Test
  fun `senza la riga dello slot adesso il contesto non c'e'`() {
    val hourly = rows().filter { it.timestampMillis != s }
    // Le righe vicine ci sono (09 e 11), ma la lettura e' esatta: niente ripiego sulla piu' vicina.
    assertNull(bundle(fetchedAt = s + 40 * 60_000L, hourly = hourly).toContext(s + 40 * 60_000L))
  }

  @Test
  fun `una riga con tutti i campi vuoti non e' un contesto`() {
    val hourly = listOf(HourlyPoint(s), HourlyPoint(s + HOUR, temperatureC = 20.0))

    assertNull(bundle(fetchedAt = s + 5 * 60_000L, hourly = hourly).toContext(s + 5 * 60_000L))
  }
}
