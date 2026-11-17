package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.NotificationSettings
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailySummaryScheduleTest {
  private val zone = ZoneId.of("Europe/Rome")
  private val settings = NotificationSettings(dailySummary = true)
  private fun time(value: String) = Instant.parse(value).toEpochMilli()

  @Test fun `cold start after delayed alarm recovers today's undelivered summary`() {
    val due = DailySummarySchedule.pendingOccurrence(time("2026-11-17T06:45:00Z"), settings, null, zone)
    assertEquals(time("2026-11-17T06:30:00Z"), due)
  }

  @Test fun `before selected time or after grace period there is no catchup`() {
    assertNull(DailySummarySchedule.pendingOccurrence(time("2026-11-17T06:29:00Z"), settings, null, zone))
    assertNull(DailySummarySchedule.pendingOccurrence(time("2026-11-17T12:30:01Z"), settings, null, zone))
  }

  @Test fun `delivered day and disabled summaries never recover`() {
    val now = time("2026-11-17T06:45:00Z")
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    assertNull(DailySummarySchedule.pendingOccurrence(now, settings, day, zone))
    assertNull(DailySummarySchedule.pendingOccurrence(now, settings.copy(dailySummary = false), null, zone))
  }

  @Test fun `a queued request expires after date or setting changes`() {
    val due = time("2026-11-17T06:30:00Z")
    val now = time("2026-11-17T06:45:00Z")
    assertTrue(DailySummarySchedule.isCurrentOccurrence(due, now, settings, zone))
    assertFalse(DailySummarySchedule.isCurrentOccurrence(due, now, settings.copy(summaryMinute = 45), zone))
    assertFalse(DailySummarySchedule.isCurrentOccurrence(due, time("2026-11-18T06:45:00Z"), settings, zone))
    assertFalse(DailySummarySchedule.isCurrentOccurrence(due, now, settings, ZoneId.of("America/New_York")))
  }

  @Test fun `spring gap and autumn overlap use one occurrence on the local date`() {
    val spring = settings.copy(summaryHour = 2, summaryMinute = 30)
    assertEquals(time("2026-03-29T01:30:00Z"), DailySummarySchedule.pendingOccurrence(time("2026-03-29T01:45:00Z"), spring, null, zone))
    assertEquals(time("2026-10-25T00:30:00Z"), DailySummarySchedule.pendingOccurrence(time("2026-10-25T01:45:00Z"), spring, null, zone))
  }

  @Test fun `midnight never replays yesterday's morning`() {
    assertNull(DailySummarySchedule.pendingOccurrence(time("2026-11-17T23:01:00Z"), settings, null, zone))
  }
}
