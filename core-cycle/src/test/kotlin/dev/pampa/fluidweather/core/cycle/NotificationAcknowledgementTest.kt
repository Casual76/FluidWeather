package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.nowcast.verdict.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NotificationAcknowledgementTest {
  private val now = 1_781_517_600_000L
  private val location = NotificationLocation("gps", 43.83, 11.20)
  private val settings = NotificationSettings(officialAlerts = true)
  private fun alert(id: String) = OfficialAlert(id, OfficialAlertSource.METEOALARM, id,
    null, null, null, null, "Toscana", null, now, now + 3_600_000L, null)
  private fun inputs(ledger: NotificationLedger = NotificationLedger(), point: NotificationLocation = location) =
    AlertInputs(now, ZoneId.of("Europe/Rome"), settings, ledger, null, emptyList(),
      listOf(alert("first"), alert("second"), alert("third")), location = point)

  @Test fun `partial official delivery records each accepted identifier independently`() {
    val decision = AlertPolicy.decide(inputs(), ItalianTexts)
    val result = decision.deliver { it.title != "second" }
    assertTrue(result.pending)
    assertEquals(listOf("first", "third"), result.ledger.officialAlertIds)
    val retry = AlertPolicy.decide(inputs(result.ledger), ItalianTexts)
    assertEquals(listOf("second"), retry.notifications.map { it.title })
    val complete = retry.deliver { true }
    assertFalse(complete.pending)
    assertTrue(AlertPolicy.decide(inputs(complete.ledger), ItalianTexts).notifications.isEmpty())
  }

  @Test fun `refused local alert does not start cooldown or become active`() {
    val verdict = NowcastVerdict(listOf(WindowVerdict("1-3h", .8, .7, .9, emptyList())), AlertLevel.ALLERTA)
    val input = inputs().copy(verdict = verdict, officialAlerts = emptyList())
    val refused = AlertPolicy.decide(input, ItalianTexts).deliver { false }
    assertNull(refused.ledger.nowcastAlertAtMillis)
    assertFalse(refused.ledger.nowcastAlertActive)
    assertEquals(1, AlertPolicy.decide(input.copy(ledger = refused.ledger), ItalianTexts).notifications.size)
  }

  @Test fun `nearby GPS jitter retains scope but a distant point gets its own event memory`() {
    val ledger = NotificationLedger(location = location, precipitationOnsetMillis = now + 3_600_000L)
    val near = ledger.atLocation(location.copy(latitude = 43.831))
    assertEquals(ledger.precipitationOnsetMillis, near.precipitationOnsetMillis)
    val far = ledger.atLocation(location.copy(latitude = 52.52, longitude = 13.40))
    assertNull(far.precipitationOnsetMillis)
    assertEquals(ledger.precipitationOnsetMillis, far.atLocation(location).precipitationOnsetMillis)
  }

  @Test fun `official identifiers survive changing local alert scope`() {
    val delivered = AlertPolicy.decide(inputs(), ItalianTexts).deliver { true }
    val elsewhere = inputs(delivered.ledger, location.copy(latitude = 52.52, longitude = 13.40))
    assertTrue(AlertPolicy.decide(elsewhere, ItalianTexts).notifications.isEmpty())
  }
}
