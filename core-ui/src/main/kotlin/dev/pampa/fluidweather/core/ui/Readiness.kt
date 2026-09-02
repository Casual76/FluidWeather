package dev.pampa.fluidweather.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.model.BarometerReadiness
import dev.pampa.fluidweather.core.model.ReadinessStage
import dev.pampa.fluidweather.strings.R
import java.util.Locale

/** La riga sopra la barra unica del barometro: home, pagina del nowcast e impostazioni la dicono uguale. */
@Composable
fun BarometerReadiness.stageText(): String = when (stage) {
  ReadinessStage.CALIBRATING -> stringResource(
    R.string.readiness_burst,
    "${calibrationCompletedSeconds / 60}:${String.format(Locale.ROOT, "%02d", calibrationCompletedSeconds % 60)}",
    "${calibrationTotalSeconds / 60}:00",
  )
  ReadinessStage.HISTORY -> stringResource(R.string.readiness_history, hoursText(historyHours), hoursText(requiredHours))
  ReadinessStage.READY -> stringResource(R.string.readiness_ready)
}

@Composable
private fun hoursText(hours: Double): String {
  val whole = hours.toInt()
  val minutes = ((hours - whole) * 60).toInt()
  return when {
    whole == 0 && minutes > 0 -> stringResource(R.string.duration_minutes, minutes)
    minutes == 0 -> "$whole"
    else -> stringResource(R.string.duration_hours_minutes, whole, minutes)
  }
}
