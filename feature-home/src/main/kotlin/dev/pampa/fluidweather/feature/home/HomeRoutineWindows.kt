package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.*
import dev.pampa.fluidweather.core.model.RoutineWindowAssessment
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter

/** A separate non-draggable grid item after the principal rain answer. */
@Composable
fun HomeRoutineWindows(assessments: List<RoutineWindowAssessment>, readFailed: Boolean = false, onOpenRoutines: () -> Unit) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val resources = LocalContext.current.resources
  val locale = LocalConfiguration.current.locales[0]
  val units = rememberUnitFormatter()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (readFailed) Text(stringResource(R.string.routines_read_error), color = MaterialTheme.colorScheme.error)
    if (!readFailed && assessments.isNotEmpty()) {
      Text(stringResource(R.string.routines_title), style = MaterialTheme.typography.titleMedium)
      (if (expanded) assessments else assessments.take(2)).forEach { assessment ->
        key(assessment.occurrence.routine.id) {
          var details by rememberSaveable { mutableStateOf(false) }
          FluidListGroup(glass = true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(assessment.occurrence.routine.name, style = MaterialTheme.typography.titleSmall)
              Text(routineOccurrenceLabel(assessment.occurrence, locale), style = MaterialTheme.typography.bodyMedium)
              Text(routineForecastLabel(resources, assessment, locale, units, details), style = MaterialTheme.typography.bodyMedium)
              val evidence = routineEvidenceLabel(resources, assessment, locale, details)
              if (evidence.isNotBlank()) {
                Text(evidence, style = MaterialTheme.typography.bodySmall)
                FluidButton(text = stringResource(if (details) R.string.routines_less else R.string.routines_details),
                  style = FluidButtonStyle.Plain, onClick = { details = !details })
              }
            }
          }
        }
      }
      if (assessments.size > 2) FluidButton(
        text = stringResource(if (expanded) R.string.routines_less else R.string.routines_more, assessments.size),
        style = FluidButtonStyle.Plain,
        onClick = { expanded = !expanded },
      )
    }
    val editLabel = stringResource(if (!readFailed && assessments.isEmpty()) R.string.routines_add else R.string.routines_edit)
    if (LocalFluidCanvasBackdrop.current != null) {
      FluidGlassButton(text = editLabel, onClick = onOpenRoutines, grip = FluidPressGrip.YieldsToScroll)
    } else {
      FluidButton(text = editLabel, style = FluidButtonStyle.Plain, onClick = onOpenRoutines)
    }
  }
}
