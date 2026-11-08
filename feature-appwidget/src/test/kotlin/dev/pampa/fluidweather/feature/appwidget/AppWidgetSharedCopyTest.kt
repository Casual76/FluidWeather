package dev.pampa.fluidweather.feature.appwidget

import android.content.res.Configuration
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppWidgetSharedCopyTest {
  private val now = 1_800_000_000_000L
  private val zone = ZoneId.of("Europe/Rome")
  private val e = WeatherEvidence("original-radar", WeatherEvidenceType.RADAR_ESTIMATE,
    downloadedAtMillis = now - 120_000, sourceTimestampMillis = now - 240_000,
    precipitationState = PrecipitationState.DRY)
  private val brief = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.DRY, now,
    Place(12, "Firenze", null, 43.8, 11.2, zone.id), GeoPoint(43.8, 11.2), listOf(e),
    WeatherInterval(now, now + 600_000), arrivalAtMillis = now + 120_000,
    limits = setOf(WeatherEvidenceLimit.RADAR_GROUND_UNCERTAINTY), radarAvailability = RadarAvailability.AVAILABLE))

  @Test fun `EN IT copy preserves absolute original clocks place and limits without ETA`() {
    listOf(Locale.ENGLISH, Locale.ITALIAN).forEach { locale ->
      val app = RuntimeEnvironment.getApplication()
      val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
      val res = app.createConfigurationContext(config).resources
      val copy = WidgetSharedRainFormatter.provenance(res, brief, now, zone, false, locale)
      assertTrue(copy.contains(zone.id))
      assertTrue(copy.contains("43.8")); assertTrue(copy.contains("11.2"))
      assertTrue(copy.contains("original-radar"))
      assertTrue(copy.contains(WidgetSharedRainFormatter.absoluteTime(now - 240_000, zone, locale)))
      assertTrue(copy.contains(WidgetSharedRainFormatter.absoluteTime(now - 120_000, zone, locale)))
      assertTrue(copy.contains(res.getString(R.string.home_rain_radar_limit)))
      assertFalse(copy.contains(WidgetSharedRainFormatter.absoluteTime(now + 120_000, zone, locale)))
      assertEquals(res.getString(R.string.home_rain_radar_no_echo),
        WidgetSharedRainFormatter.headline(res, WidgetSharedRainFormatter.headline(brief, now)))
      val unavailable = WidgetSharedRainFormatter.provenance(res,
        brief.copy(precipitation = brief.precipitation.copy(evidence = listOf(e.copy(sourceTimestampMillis = null, downloadedAtMillis = null)),
          radarAvailability = RadarAvailability.OUTSIDE)), now, zone, true, locale)
      assertTrue(unavailable.contains(res.getString(R.string.home_rain_radar_outside)))
      assertTrue(unavailable.contains(res.getString(R.string.home_rain_zone_fallback, zone.id)))
      assertTrue(unavailable.contains(res.getString(R.string.summary_unknown)))
    }
  }

  @Test fun `units are applied at rendering and never mutate current or original clocks`() {
    val res = RuntimeEnvironment.getApplication().resources
    val metric = UnitFormatter(res, UnitPreferences.METRIC)
    val imperial = UnitFormatter(res, UnitPreferences.METRIC.copy(temperature = TemperatureUnit.FAHRENHEIT))
    assertNotEquals(metric.degrees(18.0), imperial.degrees(18.0))
    assertEquals(e.sourceTimestampMillis, brief.precipitation.evidence.single().sourceTimestampMillis)
  }

  @Test fun `repeated DST wall times remain distinct by offset and year`() {
    val first = java.time.Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
    val second = first + 3_600_000
    assertNotEquals(WidgetSharedRainFormatter.absoluteTime(first, zone, Locale.ENGLISH),
      WidgetSharedRainFormatter.absoluteTime(second, zone, Locale.ENGLISH))
  }
}
