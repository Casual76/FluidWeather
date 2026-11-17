package dev.pampa.fluidweather.core.sensor

import android.app.AlarmManager
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class SamplingScheduleRecoveryTest {
  @Test fun `maxima cadence uses elapsed time from the current boot`() {
    assertEquals(420_000L, nextElapsedAlarmAt(elapsedMillis = 120_000L, minutes = 5))
    assertEquals(300_007L, nextElapsedAlarmAt(elapsedMillis = 7L, minutes = 5))
  }

  @Test fun `boot update and exact permission grant restore the saved schedule`() {
    assertTrue(isSamplingRestoreEvent(Intent.ACTION_BOOT_COMPLETED))
    assertTrue(isSamplingRestoreEvent(Intent.ACTION_MY_PACKAGE_REPLACED))
    assertTrue(isSamplingRestoreEvent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
    assertFalse(isSamplingRestoreEvent(null))
    // Wall-clock/timezone changes do not require moving an elapsed-time deadline.
    assertFalse(isSamplingRestoreEvent(Intent.ACTION_TIME_CHANGED))
    assertFalse(isSamplingRestoreEvent(Intent.ACTION_TIMEZONE_CHANGED))
  }
}
