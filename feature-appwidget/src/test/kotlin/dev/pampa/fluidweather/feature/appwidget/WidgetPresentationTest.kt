package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
  private val zone = ZoneId.of("Europe/Rome")
  private val date = LocalDate.of(2026, 11, 9)
  private val now = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
  private fun hours(day: LocalDate = date): List<FusedHour> {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return generateSequence(start) { it + 3_600_000L }.takeWhile { it < end }.mapIndexed { i, at ->
      FusedHour(at, mapOf(FusionVariables.TEMPERATURE to FusedValue(i.toDouble(), emptyList())), WeatherKind.CLOUDY)
    }.toList()
  }

  @Test fun `minimum cell spends no space on forecasts or footer`() {
    val density = WidgetDensity.of(110f, 110f)
    assertTrue(density.compact)
    assertEquals(0, density.hourCount)
    assertEquals(0, density.dayCount)
    assertFalse(density.showFooter)
    assertTrue(WidgetDensity.of(110f, 400f).compact)
  }

  @Test fun `short wide cell is hero only and extra height buys bounded details`() {
    assertEquals(0, WidgetDensity.of(250f, 110f).hourCount)
    assertEquals(4, WidgetDensity.of(250f, 200f).hourCount)
    assertEquals(0, WidgetDensity.of(250f, 200f).dayCount)
    assertEquals(5, WidgetDensity.of(250f, 340f).dayCount)
    assertEquals(5, WidgetDensity.of(560f, 900f).dayCount)
  }

  @Test fun `age never treats absent or future timestamps as fresh`() {
    assertNull(widgetAgeMinutes(null, now))
    assertNull(widgetAgeMinutes(now + 1, now))
    assertNull(widgetAgeMinutes(0, now))
    assertEquals(90L, widgetAgeMinutes(now - 90 * 60_000L, now))
  }

  @Test fun `full day has forecast extrema but partial missing and duplicate days do not`() {
    val full = hours()
    assertEquals(0.0, widgetDays(full, now, zone).single().minC!!, 0.0)
    assertEquals(23.0, widgetDays(full, now, zone).single().maxC!!, 0.0)
    listOf(full.drop(1), full.dropLast(1), full.filterIndexed { i, _ -> i != 10 },
      full + full[10], full.mapIndexed { i, h -> if (i == 10) h.copy(values = emptyMap()) else h },
      full.mapIndexed { i, h -> if (i == 10) h.copy(values = mapOf(
        FusionVariables.TEMPERATURE to FusedValue(Double.NaN, emptyList()))) else h },
    ).forEach {
      val day = widgetDays(it, now, zone).single()
      assertNull(day.minC)
      assertNull(day.maxC)
    }
  }

  @Test fun `local day boundaries accept both daylight saving day lengths`() {
    listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25)).forEach { day ->
      val yesterday = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
      assertNotNull(widgetDays(hours(day), yesterday, zone).single().maxC)
    }
  }

  @Test fun `days are local future dates sorted with a five day ceiling`() {
    val all = (-1L..6L).flatMap { hours(date.plusDays(it)) }.reversed()
    val result = widgetDays(all, now, zone)
    assertEquals((0L..4L).map(date::plusDays), result.map { it.date })
  }

  @Test fun `reference widths buy six hours and tall sizes buy five days`() {
    assertEquals(6, WidgetDensity.of(350f, 180f).hourCount)
    assertEquals(0, WidgetDensity.of(350f, 180f).dayCount)
    assertEquals(6, WidgetDensity.of(350f, 350f).hourCount)
    assertEquals(5, WidgetDensity.of(350f, 350f).dayCount)
  }

  @Test fun `range bars share domain and never turn missing or inverted into zero`() {
    fun day(low: Double?, high: Double?) = AppWidgetDay(date, low, high, 0)
    val cold = day(-10.0, 0.0)
    val warm = day(0.0, 30.0)
    val domain = widgetRangeDomain(listOf(cold, warm, day(null, 100.0)))!!
    assertEquals(-10.0, domain.start, 0.0)
    assertEquals(30.0, domain.endInclusive, 0.0)
    assertEquals(0f to 0.25f, widgetRangeFractions(cold, domain))
    assertEquals(0.25f to 1f, widgetRangeFractions(warm, domain))
    assertNull(widgetRangeFractions(day(null, 0.0), domain))
    assertNull(widgetRangeFractions(day(1.0, 0.0), domain))
    assertNull(widgetRangeDomain(listOf(day(Double.NaN, 0.0))))
    assertEquals(0.5f to 0.5f, widgetRangeFractions(day(0.0, 0.0), widgetRangeDomain(listOf(day(0.0, 0.0)))))
  }
}
