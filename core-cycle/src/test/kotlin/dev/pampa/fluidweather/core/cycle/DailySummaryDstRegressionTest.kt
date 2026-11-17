package dev.pampa.fluidweather.core.cycle

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DailySummaryDstRegressionTest {
  @Test fun `after spring gap the following day returns to the chosen wall time`() {
    val actual = DailySummaryAlarm.nextTriggerMillis(
      Instant.parse("2026-03-29T12:00:00Z").toEpochMilli(), 2, 30, ZoneId.of("Europe/Rome"),
    )
    assertEquals(Instant.parse("2026-03-30T00:30:00Z").toEpochMilli(), actual)
  }
}
