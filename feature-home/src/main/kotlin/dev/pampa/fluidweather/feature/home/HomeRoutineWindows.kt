package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.pampa.fluidweather.core.model.RoutineWindowAssessment
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.core.ui.GlassTile
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** A separate non-draggable grid item after the principal rain answer. */
@Composable
fun HomeRoutineWindows(assessments: List<RoutineWindowAssessment>, readFailed: Boolean = false, onOpenRoutines: () -> Unit) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
  }
  val foreground = if (LocalFluidCanvasBackdrop.current != null) Color.White else MaterialTheme.colorScheme.onSurface
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (readFailed) Text(stringResource(R.string.routines_read_error), color = MaterialTheme.colorScheme.error)
    if (!readFailed && assessments.isNotEmpty()) {
      Text(stringResource(R.string.routines_title), style = MaterialTheme.typography.titleMedium, color = foreground)
      (if (expanded) assessments else assessments.take(2)).forEach { assessment ->
        key(assessment.occurrence.routine.id) {
          GlassTile {
            HomeMomentContent(assessment, now, WeatherGlanceSummary.routineUsable(assessment, now))
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
