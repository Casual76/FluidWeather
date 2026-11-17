package dev.pampa.fluidweather.core.cycle

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationSettings
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotificationDeliveryReliabilityTest {
  private lateinit var app: Application
  private lateinit var manager: NotificationManager
  private lateinit var notifier: SystemNotifier
  private val notification = AppNotification(NotificationChannelKind.PRECIPITATION, 200, "Rain", "At 13:00")

  @Before fun setUp() {
    app = RuntimeEnvironment.getApplication()
    manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(manager).setNotificationsEnabled(true)
    notifier = SystemNotifier(app)
  }

  @Test fun `blocked channel is refused without consuming delivery`() {
    manager.createNotificationChannel(NotificationChannel(
      notification.channel.id, "Precipitation", NotificationManager.IMPORTANCE_NONE,
    ))
    assertFalse(notifier.post(notification))
    assertEquals(0, shadowOf(manager).size())
  }

  @Test fun `enabled channel accepts the notification and expanded body`() {
    NotificationChannels.ensure(app)
    assertTrue(notifier.post(notification.copy(bigText = "Detailed rain forecast")))
    assertEquals("Detailed rain forecast", manager.activeNotifications.single().notification.extras
      .getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString())
  }

  @Test fun `denied runtime permission refuses posting`() {
    NotificationChannels.ensure(app)
    shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertFalse(notifier.post(notification))
  }

  @Test fun `foreground bus without a banner subscriber refuses delivery`() {
    assertFalse(InAppAlertBus().emit(notification))
  }

  @Test fun `switching nowcast off retracts its currently active alert`() {
    val decision = AlertPolicy.decide(AlertInputs(
      nowMillis = 1_781_517_600_000L,
      zone = ZoneId.of("Europe/Rome"),
      settings = NotificationSettings(nowcastAlert = false, precipitation = false),
      ledger = NotificationLedger(nowcastAlertActive = true, nowcastAlertAtMillis = 1_781_517_600_000L),
      verdict = null, fusedHours = emptyList(), officialAlerts = emptyList(),
    ), ItalianTexts)
    assertTrue(decision.cancelNowcastAlert)
    assertFalse(decision.ledger.nowcastAlertActive)
  }
}
