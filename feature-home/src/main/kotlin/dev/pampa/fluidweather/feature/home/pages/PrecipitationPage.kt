package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.WeatherZones
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.feature.home.*
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.accessibilitySampleTime
import java.time.Instant
import java.time.ZoneId

/** Shared present evidence remains reachable even when hourly forecast details are absent. */
@Composable
fun PrecipitationPage(state: HomeUiState) {
  HomeRainOverview(state.weatherBrief, state.locationName ?: stringResource(R.string.summary_place_unknown))
  PrecipitationForecastSamples(state)
}

@Composable
internal fun PrecipitationForecastSamples(state: HomeUiState, compact: Boolean = false) {
  val now = System.currentTimeMillis()
  val start = now - Math.floorMod(now, ForecastRainSamples.HOUR) + ForecastRainSamples.HOUR
  val upcoming = state.fusedHours.filter { it.timestampMillis >= start }
  val zone = WeatherZones.resolve(state.weatherBrief?.precipitation?.place?.zoneId, null, ZoneId.systemDefault()).zoneId
  val locale = LocalConfiguration.current.locales[0]
  val next6 = ForecastRainSamples.slots(upcoming, start, start + 6 * ForecastRainSamples.HOUR)
  PageSection(stringResource(R.string.precip_next_6h))
  SampleSum(next6)
  SampleGraph(next6, zone)
  if (compact) return

  // Diagnostics retain the original model probabilities; never substitute them for shared rain.
  state.verdict?.let { verdict ->
    PageSection(stringResource(R.string.accessibility_diagnostic))
    verdict.windows.forEach { window ->
      StatRow(windowLabel(window.window), "${(window.probability * 100).toInt()}%",
        stringResource(R.string.precip_local_barometer))
    }
    PageNote(stringResource(R.string.precip_windows_note))
  }
  if (upcoming.isEmpty()) {
    PageNote(stringResource(R.string.common_waiting_providers))
    return
  }
  val end = upcoming.maxOf { it.timestampMillis } + ForecastRainSamples.HOUR
  val all = ForecastRainSamples.slots(upcoming, start, end)
  PageSection(stringResource(R.string.precip_hourly_title))
  SampleGraph(all, zone)
  val units = rememberUnitFormatter()
  all.forEach { slot ->
    Text(stringResource(R.string.accessibility_amount_sample,
      accessibilitySampleTime(slot.at, zone, locale),
      slot.amount?.let { units.precipitation(it) } ?: stringResource(R.string.home_rain_timeline_unknown)),
      style = MaterialTheme.typography.bodySmall)
  }
  PageSection(stringResource(R.string.precip_daily_title))
  all.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toSortedMap().forEach { (day, slots) ->
    Text(dev.pampa.fluidweather.strings.TimeFormats.longDate(day, locale), style = MaterialTheme.typography.titleSmall)
    SampleSum(slots)
    val available = slots.mapNotNull { it.probability }
    Text(stringResource(R.string.accessibility_available_max,
      available.maxOrNull()?.let { "${it.toInt()}%" } ?: stringResource(R.string.home_rain_timeline_unknown),
      available.size, slots.size), style = MaterialTheme.typography.bodySmall)
  }
  PageSection(stringResource(R.string.precip_total))
  SampleSum(all)
  PageNote(stringResource(R.string.precip_method_note))
}

@Composable
private fun SampleSum(slots: List<ForecastRainSlot>) {
  val sum = ForecastRainSamples.sum(slots)
  val units = rememberUnitFormatter()
  val value = sum.value
  PageNote(if (value == null) stringResource(R.string.accessibility_sample_unknown, sum.positions)
    else stringResource(R.string.accessibility_sample_sum, units.precipitation(value), sum.available, sum.positions))
}

/** Elapsed-time x positions; unknown slots are crossed, true zero has a baseline mark. */
@Composable
private fun SampleGraph(slots: List<ForecastRainSlot>, zone: ZoneId) {
  if (slots.isEmpty()) return
  val locale = LocalConfiguration.current.locales[0]
  val unknown = stringResource(R.string.home_rain_timeline_unknown)
  val labels = slots.map { slot -> stringResource(R.string.accessibility_probability_sample,
    accessibilitySampleTime(slot.at, zone, locale), slot.probability?.let { "${it.toInt()}%" } ?: unknown) }
  val colors = MaterialTheme.colorScheme
  val width = 80.dp * LocalDensity.current.fontScale *
    ((slots.last().at - slots.first().at).toFloat() / ForecastRainSamples.HOUR + 1f)
  Column(Modifier.horizontalScroll(rememberScrollState())) {
    Canvas(Modifier.width(width).height(70.dp).semantics { contentDescription = labels.joinToString("; ") }) {
      val span = (slots.last().at - slots.first().at + ForecastRainSamples.HOUR).toFloat()
      val cell = ForecastRainSamples.HOUR / span * size.width
      slots.forEach { slot ->
        val x = (slot.at - slots.first().at) / span * size.width
        val p = slot.probability
        if (p == null) {
          drawLine(colors.onSurfaceVariant, Offset(x, size.height), Offset(x + cell * 0.6f, 0f), 2.dp.toPx())
        } else {
          val height = (size.height * p.toFloat() / 100f).coerceAtLeast(2.dp.toPx())
          drawRect(colors.primary, Offset(x, size.height - height), Size(cell * 0.6f, height))
        }
      }
    }
  }
  // Text gives each slot its exact clock, units and unknown/zero distinction without tiny columns.
  labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
}
