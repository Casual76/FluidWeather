package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R

@Composable
internal fun GlanceMetric(icon: ImageVector, value: String, label: String) {
  Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Icon(icon, null, Modifier.size(18.dp), tint = LocalContentColor.current)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

/** The same compact personal window is used by home and the sheet. No raw catalog above fold. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeMomentContent(a: RoutineWindowAssessment, now: Long, valid: Boolean) {
  val resources = LocalContext.current.resources
  val locale = LocalConfiguration.current.locales[0]
  val units = rememberUnitFormatter()
  var details by rememberSaveable(a.occurrence) { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().testTag("moment_${a.occurrence.routine.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(a.occurrence.routine.name, style = MaterialTheme.typography.titleSmall)
        Text(WeatherGlanceSummary.day(resources, a.occurrence, now, locale), style = MaterialTheme.typography.labelMedium)
      }
      FluidGlassIconButton(onClick = { details = !details }, size = 32.dp,
        grip = FluidPressGrip.YieldsToScroll,
        modifier = Modifier.testTag("moment_details_${a.occurrence.routine.id}")) {
        Icon(Icons.Rounded.Info, stringResource(if (details) R.string.glance_less else R.string.glance_details),
          Modifier.size(18.dp), tint = LocalContentColor.current)
      }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      GlanceMetric(Icons.Rounded.Schedule, WeatherGlanceSummary.time(a.occurrence, locale), stringResource(R.string.glance_time))
      if (valid) a.maxProbabilityPercent?.let {
        GlanceMetric(Icons.Rounded.WaterDrop, units.num(it, 0) + "%", stringResource(R.string.glance_probability))
      }
      if (valid) WeatherGlanceSummary.temperature(a, units)?.let {
        GlanceMetric(Icons.Rounded.Thermostat, it, stringResource(R.string.glance_temperature))
      }
    }
    // Samples are not full-window extrema, even when precipitation coverage is complete.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(if (valid && a.precipitation == PrecipitationState.DRY && WeatherGlanceSummary.partial(a))
        R.string.glance_partial_dry else WeatherGlanceSummary.stateRes(if (valid) a.precipitation else PrecipitationState.UNAVAILABLE)),
        style = MaterialTheme.typography.labelLarge)
      if (WeatherGlanceSummary.partial(a)) Text(stringResource(R.string.glance_partial), style = MaterialTheme.typography.labelMedium)
    }
    if (details) {
      Column(Modifier.testTag("moment_metadata_${a.occurrence.routine.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(routineOccurrenceLabel(a.occurrence, locale), style = MaterialTheme.typography.bodySmall)
        if (valid) {
          Text(routineForecastLabel(resources, a, locale, units, true), style = MaterialTheme.typography.bodySmall)
          if (WeatherGlanceSummary.temperature(a, units) != null)
            Text(resources.getString(R.string.summary_samples, units.temperature(a.minTemperatureC!!), units.temperature(a.maxTemperatureC!!)),
              style = MaterialTheme.typography.bodySmall)
        } else Text(stringResource(R.string.summary_refresh), style = MaterialTheme.typography.bodySmall)
        Text(routineEvidenceLabel(resources, a, locale, true), style = MaterialTheme.typography.bodySmall)
        if (!valid) routineLimitResources(a).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
}

@Composable
internal fun HomeGlanceSummaryContent(document: WeatherSummaryDocument, brief: WeatherBrief?, place: String,
  ids: List<String>?, now: Long) {
  val units = rememberUnitFormatter()
  val validBrief = brief.takeIf { WeatherGlanceSummary.matches(document, it, now) }
  val current = WeatherGlanceSummary.current(validBrief, now)
  val selected = ids?.takeIf { document.accepts(it) } ?: document.defaultFactIds
  var details by rememberSaveable(document.contextKey) { mutableStateOf(false) }
  var complete by rememberSaveable(document.contextKey) { mutableStateOf(false) }
  Text(place, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("weather_summary_text"))
  if (current != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    WeatherKindIcon(current.kind, 32.dp)
    current.temperatureC?.let { Text(units.degrees(it), style = MaterialTheme.typography.headlineLarge) }
    current.kind?.labelRes()?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
  }
  Text(document.facts.first { it.id == "rain" }.text, style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.testTag("weather_summary_rain"))
  if (WeatherGlanceSummary.transition(validBrief, now) != null)
    Text(document.facts.first { it.id == "transition" }.text, style = MaterialTheme.typography.bodyMedium)
  // Only app-authored typed facts can become cards. Provider-selected diagnostic IDs stay in Details.
  val routineIds = (selected.filter { it.startsWith("routine_") } + document.defaultFactIds.filter { it.startsWith("routine_") }).distinct()
  routineIds.forEach { id ->
    val index = id.removePrefix("routine_").toIntOrNull()
    val a = index?.let { validBrief?.routines?.getOrNull(it) }
    if (a != null && document.facts.any { it.id == id }) FluidListGroup(glass = true) {
      Box(Modifier.padding(12.dp)) { HomeMomentContent(a, now, WeatherGlanceSummary.routineUsable(a, now)) }
    }
  }
  FluidButton(stringResource(if (details) R.string.glance_less else R.string.glance_sources), { details = !details },
    style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
    modifier = Modifier.testTag("weather_summary_details"))
  if (details) {
    Text(WeatherGlanceSummary.sources(validBrief).joinToString(" · ").ifBlank { stringResource(R.string.summary_sources_unknown) },
      style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("weather_summary_sources"))
    document.facts.filter { it.id in setOf("zone", "limits", "freshness", "source_limits", "source_details", "more_routines") }
      .forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
    // Extra selected IDs are still authored facts; never render provider text.
    document.facts.filter { it.id in selected && it.id !in document.defaultFactIds && !it.required && it.id != "source_details" }
      .forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
    FluidButton(stringResource(R.string.glance_complete), { complete = !complete }, style = FluidButtonStyle.Plain,
      grip = FluidPressGrip.YieldsToScroll, modifier = Modifier.testTag("weather_summary_complete"))
    if (complete) Text(document.facts.joinToString("\n") { it.text }, style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.testTag("weather_summary_metadata"))
  }
}
