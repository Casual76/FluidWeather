package dev.pampa.fluidweather.feature.report

import android.content.Context
import android.location.Geocoder
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FilterDrama
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Hail
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.data.ObservationRepository
import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.Observation
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.BlackSheetNote
import dev.pampa.fluidweather.core.ui.BlackSheetSectionTitle
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.labelRes
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.strings.TimeFormats

/** Tutto quello che la segnalazione tocca; lo costruisce :app dal suo grafo. */
class ReportDependencies(
  val observations: ObservationRepository,
  val locationProvider: LocationProvider,
)

private val White = Color.White
private val Dim = Color.White.copy(alpha = 0.7f)
private val Faint = Color.White.copy(alpha = 0.5f)
private val Blue = Color(0xFF8FC7F0)

/** Dove e quando: risolto una volta all'apertura, mostrato sotto la domanda. */
private data class ReportPlace(val latitude: Double?, val longitude: Double?, val name: String?)

/**
 * "Che tempo fa da te adesso?" in due tocchi (fase 14): una condizione, poi Segnala. L'osservazione
 * e' geolocalizzata e con orario, entra in archivio e da li' nella verifica come verita' di
 * riferimento (chi ha guardato fuori sa se piove) e nella calibrazione (fase 16). Foglio nero
 * dal basso, come deciso il 2026-09-02; le tessere arrivano una dopo l'altra, non tutte insieme.
 */
@Composable
fun ReportSheet(open: Boolean, deps: ReportDependencies, onDismiss: () -> Unit) {
  if (!open) return
  BlackSheet(title = stringResource(R.string.report_title), onDismiss = onDismiss) {
    ReportContent(deps)
  }
}

