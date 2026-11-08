package dev.pampa.fluidweather

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.RoutineWindowEvaluator
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Evaluator -> actual Android resources/formatter: future valid times are forecast facts. */
class WeatherSummaryForecastAndroidTest {
  private val now = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli()
  private val start = now + 3_600_000L
  private val place = Place(42, "Rome", null, 41.9, 12.5, "UTC")
  private fun assessment(amount: Double): RoutineWindowAssessment {
    val bundle = ForecastBundle("fixture-model", now, 41.9, 12.5,
      listOf(HourlyPoint(start, temperatureC = 12.0, precipitationMm = amount)), zoneId = "UTC",
      hourlyPrecipitation = PrecipitationAccumulation(3_600_000L, AccumulationAnchor.FOLLOWING))
    val snapshot = WeatherSnapshot("place-42", 41.9, 12.5, now, emptyList(), FusedForecast(emptyList(), emptyMap()), bundle, zoneId = "UTC")
    val routine = RoutineWindow("walk", "Walk", setOf(DayOfWeek.MONDAY), LocalTime.of(10, 0), LocalTime.of(11, 0))
    return RoutineWindowEvaluator().evaluate(listOf(routine), place, GeoPoint(41.9, 12.5), snapshot, now, ZoneId.of("UTC")).single()
  }
  private fun document(a: RoutineWindowAssessment, locale: Locale, at: Long): Pair<WeatherSummaryDocument, UnitFormatter> {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
    val res = base.createConfigurationContext(config).resources
    val units = UnitFormatter(res, UnitPreferences.METRIC, locale)
    val brief = WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE, at, place = place), routines = listOf(a))
    return WeatherBriefFormatter.document(res, brief, place.name, units, locale, at) to units
  }

  @Test fun futureWetAndDryWindowsRetainForecastStateAmountAndTemperatureInBothLanguages() {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    for (locale in listOf(Locale.ENGLISH, Locale.ITALIAN)) for (amount in listOf(0.0, 2.0)) {
      val a = assessment(amount)
      assertTrue(a.hasCompleteCoverage)
      assertEquals(if (amount > 0) PrecipitationState.WET else PrecipitationState.DRY, a.precipitation)
      assertTrue(a.evidence.all { it.sourceTimestampMillis!! > now })
      val (doc, units) = document(a, locale, now)
      val res = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) }).resources
      val text = doc.facts.single { it.id == "routine_0" }.text
      assertTrue(text.contains(res.getString(routineForecastRes(a))))
      assertTrue(text.contains(units.precipitation(amount)))
      assertTrue(text.contains(units.temperature(12.0)))
      assertFalse(doc.facts.single { it.id == "source_details" }.text.contains(res.getString(R.string.home_rain_stale)))
      assertTrue(doc.render().contains(text))
    }
  }

  @Test fun expiredDownloadDoesNotRetainFutureForecastCertaintyOrAmounts() {
    val at = now + 3_600_001L
    // Refresh computation time only; the provider download stays original and must expire.
    val a = assessment(2.0).copy(evaluatedAtMillis = at)
    val (doc, units) = document(a, Locale.ENGLISH, at)
    val res = InstrumentationRegistry.getInstrumentation().targetContext.createConfigurationContext(
      Configuration().apply { setLocale(Locale.ENGLISH) }).resources
    val text = doc.facts.single { it.id == "routine_0" }.text
    assertTrue(text.contains(res.getString(R.string.routines_unknown)))
    assertFalse(text.contains(units.precipitation(2.0)))
    assertFalse(text.contains(units.temperature(12.0)))
  }
}
