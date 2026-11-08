package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Explicit budgets for static Glance layouts; no lazy lists or hidden overflow. */
internal data class WidgetDensity(
  val compact: Boolean,
  val padding: Int,
  val hourCount: Int,
  val dayCount: Int,
  val showFooter: Boolean,
) {
  companion object {
    fun of(widthDp: Float, heightDp: Float): WidgetDensity = when {
      widthDp < 180 -> WidgetDensity(true, 8, 0, 0, false)
      heightDp < 170 -> WidgetDensity(false, 12, 0, 0, false)
      heightDp < 330 -> WidgetDensity(false, 12, if (widthDp >= 320) 6 else 4, 0, true)
      else -> WidgetDensity(false, 12, if (widthDp >= 320) 6 else 4, 5, true)
    }
  }
}

/** Relative age at composition time, never a ticking claim of live weather. */
internal fun widgetAgeMinutes(dataAt: Long?, now: Long): Long? =
  dataAt?.takeIf { it > 0 && it <= now }?.let { (now - it) / 60_000L }

data class AppWidgetDay(
  val date: LocalDate,
  val minC: Double?,
  val maxC: Double?,
  val iconRes: Int,
)

/** Forecast-only future local days. Incomplete days retain unknown extrema, not zero. */
internal fun widgetDays(hours: List<FusedHour>, now: Long, zone: ZoneId): List<AppWidgetDay> {
  val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
  return hours.groupBy { it.timestampMillis }.values.mapNotNull { it.singleOrNull() }
    .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
    .filterKeys { it > today }.toSortedMap().entries.take(5).map { (date, samples) ->
      val ordered = samples.sortedBy { it.timestampMillis }
      val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
      val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
      val temperatures = ordered.map { it.values[FusionVariables.TEMPERATURE]?.value?.takeIf(Double::isFinite) }
      val complete = ordered.first().timestampMillis <= start + 3_599_999L &&
        ordered.last().timestampMillis >= end - 3_600_000L &&
        ordered.zipWithNext().all { (a, b) -> b.timestampMillis - a.timestampMillis <= 3_600_000L } &&
        temperatures.all { it != null }
      val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
      val kind = ordered.minByOrNull { kotlin.math.abs(it.timestampMillis - noon) }?.kind
      AppWidgetDay(date,
        if (complete) temperatures.filterNotNull().minOrNull() else null,
        if (complete) temperatures.filterNotNull().maxOrNull() else null,
        kind.appWidgetIconRes(DayPhase.DAY))
    }
}

/** One shared Celsius domain; incomplete days never contribute invented endpoints. */
internal fun widgetRangeDomain(days: List<AppWidgetDay>): ClosedFloatingPointRange<Double>? {
  val complete = days.filter { day ->
    day.minC?.isFinite() == true && day.maxC?.isFinite() == true && day.minC <= day.maxC
  }
  val low = complete.minOfOrNull { it.minC!! } ?: return null
  val high = complete.maxOf { it.maxC!! }
  // Constant-temperature days still have a visible, centered interval.
  return if (low == high) (low - 0.5)..(high + 0.5) else low..high
}

internal fun widgetRangeFractions(day: AppWidgetDay, domain: ClosedFloatingPointRange<Double>?): Pair<Float, Float>? {
  val low = day.minC?.takeIf(Double::isFinite) ?: return null
  val high = day.maxC?.takeIf(Double::isFinite) ?: return null
  if (domain == null || low > high) return null
  val span = domain.endInclusive - domain.start
  if (!span.isFinite() || span <= 0) return null
  return ((low - domain.start) / span).toFloat().coerceIn(0f, 1f) to
    ((high - domain.start) / span).toFloat().coerceIn(0f, 1f)
}