@Composable
private fun ReportContent(deps: ReportDependencies) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val recent by deps.observations.recent(5).collectAsState(initial = emptyList())
  val place by produceState<ReportPlace?>(initialValue = null) {
    val here = if (deps.locationProvider.hasPermission()) deps.locationProvider.snapshot() else null
    val name = here?.let { reverseGeocode(context, it.latitude, it.longitude) }
    value = ReportPlace(here?.latitude, here?.longitude, name)
  }
  var selected by remember { mutableStateOf<ObservedCondition?>(null) }
  var sent by remember { mutableStateOf<Observation?>(null) }
  val haptics = rememberFluidHaptics()

  // L'apertura curata: le tessere si materializzano in sequenza, non tutte in un colpo.
  var revealed by remember { mutableStateOf(0) }
  LaunchedEffect(Unit) {
    while (revealed < ObservedCondition.entries.size) {
      delay(40)
      revealed++
    }
  }

  val done = sent
  if (done != null) {
    BlackSheetSectionTitle(stringResource(R.string.report_done))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(conditionIcon(done.condition), contentDescription = null, tint = Blue, modifier = Modifier.size(40.dp))
      Spacer(Modifier.width(14.dp))
      Column {
        Text(stringResource(done.condition.labelRes()), style = MaterialTheme.typography.titleLarge, color = White)
        Text(
          "${fmtTime(done.timestampMillis)}${done.placeName?.let { " · $it" } ?: ""}",
          style = MaterialTheme.typography.bodyMedium,
          color = Dim,
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    BlackSheetNote(
      stringResource(R.string.report_thanks),
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      FluidButton(
        text = stringResource(R.string.report_undo),
        style = FluidButtonStyle.Tinted,
        onClick = {
          scope.launch {
            deps.observations.delete(done.id)
            sent = null
            selected = null
          }
        },
      )
      FluidButton(text = stringResource(R.string.report_another), style = FluidButtonStyle.Tinted, onClick = { sent = null; selected = null })
    }
  } else {
    Text(
      stringResource(R.string.report_question),
      style = MaterialTheme.typography.headlineSmall,
      color = White,
    )
    Text(
      when {
        place == null -> stringResource(R.string.report_locating)
        place?.name != null -> "${place!!.name} · ${fmtTime(System.currentTimeMillis())}"
        place?.latitude != null -> stringResource(R.string.report_phone_position, fmtTime(System.currentTimeMillis()))
        else -> stringResource(R.string.report_no_position)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = Dim,
      modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
    )

    TutorialSlot(screen = TutorialScreen.REPORT)
    val conditions = ObservedCondition.entries
    conditions.chunked(3).forEachIndexed { rowIndex, row ->
      Row(
        Modifier
          .fillMaxWidth()
          .then(if (rowIndex == 0) Modifier.fluidTutorialAnchor("report_conditions") else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        row.forEachIndexed { columnIndex, condition ->
          val index = rowIndex * 3 + columnIndex
          ConditionTile(
            condition = condition,
            selected = condition == selected,
            visible = index < revealed,
            onClick = { selected = condition },
            modifier = Modifier.weight(1f),
          )
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
      }
      Spacer(Modifier.height(10.dp))
    }

    Spacer(Modifier.height(6.dp))
    FluidButton(
      text = selected?.let { stringResource(R.string.report_submit, stringResource(it.labelRes())) } ?: stringResource(R.string.report_pick),
      style = FluidButtonStyle.Filled,
      enabled = selected != null,
      onClick = {
        val condition = selected ?: return@FluidButton
        val here = place
        scope.launch {
          sent = deps.observations.record(
            condition = condition,
            timestampMillis = System.currentTimeMillis(),
            latitude = here?.latitude,
            longitude = here?.longitude,
            placeName = here?.name,
          )
          haptics.play(if (sent != null) FluidHapticEvent.Confirm else FluidHapticEvent.Error)
        }
      },
      modifier = Modifier.fillMaxWidth(),
    )
    BlackSheetNote(stringResource(R.string.report_present_only))
  }

  if (recent.isNotEmpty()) {
    BlackSheetSectionTitle(stringResource(R.string.report_recent))
    recent.forEach { observation ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 6.dp),
      ) {
        Icon(conditionIcon(observation.condition), contentDescription = null, tint = Dim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(observation.condition.labelRes()), style = MaterialTheme.typography.bodyMedium, color = White, modifier = Modifier.weight(1f))
        Text(
          fmtDayTime(observation.timestampMillis) + (observation.placeName?.let { " · $it" } ?: ""),
          style = MaterialTheme.typography.bodySmall,
          color = Faint,
        )
      }
    }
  }
}

@Composable
private fun ConditionTile(
  condition: ObservedCondition,
  selected: Boolean,
  visible: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val presence = remember { Animatable(0f) }
  LaunchedEffect(visible) {
    if (visible) presence.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = FluidMotion.ResponseSnappy))
  }
  val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else White.copy(alpha = 0.07f)
  val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .graphicsLayer {
        val p = presence.value
        alpha = p
        scaleX = 0.85f + 0.15f * p
        scaleY = 0.85f + 0.15f * p
      }
      .background(background, ContinuousCornerShape(FluidRadius.Group))
      .background(border.copy(alpha = if (selected) 0.35f else 0f), ContinuousCornerShape(FluidRadius.Group))
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
      .padding(vertical = 14.dp, horizontal = 6.dp),
  ) {
    Icon(
      conditionIcon(condition),
      contentDescription = null,
      tint = if (selected) White else Dim,
      modifier = Modifier.size(28.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
      stringResource(condition.labelRes()),
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
      color = if (selected) White else Dim,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

private fun conditionIcon(condition: ObservedCondition): ImageVector = when (condition) {
  ObservedCondition.CLEAR -> Icons.Rounded.WbSunny
  ObservedCondition.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy
  ObservedCondition.CLOUDY -> Icons.Rounded.Cloud
  ObservedCondition.FOG -> Icons.Rounded.FilterDrama
  ObservedCondition.DRIZZLE -> Icons.Rounded.Grain
  ObservedCondition.RAIN -> Icons.Rounded.WaterDrop
  ObservedCondition.HEAVY_RAIN -> Icons.Rounded.Umbrella
  ObservedCondition.THUNDERSTORM -> Icons.Rounded.Thunderstorm
  ObservedCondition.SNOW -> Icons.Rounded.AcUnit
  ObservedCondition.HAIL -> Icons.Rounded.Hail
}

private fun fmtTime(millis: Long): String = TimeFormats.time(millis)

private fun fmtDayTime(millis: Long): String = TimeFormats.dayTime(millis)

private suspend fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String? =
  withContext(Dispatchers.IO) {
    runCatching {
      @Suppress("DEPRECATION")
      Geocoder(context, Locale.getDefault())
        .getFromLocation(latitude, longitude, 1)
        ?.firstOrNull()
        ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()
  }
