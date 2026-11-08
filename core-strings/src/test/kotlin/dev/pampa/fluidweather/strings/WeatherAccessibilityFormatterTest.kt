package dev.pampa.fluidweather.strings

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class WeatherAccessibilityFormatterTest {
  private val zone = ZoneId.of("Europe/Rome")
  @Test fun shortResidualIsQualifiedWithoutSubsecondWeatherPrecisionForBothLanguages() {
    val start = Instant.parse("2026-11-07T10:20:42.123Z").toEpochMilli()
    listOf(Locale.ENGLISH, Locale.ITALIAN).forEach { locale ->
      val label = accessibleInterval(start, start + 8000, zone, locale)
      assertTrue(label.short)
      assertTrue(label.start.contains("11:20"))
      assertFalse(label.start.contains("42.123"))
      // Equal minute captions are fine in the DTO: the short label renders ONE approximate
      // caption, not a misleading equal-endpoint range. The source interval remains unchanged.
      assertEquals(label.start, label.end)
    }
  }
  @Test fun repeatedDstClockIsAnHourWithDistinctOffsetsNotShortFragment() {
    val start = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
    listOf(Locale.ENGLISH, Locale.ITALIAN).forEach { locale ->
      val label = accessibleInterval(start, start + 3_600_000, zone, locale)
      assertFalse(label.short)
      assertTrue(label.start.contains("02:30 +02:00"))
      assertTrue(label.end.contains("02:30 +01:00"))
    }
  }
  @Test fun midnightAndZoneAreExplicit() {
    val start = Instant.parse("2026-11-07T22:59:59Z").toEpochMilli()
    val label = accessibleInterval(start, start + 2000, zone, Locale.ITALIAN)
    assertTrue(label.short)
    assertTrue(label.start.contains("7 nov"))
    assertTrue(label.end.contains("8 nov"))
    assertTrue(label.end.contains("Europe/Rome"))
  }
}
