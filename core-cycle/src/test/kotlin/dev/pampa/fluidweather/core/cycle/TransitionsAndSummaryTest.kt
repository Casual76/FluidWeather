package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionsAndSummaryTest {

  private val zone: ZoneId = ZoneId.of("Europe/Rome")

  /** 2026-06-15 12:00 Europe/Rome. */
  private val noon = 1_781_517_600_000L

  private fun hour(
    base: Long,
    offsetHours: Int,
    probability: Double? = null,
    amount: Double? = null,
    temperature: Double? = null,
    kind: WeatherKind? = null,
  ) = FusedHour(
    timestampMillis = base + offsetHours * 3_600_000L,
    values = buildMap {
      probability?.let { put(FusionVariables.PRECIP_PROBABILITY, FusedValue(it, emptyList())) }
      amount?.let { put(FusionVariables.PRECIPITATION, FusedValue(it, emptyList())) }
      temperature?.let { put(FusionVariables.TEMPERATURE, FusedValue(it, emptyList())) }
    },
    kind = kind,
  )

  // ------------------------------------------------------------------- transizioni

  @Test
  fun `un'ora e' bagnata per probabilita' alta o per accumulo misurabile`() {
    assertTrue(PrecipitationTransitions.isWet(hour(noon, 0, probability = 65.0)))
    assertTrue(PrecipitationTransitions.isWet(hour(noon, 0, probability = 20.0, amount = 0.5)))
    assertTrue(!PrecipitationTransitions.isWet(hour(noon, 0, probability = 40.0, amount = 0.1)))
    assertTrue(!PrecipitationTransitions.isWet(hour(noon, 0)))
  }

  @Test
  fun `l'inizio si vede dall'ora in corso, anche a meta' ora`() {
    val hours = listOf(hour(noon, 0, 5.0), hour(noon, 1, 75.0, kind = WeatherKind.RAIN), hour(noon, 2, 80.0))
    val transition = PrecipitationTransitions.next(hours, noon + 40 * 60_000L)!!
    assertEquals(TransitionKind.ONSET, transition.kind)
    assertEquals(noon + 3_600_000L, transition.atMillis)
    assertEquals(WeatherKind.RAIN, transition.weatherKind)
  }

  @Test
  fun `oltre l'anticipo non c'e' transizione, e senza ora corrente nemmeno`() {
    val late = listOf(hour(noon, 0, 5.0), hour(noon, 1, 5.0), hour(noon, 2, 90.0))
    assertNull(PrecipitationTransitions.next(late, noon))
    assertNull(PrecipitationTransitions.next(listOf(hour(noon, 5, 90.0)), noon))
  }

  @Test
  fun `la fine e' la prima ora asciutta dopo quella bagnata`() {
    val hours = listOf(hour(noon, 0, 90.0, kind = WeatherKind.HEAVY_RAIN), hour(noon, 1, 10.0, kind = WeatherKind.CLOUDY))
    val transition = PrecipitationTransitions.next(hours, noon + 10 * 60_000L)!!
    assertEquals(TransitionKind.END, transition.kind)
    assertEquals(noon + 3_600_000L, transition.atMillis)
    assertEquals(WeatherKind.HEAVY_RAIN, transition.weatherKind)
  }

  // -------------------------------------------------------------------- riepilogo

  @Test
  fun `il riepilogo racconta condizione, escursione, pioggia e barometro`() {
    val morning = noon - 5 * 3_600_000L // 07:00
    val hours = (0..23).map { h ->
      hour(
        noon - 12 * 3_600_000L,
        h,
        probability = if (h == 16) 55.0 else 10.0,
        temperature = 14.0 + h * 0.5,
        kind = if (h in 8..20) WeatherKind.PARTLY_CLOUDY else WeatherKind.CLEAR,
      )
    }
    val verdict = NowcastVerdict(
      windows = listOf(WindowVerdict("1-3h", 0.4, 0.3, 0.5, emptyList())),
      level = AlertLevel.SORVEGLIANZA,
    )
    val summary = DailySummary.compose(morning, zone, hours, verdict, pressureTrendHpaPerHour = -0.8, locationName = "Sesto Fiorentino")!!

    assertEquals(NotificationChannelKind.DAILY_SUMMARY, summary.channel)
    assertEquals("Oggi a Sesto Fiorentino", summary.title)
    assertTrue(summary.text, summary.text.startsWith("Parzialmente nuvoloso"))
    assertTrue(summary.text, summary.text.contains("max 25° min 14°"))
    assertTrue(summary.text, summary.text.contains("pioggia 55% verso le 16"))
    assertTrue(summary.bigText!!, summary.bigText!!.contains("Barometro in calo (-0.8 hPa/h)"))
    assertTrue(summary.bigText!!, summary.bigText!!.contains("sorveglianza: pioggia 40% fra una e tre ore"))
  }

  @Test
  fun `senza ore di oggi non c'e' riepilogo`() {
    assertNull(DailySummary.compose(noon, zone, listOf(hour(noon + 48 * 3_600_000L, 0, temperature = 20.0)), null, null, null))
  }

  @Test
  fun `l'allarme del riepilogo cade oggi se deve ancora venire, altrimenti domani`() {
    val trigger = DailySummaryAlarm.nextTriggerMillis(noon, hour = 19, minute = 30, zone = zone)
    assertEquals(noon + 7 * 3_600_000L + 30 * 60_000L, trigger)
    val tomorrow = DailySummaryAlarm.nextTriggerMillis(noon, hour = 7, minute = 30, zone = zone)
    assertEquals(noon + 19 * 3_600_000L + 30 * 60_000L, tomorrow)
  }
}
