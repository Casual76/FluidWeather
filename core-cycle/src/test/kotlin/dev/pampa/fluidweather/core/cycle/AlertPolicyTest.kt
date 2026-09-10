package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationSettings
import dev.pampa.fluidweather.core.model.OfficialAlert
import dev.pampa.fluidweather.core.model.OfficialAlertSource
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.Factor
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

  private val zone: ZoneId = ZoneId.of("Europe/Rome")

  /** 2026-06-15 12:00 Europe/Rome. */
  private val now = 1_781_517_600_000L

  private fun verdict(level: AlertLevel, probability: Double) = NowcastVerdict(
    windows = listOf(
      WindowVerdict("0-1h", probability * 0.6, probability * 0.5, probability * 0.7, emptyList()),
      WindowVerdict(
        "1-3h",
        probability,
        probability - 0.1,
        probability + 0.1,
        listOf(Factor("tendenza-3h", -0.8), Factor("umidita'", 0.3)),
      ),
      WindowVerdict("3-6h", probability * 0.8, probability * 0.7, probability * 0.9, emptyList()),
    ),
    level = level,
  )

  private fun hour(offsetHours: Int, probability: Double, amount: Double = 0.0, kind: WeatherKind? = null) =
    FusedHour(
      timestampMillis = now + offsetHours * 3_600_000L,
      values = mapOf(
        FusionVariables.PRECIP_PROBABILITY to FusedValue(probability, emptyList()),
        FusionVariables.PRECIPITATION to FusedValue(amount, emptyList()),
      ),
      kind = kind,
    )

  private fun decide(inputs: AlertInputs) = AlertPolicy.decide(inputs, ItalianTexts)

  private fun inputs(
    verdict: NowcastVerdict? = null,
    hours: List<FusedHour> = emptyList(),
    alerts: List<OfficialAlert> = emptyList(),
    settings: NotificationSettings = NotificationSettings(officialAlerts = true),
    ledger: NotificationLedger = NotificationLedger(),
    at: Long = now,
    rainingNow: Boolean = false,
  ) = AlertInputs(at, zone, settings, ledger, verdict, hours, alerts, rainingNow)

  // ---------------------------------------------------------------- allerta del barometro

  @Test
  fun `a chi sta gia' prendendo la pioggia non si annuncia la pioggia`() {
    // Il caso visto sul telefono: pioveva da due ore, e la notifica avrebbe detto "probabile".
    val decision = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.72), rainingNow = true))

    assertTrue(decision.notifications.isEmpty())
    assertFalse(decision.ledger.nowcastAlertActive)
  }

  @Test
  fun `se i provider hanno gia' detto l'ora esatta, il barometro non ripete`() {
    // "Pioggia alle 14:00" e' piu' utile di "probabile fra una e tre ore": e' la stessa notizia,
    // e la prima porta un'informazione che il barometro non ha.
    val ledger = NotificationLedger(precipitationOnsetMillis = now + 2 * 3_600_000L)
    val settings = NotificationSettings(officialAlerts = true, precipitation = true)

    val quiet = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.72), settings = settings, ledger = ledger))
    assertTrue(quiet.notifications.none { it.channel == NotificationChannelKind.NOWCAST_ALERT })

    // Un annuncio per dopodomani non copre la finestra del verdetto: li' il barometro parla.
    val far = ledger.copy(precipitationOnsetMillis = now + 30 * 3_600_000L)
    val loud = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.72), settings = settings, ledger = far))
    assertTrue(loud.notifications.any { it.channel == NotificationChannelKind.NOWCAST_ALERT })
  }

  @Test
  fun `l'allerta del barometro esce solo al livello Allerta`() {
    assertTrue(decide(inputs(verdict = verdict(AlertLevel.SORVEGLIANZA, 0.45))).notifications.isEmpty())
    assertTrue(decide(inputs(verdict = verdict(AlertLevel.QUIETE, 0.1))).notifications.isEmpty())

    val decision = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.72)))
    val alert = decision.notifications.single()
    assertEquals(NotificationChannelKind.NOWCAST_ALERT, alert.channel)
    assertEquals(AlertPolicy.NOWCAST_ID, alert.id)
    assertTrue(alert.text.contains("72%"))
    assertTrue(alert.text.contains("fra una e tre ore"))
    assertTrue(alert.bigText!!.contains("tendenza-3h ▼"))
    assertTrue(decision.ledger.nowcastAlertActive)
    assertEquals(now, decision.ledger.nowcastAlertAtMillis)
  }

  @Test
  fun `la stessa allerta non si ripete per tre ore, ma un'escalation si'`() {
    val first = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.70)))
    val ledger = first.ledger

    val sameSoon = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.75), ledger = ledger, at = now + 30 * 60_000L))
    assertTrue(sameSoon.notifications.isEmpty())

    val escalated = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.92), ledger = ledger, at = now + 30 * 60_000L))
    assertEquals(1, escalated.notifications.size)

    val later = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.70), ledger = ledger, at = now + AlertPolicy.NOWCAST_REPEAT_MILLIS))
    assertEquals(1, later.notifications.size)
  }

  @Test
  fun `quando l'allerta rientra la notifica si ritira`() {
    val active = decide(inputs(verdict = verdict(AlertLevel.ALLERTA, 0.70))).ledger
    val calm = decide(inputs(verdict = verdict(AlertLevel.QUIETE, 0.05), ledger = active))
    assertTrue(calm.cancelNowcastAlert)
    assertFalse(calm.ledger.nowcastAlertActive)
    // Il rientro non cancella la memoria del "quando": rientrare e riallertarsi in mezz'ora e' rumore.
    assertEquals(now, calm.ledger.nowcastAlertAtMillis)
  }

  @Test
  fun `un canale spento non produce niente, nemmeno memoria`() {
    val decision = decide(
      inputs(
        verdict = verdict(AlertLevel.ALLERTA, 0.9),
        hours = listOf(hour(0, 5.0), hour(1, 85.0, kind = WeatherKind.RAIN)),
        settings = NotificationSettings(nowcastAlert = false, precipitation = false, officialAlerts = false),
      ),
    )
    assertTrue(decision.notifications.isEmpty())
    assertEquals(NotificationLedger(), decision.ledger)
  }

  // ---------------------------------------------------------------- inizio/fine precipitazione

  @Test
  fun `la pioggia in arrivo entro l'anticipo si annuncia una volta sola`() {
    val hours = listOf(hour(0, 5.0), hour(1, 80.0, kind = WeatherKind.RAIN), hour(2, 90.0, kind = WeatherKind.RAIN))
    val first = decide(inputs(hours = hours))
    val onset = first.notifications.single()
    assertEquals(NotificationChannelKind.PRECIPITATION, onset.channel)
    assertEquals("Pioggia in arrivo", onset.title)
    assertTrue(onset.text.contains("13:00"))
    assertEquals(now + 3_600_000L, first.ledger.precipitationOnsetMillis)

    val again = decide(inputs(hours = hours, ledger = first.ledger, at = now + 20 * 60_000L))
    assertTrue(again.notifications.isEmpty())
  }

  @Test
  fun `la neve ha il suo nome e la fine ha il suo avviso`() {
    val snowing = listOf(hour(0, 90.0, 1.2, WeatherKind.SNOW), hour(1, 10.0, 0.0, WeatherKind.CLOUDY))
    val end = decide(inputs(hours = snowing)).notifications.single()
    assertEquals("Neve in esaurimento", end.title)
    assertTrue(end.text.contains("13:00"))
  }

  @Test
  fun `un evento lontano dall'anticipo o gia' raccontato non fa rumore`() {
    val farAway = listOf(hour(0, 5.0), hour(1, 5.0), hour(2, 5.0), hour(3, 95.0, kind = WeatherKind.RAIN))
    assertTrue(decide(inputs(hours = farAway)).notifications.isEmpty())
  }

  // ---------------------------------------------------------------------- allerte ufficiali

  private fun official(id: String, expiresOffsetHours: Int = 6) = OfficialAlert(
    id = id,
    source = OfficialAlertSource.METEOALARM,
    event = "Yellow Wind Warning",
    severity = "Moderate",
    headline = "Yellow Wind Warning issued for Italy - Toscana",
    description = null,
    instruction = null,
    areaDescription = "Toscana",
    sender = "meteoalarm.org",
    onsetMillis = now,
    expiresMillis = now + expiresOffsetHours * 3_600_000L,
    link = "https://meteoalarm.org?geocode=EMMA_ID:IT007",
  )

  @Test
  fun `ogni allerta ufficiale una volta sola, le scadute mai, al massimo tre per giro`() {
    val alerts = (1..5).map { official("a$it") } + official("scaduta", expiresOffsetHours = -1)
    val first = decide(inputs(alerts = alerts))
    assertEquals(AlertPolicy.MAX_OFFICIAL_PER_ROUND, first.notifications.size)
    assertTrue(first.notifications.all { it.channel == NotificationChannelKind.OFFICIAL_ALERTS })
    assertTrue(first.notifications.first().bigText!!.contains("Fonte: Meteoalarm"))
    assertEquals(listOf("a1", "a2", "a3"), first.ledger.officialAlertIds)

    val second = decide(inputs(alerts = alerts, ledger = first.ledger))
    assertEquals(2, second.notifications.size)
    assertEquals(listOf("a1", "a2", "a3", "a4", "a5"), second.ledger.officialAlertIds)

    val third = decide(inputs(alerts = alerts, ledger = second.ledger))
    assertTrue(third.notifications.isEmpty())
    assertNull(third.notifications.firstOrNull())
  }
}
