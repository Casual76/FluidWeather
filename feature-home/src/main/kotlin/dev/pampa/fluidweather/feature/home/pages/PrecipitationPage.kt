package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherZones
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.feature.home.*
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.accessibilitySampleTime
import java.time.Instant
import java.time.ZoneId

/** Shared present evidence is separate from the browsable model forecast. */
@Composable
fun PrecipitationPage(state: HomeUiState) {
  HomeRainOverview(state.weatherBrief, state.locationName ?: stringResource(R.string.summary_place_unknown),
    showCurrent = false, showForecast = false)
  PrecipitationForecastSamples(state)
}

/** Default: six hours, one chart, no expanded table or diagnostic prose. Also used by the home tile. */
@Composable
internal fun PrecipitationForecastSamples(state: HomeUiState, compact: Boolean = false) {
  val now = System.currentTimeMillis()
  val start = now - Math.floorMod(now, ForecastRainSamples.HOUR) + ForecastRainSamples.HOUR
  val upcoming = state.fusedHours.filter { it.timestampMillis >= start }
  val zone = WeatherZones.resolve(state.weatherBrief?.precipitation?.place?.zoneId, null, ZoneId.systemDefault()).zoneId
  val locale = LocalConfiguration.current.locales[0]
  val next6 = ForecastRainSamples.slots(upcoming, start, start + 6 * ForecastRainSamples.HOUR)
  if (compact) {
    PageSection(stringResource(R.string.precip_next_6h))
    Text(stringResource(R.string.home_forecast_rain_peak, probability(next6.mapNotNull { it.probability }.maxOrNull())),
      style = MaterialTheme.typography.titleMedium)
    ProbabilityChart(next6, zone, compact = true)
    return
  }

  // Every original position remains reachable; only one small window is composed at a time.
  val end = upcoming.maxOfOrNull { it.timestampMillis }?.plus(ForecastRainSamples.HOUR) ?: start
  val all = ForecastRainSamples.slots(upcoming, start, end)
  val days = all.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toSortedMap()
  var selectedDay by rememberSaveable(state.locationName) { mutableStateOf<String?>(null) }
  var window by rememberSaveable(selectedDay, state.locationName) { mutableIntStateOf(0) }
  var hoursExpanded by rememberSaveable(state.locationName) { mutableStateOf(false) }
  var sourcesExpanded by rememberSaveable(state.locationName) { mutableStateOf(false) }
  val day = days.keys.firstOrNull { it.toString() == selectedDay }
  val daySlots = day?.let { days[it] }.orEmpty()
  val windows = daySlots.chunked(6)
  val page = window.coerceIn(0, (windows.size - 1).coerceAtLeast(0))
  val slots = if (day == null) next6 else windows[page]
  val period = if (day == null) stringResource(R.string.precip_next_6h) else TimeFormats.longDate(day, locale)
  val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

  if (days.isNotEmpty()) LazyRow(
    modifier = Modifier.fillMaxWidth().testTag("precip_day_selector"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(vertical = 4.dp),
  ) {
    item {
      FluidChip(stringResource(R.string.precip_next_6h), selected = day == null,
        onClick = { selectedDay = null }, modifier = Modifier.testTag("precip_next_six"))
    }
    itemsIndexed(days.keys.toList(), key = { _, date -> date.toString() }) { index, date ->
      val label = if (date == today) stringResource(R.string.precip_quality_today) else TimeFormats.shortDate(date, locale)
      val fullDate = TimeFormats.longDate(date, locale)
      FluidChip(label, selected = day == date,
        onClick = { selectedDay = date.toString() }, modifier = Modifier.testTag("precip_day_$index")
          .semantics { contentDescription = fullDate })
    }
  }
  val summarySlots = if (day == null) next6 else daySlots
  FluidCard(Modifier.testTag("precip_forecast_summary"), animateContent = false) {
    Text(period, style = MaterialTheme.typography.titleMedium)
    Text(probability(summarySlots.mapNotNull { it.probability }.maxOrNull()),
      style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.testTag("precip_forecast_peak"))
    Text(stringResource(R.string.precip_quality_peak), style = MaterialTheme.typography.bodyMedium)
    val available = summarySlots.count { it.probability != null }
    if (available != summarySlots.size) Text(stringResource(R.string.precip_quality_coverage, available, summarySlots.size),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SampleSum(summarySlots)
  }
  Spacer(Modifier.height(12.dp))
  if (day != null) {
    Text(stringResource(R.string.precip_quality_window,
      TimeFormats.time(slots.first().at, zone, locale), TimeFormats.time(slots.last().at, zone, locale)),
      style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      FluidButton(stringResource(R.string.precip_quality_earlier), { window = page - 1 }, enabled = page > 0,
        style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
        modifier = Modifier.testTag("precip_earlier"))
      FluidButton(stringResource(R.string.precip_quality_later), { window = page + 1 }, enabled = page < windows.lastIndex,
        style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
        modifier = Modifier.testTag("precip_later"))
    }
  }
  ProbabilityChart(slots, zone)
  if (slots.any { it.probability != null } && slots.any { it.probability == null }) Text(stringResource(R.string.precip_quality_legend),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

  val hoursDescription = stringResource(if (hoursExpanded) R.string.precip_quality_expanded else R.string.precip_quality_collapsed)
  val sourcesDescription = stringResource(if (sourcesExpanded) R.string.precip_quality_expanded else R.string.precip_quality_collapsed)
  FluidButton(stringResource(if (hoursExpanded) R.string.precip_quality_hide_hours else R.string.precip_quality_show_hours),
    { hoursExpanded = !hoursExpanded }, style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
    modifier = Modifier.testTag("precip_hours_toggle").semantics { stateDescription = hoursDescription })
  if (hoursExpanded) HourTable(slots, zone)

  FluidButton(stringResource(R.string.precip_quality_sources), { sourcesExpanded = !sourcesExpanded },
    style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
    modifier = Modifier.testTag("precip_sources_toggle").semantics { stateDescription = sourcesDescription })
  if (sourcesExpanded) ForecastSources(state, zone)
}

private fun probability(value: Double?): String = precipitationProbability(value)

@Composable
private fun SampleSum(slots: List<ForecastRainSlot>) {
  val sum = ForecastRainSamples.sum(slots)
  val units = rememberUnitFormatter()
  val amountText = sum.value?.let { precipitationSampleValue(it, units) + " " + units.precipitationSymbol() }
  val accessible = if (sum.value == null) stringResource(R.string.accessibility_sample_unknown, sum.positions)
    else stringResource(R.string.accessibility_sample_sum, amountText!!, sum.available, sum.positions)
  Column(Modifier.padding(top = 12.dp).clearAndSetSemantics { contentDescription = accessible }) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.precip_quality_sample_sum), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f))
      Text(amountText ?: "\u2014", style = MaterialTheme.typography.titleMedium)
    }
    if (sum.available != sum.positions) Text(stringResource(R.string.precip_quality_amount_coverage, sum.available, sum.positions),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Fixed 0–100% scale, elapsed-time x positions, missing crosses and an explicit zero mark. */
@Composable
private fun ProbabilityChart(slots: List<ForecastRainSlot>, zone: ZoneId, compact: Boolean = false) {
  if (slots.isEmpty()) return
  if (slots.none { it.probability != null }) {
    Text(stringResource(R.string.precip_quality_probability_unknown), style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("precip_empty"))
    return
  }
  val locale = LocalConfiguration.current.locales[0]
  val unknown = stringResource(R.string.home_rain_timeline_unknown)
  val labels = slots.map { slot -> stringResource(R.string.accessibility_probability_sample,
    accessibilitySampleTime(slot.at, zone, locale), slot.probability?.let(::probability) ?: unknown) }
  val colors = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth()) {
    if (!compact) Text(stringResource(R.string.precip_quality_scale), style = MaterialTheme.typography.labelSmall,
      color = colors.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(if (compact) 64.dp else 96.dp)
      .testTag("precip_probability_chart").semantics { contentDescription = labels.joinToString("; ") }) {
      val baseline = size.height - 5.dp.toPx()
      val top = 5.dp.toPx()
      val span = (slots.last().at - slots.first().at + ForecastRainSamples.HOUR).toFloat()
      val cell = ForecastRainSamples.HOUR / span * size.width
      for (fraction in listOf(0f, 0.5f, 1f)) {
        val y = baseline - (baseline - top) * fraction
        drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
      }
      slots.forEach { slot ->
        val x = (slot.at - slots.first().at) / span * size.width + cell * 0.2f
        val width = cell * 0.6f
        val p = slot.probability
        when {
          p == null -> {
            val center = x + width / 2
            val half = 3.dp.toPx()
            drawLine(colors.onSurfaceVariant, Offset(center - half, baseline - half), Offset(center + half, baseline + half), 2.dp.toPx())
            drawLine(colors.onSurfaceVariant, Offset(center - half, baseline + half), Offset(center + half, baseline - half), 2.dp.toPx())
          }
          p == 0.0 -> drawCircle(colors.primary, 2.dp.toPx(), Offset(x + width / 2, baseline))
          else -> {
            val height = ((baseline - top) * p.toFloat() / 100f).coerceAtLeast(3.dp.toPx())
            drawRect(colors.primary, Offset(x, baseline - height), Size(width, height))
          }
        }
      }
    }
    if (!compact) Row(Modifier.fillMaxWidth()) {
      slots.forEachIndexed { index, slot ->
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(probability(slot.probability), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("precip_bar_value_$index"), color = colors.onSurface)
          Text(TimeFormats.hour(slot.at, zone, locale), style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant)
        }
      }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      listOf(slots.first().at, slots.last().at).distinct().forEach {
        Text(TimeFormats.time(it, zone, locale), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun HourTable(slots: List<ForecastRainSlot>, zone: ZoneId) {
  val locale = LocalConfiguration.current.locales[0]
  val units = rememberUnitFormatter()
  val unknown = stringResource(R.string.home_rain_timeline_unknown)
  Text(stringResource(R.string.precip_quality_hour_columns), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
  FluidListGroup(Modifier.testTag("precip_hour_table")) {
    slots.forEachIndexed { index, slot ->
      if (index > 0) FluidListDivider()
      val p = slot.probability?.let(::probability) ?: unknown
      val amount = slot.amount?.let { precipitationSampleValue(it, units) + " " + units.precipitationSymbol() } ?: unknown
      val stamp = accessibilitySampleTime(slot.at, zone, locale)
      val description = stringResource(R.string.accessibility_probability_sample, stamp, p) + "; " +
        stringResource(R.string.accessibility_amount_sample, stamp, amount)
      FluidListRow(title = TimeFormats.time(slot.at, zone, locale),
        subtitle = stringResource(R.string.precip_quality_hour_values, p, amount),
        modifier = Modifier.testTag("precip_hour_$index").clearAndSetSemantics { contentDescription = description })
    }
  }
}

@Composable
private fun ForecastSources(state: HomeUiState, zone: ZoneId) {
  val locale = LocalConfiguration.current.locales[0]
  val providers = state.fusedHours.flatMap { hour ->
    listOf(FusionVariables.PRECIPITATION, FusionVariables.PRECIP_PROBABILITY)
      .flatMap { hour.values[it]?.contributions.orEmpty() }
  }.map { it.providerId }.distinct().sorted()
  FluidCard(Modifier.testTag("precip_sources_details"), animateContent = false) {
    Text(stringResource(R.string.precip_quality_sources), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.precip_quality_method), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.precip_quality_provider_list,
      providers.joinToString().ifEmpty { stringResource(R.string.home_rain_timeline_unknown) }),
      style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.precip_quality_download,
      state.dataAtMillis?.let { accessibilitySampleTime(it, zone, locale) }
        ?: stringResource(R.string.home_rain_timeline_unknown)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.precip_quality_interval_limit), style = MaterialTheme.typography.bodySmall)
    state.verdict?.let { verdict ->
      PageSection(stringResource(R.string.accessibility_diagnostic))
      verdict.windows.forEach { window ->
        StatRow(windowLabel(window.window), "${(window.probability * 100).toInt()}%",
          stringResource(R.string.precip_local_barometer))
      }
      Text(stringResource(R.string.precip_windows_note), style = MaterialTheme.typography.bodySmall)
    }
  }
}
