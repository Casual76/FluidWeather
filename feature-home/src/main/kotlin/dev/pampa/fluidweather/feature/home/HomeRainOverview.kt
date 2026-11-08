package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pampa.fluidweather.core.model.*
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.pampa.fluidweather.core.ui.GlassTile
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Fixed overview: no click/drag action, no source fetch, no reserved height or truncated text. */
@Composable
fun HomeRainOverview(
  brief: WeatherBrief?,
  locationName: String,
  modifier: Modifier = Modifier,
  /** Injected only by focused UI tests; production follows the consumption clock. */
  nowMillis: Long? = null,
  onOpenSummary: (() -> Unit)? = null,
) {
  var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle, nowMillis) {
    if (nowMillis != null) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) { clock = System.currentTimeMillis(); delay(30_000) }
    }
  }
  val now = nowMillis ?: clock
  val config = LocalConfiguration.current
  val locale = config.locales[0]
  val zone = WeatherZones.resolve(brief?.precipitation?.place?.zoneId, null, ZoneId.systemDefault())
  val units = rememberUnitFormatter()
  val current = brief?.current
  val headline = HomeRainPresentation.headline(brief)
  val limits = brief?.precipitation?.let { it.limits + it.evidence.flatMap { e -> e.limits } }.orEmpty()
  var sourcesExpanded by rememberSaveable(locationName) { mutableStateOf(false) }
  val headlineText = stringResource(when (headline) {
    RainHeadline.MODEL_WET -> R.string.home_rain_model_wet
    RainHeadline.MODEL_DRY -> R.string.home_rain_model_dry
    RainHeadline.RADAR_WET -> R.string.home_rain_radar_wet
    RainHeadline.RADAR_NO_ECHO -> R.string.home_rain_radar_no_echo
    RainHeadline.WET -> R.string.home_rain_wet
    RainHeadline.DRY -> R.string.home_rain_dry
    RainHeadline.CONFLICT -> R.string.home_rain_conflict
    RainHeadline.UNCERTAIN -> R.string.home_rain_uncertain
    RainHeadline.UNAVAILABLE -> R.string.home_rain_unavailable
  })
  GlassTile(modifier.testTag("home_rain_overview")) {
    // A themed tonal band gives the answer a stable contrast base over bright and dark skies.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
      MaterialTheme.shapes.large).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(locationName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
      Text(
        if (current != null && (current.temperatureC != null || current.kind != null)) stringResource(
          if (current.evidence.any { it.type == WeatherEvidenceType.MODEL }) R.string.home_rain_current_model else R.string.home_rain_current,
          current.temperatureC?.let { units.degrees(it) } ?: stringResource(R.string.home_rain_timeline_unknown),
          current.kind?.labelRes()?.let { stringResource(it) } ?: stringResource(R.string.home_rain_timeline_unknown),
        ) else stringResource(R.string.home_rain_current_unknown),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(headlineText, style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() }.testTag("home_rain_headline"))
      val transition = HomeRainPresentation.transition(brief, now)
      Text(if (transition != null) stringResource(if (transition.arrival) R.string.home_rain_arrival else R.string.home_rain_end,
        TimeFormats.time(transition.startMillis, zone.zoneId, locale), TimeFormats.time(transition.endMillis, zone.zoneId, locale))
        else stringResource(R.string.home_rain_no_eta), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface)
      Text(stringResource(when {
        headline == RainHeadline.UNAVAILABLE -> R.string.home_rain_unknown_limit
        headline in setOf(RainHeadline.CONFLICT, RainHeadline.UNCERTAIN) -> R.string.home_rain_conflict_limit
        headline in setOf(RainHeadline.MODEL_WET, RainHeadline.MODEL_DRY) -> R.string.home_rain_model_limit
        else -> R.string.home_rain_radar_limit
      }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      RainTimeline(brief, now, zone.zoneId)
    }
    brief?.precipitation?.radarAvailability?.takeIf { it != RadarAvailability.AVAILABLE }?.let { radar ->
      Text(stringResource(when (radar) {
        RadarAvailability.OUTSIDE -> R.string.home_rain_radar_outside
        RadarAvailability.UNKNOWN -> R.string.home_rain_radar_unknown
        else -> R.string.home_rain_radar_unavailable
      }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val resources = LocalContext.current.resources
    // Distinct source facts remain distinct even when the types match (e.g. old quarter/current).
    val evidence = (brief?.precipitation?.evidence.orEmpty() + current?.evidence.orEmpty()).distinct()
    evidence.groupBy { it.sourceId }.forEach { (id, sources) ->
      val at = sources.mapNotNull { it.sourceTimestampMillis ?: it.effectiveInterval?.endMillis }.minOrNull()
      if (at != null && at <= now) {
        Text(stringResource(R.string.home_rain_source_summary, id,
          TimeFormats.time(at, zone.zoneId, locale), (now - at) / 60_000L),
          style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(top = 4.dp))
      }
    }
    // Flow layout keeps both actions compact, and wraps at large font sizes.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (onOpenSummary != null) FluidButton(
        text = stringResource(R.string.weather_summary_ui_title), onClick = onOpenSummary,
        style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
        modifier = Modifier.heightIn(min = 48.dp).testTag("home_summary_open"),
      )
      if (evidence.isNotEmpty()) {
        val expandedLabel = stringResource(if (sourcesExpanded) R.string.home_rain_sources_expanded else R.string.home_rain_sources_collapsed)
        FluidButton(
          text = stringResource(R.string.home_rain_sources),
          onClick = { sourcesExpanded = !sourcesExpanded }, style = FluidButtonStyle.Plain,
          grip = FluidPressGrip.YieldsToScroll,
          modifier = Modifier.heightIn(min = 48.dp).testTag("home_rain_sources_toggle").semantics { stateDescription = expandedLabel },
        )
      }
    }
    if (sourcesExpanded) Column(Modifier.testTag("home_rain_sources_details")) {
      evidence.forEach { source ->
        Text(rainSourceLabel(resources, source, now, zone.zoneId, locale),
          style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(top = 4.dp))
      }
    }
    if (WeatherEvidenceLimit.PARTIAL_COVERAGE in limits) Text(stringResource(R.string.routines_partial), style = MaterialTheme.typography.labelSmall)
    if (WeatherEvidenceLimit.UNKNOWN_PROVENANCE in limits) Text(stringResource(R.string.routines_provenance_unknown), style = MaterialTheme.typography.labelSmall)
    if (WeatherEvidenceLimit.STALE in limits) Text(stringResource(R.string.home_rain_stale), style = MaterialTheme.typography.labelSmall)
    if (zone.source == WeatherZoneSource.DEVICE_FALLBACK) Text(
      stringResource(R.string.home_rain_zone_fallback, zone.zoneId.id), style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun RainTimeline(brief: WeatherBrief?, now: Long, zone: ZoneId) {
  val parts = HomeRainPresentation.timeline(brief, now)
  val locale = LocalConfiguration.current.locales[0]
  val units = rememberUnitFormatter()
  val unknown = stringResource(R.string.home_rain_timeline_unknown)
  val wet = stringResource(R.string.home_rain_timeline_wet)
  val dry = stringResource(R.string.home_rain_timeline_dry)
  val uncertain = stringResource(R.string.home_rain_timeline_uncertain)
  val descriptions = parts.map { part ->
    val label = when (part.period?.state) {
      PrecipitationState.WET -> wet
      PrecipitationState.DRY -> dry
      PrecipitationState.UNCERTAIN -> uncertain
      else -> unknown
    }
    val interval = accessibleIntervalLabel(LocalContext.current.resources,
      part.interval.startMillis, part.interval.endMillis, zone, locale)
    val resolutions = part.period?.evidence.orEmpty().mapNotNull { it.temporalResolutionMillis }.distinct()
    val precision = if (resolutions.isEmpty()) stringResource(R.string.accessibility_resolution_unknown)
      else stringResource(R.string.accessibility_resolution, resolutions.joinToString(" / ") { "${it / 1000.0} s" })
    interval + ": " + (part.period?.amountMm?.let { "$label \u00b7 ${units.precipitation(it)}" } ?: label) + ". " + precision
  }
  val title = stringResource(R.string.home_rain_hour)
  val colors = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().testTag("home_rain_timeline")
    .clearAndSetSemantics { contentDescription = title + ". " + descriptions.joinToString("; ") }) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
    Canvas(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(14.dp)) {
      parts.forEach { part ->
        val left = (part.interval.startMillis - now) / 3_600_000f * size.width
        val width = (part.interval.endMillis - part.interval.startMillis) / 3_600_000f * size.width
        val known = part.period != null
        drawRect(if (!known) colors.outlineVariant else when (part.period?.state) {
          PrecipitationState.WET -> colors.primary
          PrecipitationState.DRY -> colors.secondaryContainer
          else -> colors.tertiary
        }, Offset(left, 0f), Size((width - 2.dp.toPx()).coerceAtLeast(0f), size.height))
        if (!known) drawLine(colors.onSurfaceVariant, Offset(left, size.height), Offset(left + width, 0f), 1.dp.toPx())
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      listOf(now, now + 1_800_000L, now + 3_600_000L).forEach {
        Text(TimeFormats.time(it, zone, locale), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
      }
    }
    // One readable legend also explains the pattern to sighted users; the semantics above read each interval once.
    val legend = parts.map { part -> when (part.period?.state) {
      PrecipitationState.WET -> wet
      PrecipitationState.DRY -> dry
      PrecipitationState.UNCERTAIN -> uncertain
      else -> unknown
    } }.distinct()
    Text(legend.joinToString(" \u00b7 "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
  }
}
