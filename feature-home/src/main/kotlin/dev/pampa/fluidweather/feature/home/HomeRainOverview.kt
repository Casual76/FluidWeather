package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.GlassTile
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

/** Current evidence and a qualified future forecast share one compact, non-interactive surface. */
@Composable
fun HomeRainOverview(
  brief: WeatherBrief?,
  locationName: String,
  modifier: Modifier = Modifier,
  /** Injected only by focused UI tests; production follows the consumption clock. */
  nowMillis: Long? = null,
  onOpenSummary: (() -> Unit)? = null,
  showCurrent: Boolean = true,
  /** Detail pages with their own forecast chart only need the shared rain-now status. */
  showForecast: Boolean = true,
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
  val locale = LocalConfiguration.current.locales[0]
  val zone = WeatherZones.resolve(brief?.precipitation?.place?.zoneId, null, ZoneId.systemDefault())
  val units = rememberUnitFormatter()
  val current = brief?.current
  val headline = HomeRainPresentation.headline(brief)
  val forecast = HomeRainPresentation.forecast(brief, now)
  val hasForecast = showForecast && forecast.kind != RainForecastKind.NONE
  val forecastLeads = headline == RainHeadline.UNAVAILABLE && hasForecast
  val limits = (brief?.precipitation?.limits.orEmpty() + brief?.precipitation?.evidence.orEmpty().flatMap { it.limits }).toSet()
  val evidence = (brief?.precipitation?.evidence.orEmpty() + current?.evidence.orEmpty() +
    brief?.timeline.orEmpty().flatMap { it.evidence }).distinct()
  var sourcesExpanded by rememberSaveable(locationName) { mutableStateOf(false) }
  val colors = MaterialTheme.colorScheme
  val headlineText = stringResource(if (forecastLeads) when (forecast.kind) {
    RainForecastKind.DRY -> if (forecast.hasGaps) R.string.rain_quality_forecast_partial_dry else R.string.rain_quality_forecast_dry
    RainForecastKind.WET -> R.string.rain_quality_forecast_wet
    RainForecastKind.UNCERTAIN -> R.string.rain_quality_forecast_uncertain
    RainForecastKind.NONE -> R.string.rain_quality_now_unknown
  } else when (headline) {
    RainHeadline.MODEL_WET -> R.string.home_rain_model_wet
    RainHeadline.MODEL_DRY -> R.string.home_rain_model_dry
    RainHeadline.RADAR_WET -> R.string.home_rain_radar_wet
    RainHeadline.RADAR_NO_ECHO -> R.string.home_rain_radar_no_echo
    RainHeadline.WET -> R.string.home_rain_wet
    RainHeadline.DRY -> R.string.home_rain_dry
    RainHeadline.CONFLICT -> R.string.home_rain_conflict
    RainHeadline.UNCERTAIN -> R.string.home_rain_uncertain
    RainHeadline.UNAVAILABLE -> R.string.rain_quality_now_unknown
  })
  GlassTile(modifier.testTag("home_rain_overview")) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (showCurrent) {
        Text(locationName, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        if (current != null && (current.temperatureC != null || current.kind != null)) Text(
          stringResource(if (current.evidence.any { it.type == WeatherEvidenceType.MODEL })
            R.string.home_rain_current_model else R.string.home_rain_current,
            current.temperatureC?.let { units.degrees(it) } ?: stringResource(R.string.home_rain_timeline_unknown),
            current.kind?.labelRes()?.let { stringResource(it) } ?: stringResource(R.string.home_rain_timeline_unknown)),
          style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
      }
      if (showCurrent || forecastLeads) Text(
        stringResource(if (forecastLeads) R.string.rain_quality_forecast_title else R.string.rain_quality_title),
        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
      Text(headlineText, style = MaterialTheme.typography.titleMedium, color = colors.onSurface,
        modifier = Modifier.semantics { heading() }.testTag("home_rain_headline"))
      val qualifier = when {
        forecastLeads -> R.string.rain_quality_now_and_forecast
        headline in setOf(RainHeadline.CONFLICT, RainHeadline.UNCERTAIN) -> R.string.home_rain_conflict_limit
        headline in setOf(RainHeadline.MODEL_WET, RainHeadline.MODEL_DRY) -> R.string.rain_quality_model_recent
        headline == RainHeadline.UNAVAILABLE -> null
        else -> R.string.rain_quality_radar_recent
      }
      qualifier?.takeIf { showCurrent || forecastLeads || headline in setOf(RainHeadline.CONFLICT, RainHeadline.UNCERTAIN) }
        ?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
      HomeRainPresentation.transition(brief, now)?.let { transition ->
        Text(stringResource(if (transition.arrival) R.string.home_rain_arrival else R.string.home_rain_end,
          TimeFormats.time(transition.startMillis, zone.zoneId, locale), TimeFormats.time(transition.endMillis, zone.zoneId, locale)),
          style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
      }
      // No decorative chart for entirely missing data. Gaps within a useful forecast stay visible.
      if (hasForecast) RainTimeline(brief, now, zone.zoneId, forecast, showTitle = !forecastLeads, compact = !showCurrent)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (onOpenSummary != null) FluidButton(text = stringResource(R.string.weather_summary_ui_title),
        onClick = onOpenSummary, style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
        modifier = Modifier.heightIn(min = 48.dp).testTag("home_summary_open"))
      if (evidence.isNotEmpty() || limits.isNotEmpty()) {
        val expandedLabel = stringResource(if (sourcesExpanded) R.string.home_rain_sources_expanded else R.string.home_rain_sources_collapsed)
        FluidButton(text = stringResource(R.string.home_rain_sources), onClick = { sourcesExpanded = !sourcesExpanded },
          style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,
          modifier = Modifier.heightIn(min = 48.dp).testTag("home_rain_sources_toggle").semantics { stateDescription = expandedLabel })
      }
    }
    if (sourcesExpanded) RainSourceDetails(brief, evidence, limits, now, zone, locale)
  }
}

@Composable
private fun RainTimeline(brief: WeatherBrief?, now: Long, zone: ZoneId,
  forecast: RainForecastPresentation, showTitle: Boolean, compact: Boolean) {
  val parts = HomeRainPresentation.timeline(brief, now)
  val locale = LocalConfiguration.current.locales[0]
  val units = rememberUnitFormatter()
  val colors = MaterialTheme.colorScheme
  val title = stringResource(R.string.rain_quality_forecast_title)
  val descriptions = parts.map { part ->
    val label = stringResource(when (part.period?.state) {
      PrecipitationState.WET -> R.string.home_rain_timeline_wet
      PrecipitationState.DRY -> R.string.home_rain_timeline_dry
      PrecipitationState.UNCERTAIN -> R.string.home_rain_timeline_uncertain
      else -> R.string.home_rain_timeline_unknown
    })
    val amount = part.period?.amountMm?.let { units.precipitation(it) }
    val probability = part.period?.probabilityPercent?.let { stringResource(R.string.rain_quality_probability_part, units.percent(it / 100.0)) }
    val interval = accessibleIntervalLabel(LocalContext.current.resources,
      part.interval.startMillis, part.interval.endMillis, zone, locale)
    val precision = part.period?.evidence.orEmpty().mapNotNull { it.temporalResolutionMillis }.distinct()
      .map { stringResource(R.string.accessibility_resolution, "${it / 1000.0} s") }
    interval + ": " + (listOfNotNull(label, amount, probability) + precision).joinToString(" · ")
  }
  val resolution = forecast.resolutionMillis?.let { stringResource(R.string.rain_quality_resolution, rainResolution(it)) }
    ?: stringResource(R.string.rain_quality_resolution_unknown)
  val amountScale = parts.mapNotNull { it.period?.amountMm }.maxOrNull()?.takeIf { it > 0 }
  val hasRain = forecast.kind == RainForecastKind.WET
  val partial = stringResource(R.string.rain_quality_partial)
  Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("home_rain_timeline")
    .clearAndSetSemantics { contentDescription = listOf(title, resolution,
      if (forecast.hasGaps) partial else "", descriptions.joinToString("; ")).filter { it.isNotBlank() }.joinToString(". ") },
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (zone != ZoneId.systemDefault()) Text(stringResource(R.string.quality_times_in, zone.id),
      style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    if (showTitle) Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
    Canvas(Modifier.fillMaxWidth().height(if (hasRain) 40.dp else 12.dp)) {
      val baseline = size.height - 2.dp.toPx()
      parts.forEach { part ->
        val left = (part.interval.startMillis - now) / 3_600_000f * size.width
        val width = (part.interval.endMillis - part.interval.startMillis) / 3_600_000f * size.width
        val inset = minOf(2.dp.toPx(), width / 4)
        val start = left + inset
        val finish = left + width - inset
        val period = part.period
        val amount = period?.amountMm
        when {
          period == null -> drawLine(colors.onSurfaceVariant, Offset(start, baseline), Offset(finish, baseline),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx())))
          period.state == PrecipitationState.WET && amount != null && amountScale != null -> {
            val barHeight = ((amount / amountScale).toFloat() * (size.height - 5.dp.toPx())).coerceAtLeast(3.dp.toPx())
            drawRoundRect(colors.primary, Offset(start, baseline - barHeight),
              Size((finish - start).coerceAtLeast(0f), barHeight), CornerRadius(2.dp.toPx()))
          }
          else -> drawLine(if (period.state == PrecipitationState.DRY) colors.primary else colors.tertiary,
            Offset(start, baseline), Offset(finish, baseline), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      listOf(now, now + 1_800_000L, now + 3_600_000L).forEach {
        Text(TimeFormats.time(it, zone, locale), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
      }
    }
    val gapLabel = if (forecast.hasGaps) stringResource(R.string.rain_quality_gaps) else null
    val caption = if (compact) gapLabel else listOfNotNull(resolution, gapLabel).joinToString(" · ")
    caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
    if (hasRain && amountScale != null) Text(stringResource(R.string.rain_quality_amount_bars, units.precipitation(amountScale)),
      style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    val metrics = listOfNotNull(
      forecast.totalAmountMm?.takeIf { it > 0 }?.let { stringResource(R.string.rain_quality_total, units.precipitation(it)) },
      forecast.maximumProbabilityPercent?.takeIf { !compact || it > 0 }?.let {
        stringResource(R.string.rain_quality_probability, units.percent(it / 100.0)) })
    if (metrics.isNotEmpty()) Text(metrics.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
  }
}

@Composable
private fun rainResolution(millis: Long): String = when {
  millis % 3_600_000L == 0L -> stringResource(R.string.rain_quality_hours, millis / 3_600_000L)
  millis % 60_000L == 0L -> stringResource(R.string.rain_quality_minutes, millis / 60_000L)
  else -> stringResource(R.string.rain_quality_seconds, millis / 1_000L)
}

/** Source clocks are separate rows; computation time is never called a source update. */
@Composable
private fun RainSourceDetails(brief: WeatherBrief?, evidence: List<WeatherEvidence>, limits: Set<WeatherEvidenceLimit>,
  now: Long, zone: ResolvedWeatherZone, locale: Locale) {
  val units = rememberUnitFormatter()
  val colors = MaterialTheme.colorScheme
  fun at(time: Long) = TimeFormats.dayTime(time, zone.zoneId, locale)
  Column(Modifier.fillMaxWidth().testTag("home_rain_sources_details"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FluidHairline()
    Text(stringResource(R.string.rain_quality_zone, zone.zoneId.id), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
    if (zone.source == WeatherZoneSource.DEVICE_FALLBACK) SourceText(stringResource(R.string.home_rain_zone_fallback, zone.zoneId.id))
    evidence.groupBy { it.sourceId to it.type }.forEach { (key, sources) ->
      val type = stringResource(when (key.second) {
        WeatherEvidenceType.MODEL -> R.string.home_rain_source_model
        WeatherEvidenceType.RADAR_ESTIMATE -> R.string.home_rain_source_radar
        WeatherEvidenceType.MEASUREMENT -> R.string.home_rain_source_measurement
      })
      Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$type · ${key.first}", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        val times = sources.mapNotNull { it.sourceTimestampMillis }.distinct().sorted()
        SourceText(if (times.isEmpty()) stringResource(R.string.rain_quality_source_time_unknown)
          else stringResource(R.string.rain_quality_source_time, times.joinToString(" / ") { at(it) }))
        if (times.isNotEmpty() && sources.any { it.sourceTimestampMillis == null })
          SourceText(stringResource(R.string.rain_quality_source_time_unknown))
        sources.map { it.downloadedAtMillis }.distinct().forEach { downloaded ->
          SourceText(if (downloaded == null) stringResource(R.string.rain_quality_download_unknown)
            else stringResource(R.string.rain_quality_download_time, if (downloaded <= now)
              stringResource(R.string.rain_quality_source_age, at(downloaded), (now - downloaded) / 60_000L) else at(downloaded)))
        }
        val intervals = sources.mapNotNull { it.effectiveInterval }.distinct().sortedBy { it.startMillis }
        if (intervals.isNotEmpty()) SourceText(stringResource(R.string.rain_quality_intervals,
          intervals.joinToString(" / ") { at(it.startMillis) + "–" + TimeFormats.time(it.endMillis, zone.zoneId, locale) }))
        val resolutionLabels = sources.mapNotNull { it.temporalResolutionMillis }.distinct().sorted().map { rainResolution(it) }
        SourceText(if (resolutionLabels.isEmpty()) stringResource(R.string.rain_quality_resolution_unknown)
          else stringResource(R.string.rain_quality_source_resolution, resolutionLabels.joinToString(" / ")))
        val spatial = sources.mapNotNull { it.spatialResolutionMeters }.distinct()
        if (spatial.isNotEmpty()) SourceText(stringResource(R.string.rain_quality_source_space,
          spatial.joinToString(" / ") { units.distance(it / 1_000.0, decimals = 1) }))
      }
    }
    brief?.precipitation?.radarAvailability?.takeIf { it != RadarAvailability.AVAILABLE }?.let { radar ->
      SourceText(stringResource(when (radar) {
        RadarAvailability.OUTSIDE -> R.string.home_rain_radar_outside
        RadarAvailability.UNKNOWN -> R.string.home_rain_radar_unknown
        else -> R.string.home_rain_radar_unavailable
      }))
    }
    if (brief?.timeline?.any { it.role == PrecipitationPeriodRole.FORECAST } == true)
      SourceText(stringResource(R.string.rain_quality_forecast_notice))
    (limits + evidence.flatMap { it.limits }).map { limit -> when (limit) {
      WeatherEvidenceLimit.STALE -> R.string.home_rain_stale
      WeatherEvidenceLimit.UNKNOWN_PROVENANCE -> R.string.routines_provenance_unknown
      WeatherEvidenceLimit.PARTIAL_COVERAGE -> R.string.routines_partial
      WeatherEvidenceLimit.LOCATION_MISMATCH -> R.string.routines_mismatch
      WeatherEvidenceLimit.MISSING_COORDINATES -> R.string.rain_quality_no_coordinates
      WeatherEvidenceLimit.POSSIBLY_INTERPOLATED -> R.string.rain_quality_interpolated
      WeatherEvidenceLimit.COARSE_TIME_RESOLUTION -> R.string.rain_quality_coarse_time
      WeatherEvidenceLimit.COARSE_SPATIAL_RESOLUTION -> R.string.rain_quality_coarse_space
      WeatherEvidenceLimit.CONFLICTING_SIGNALS -> R.string.home_rain_conflict_limit
      WeatherEvidenceLimit.TRANSITION_EXPIRED -> R.string.rain_quality_transition_expired
      WeatherEvidenceLimit.FUTURE_DIVERGENCE -> R.string.rain_quality_future_divergence
      WeatherEvidenceLimit.MODEL_ONLY -> R.string.rain_quality_model_recent
      WeatherEvidenceLimit.RADAR_GROUND_UNCERTAINTY -> R.string.rain_quality_radar_recent
      WeatherEvidenceLimit.RADAR_UNAVAILABLE -> R.string.home_rain_radar_unavailable
      WeatherEvidenceLimit.RADAR_SUPPORT_UNKNOWN -> R.string.home_rain_radar_unknown
    } }.distinct().forEach { SourceText(stringResource(it)) }
  }
}

@Composable
private fun SourceText(text: String) {
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
