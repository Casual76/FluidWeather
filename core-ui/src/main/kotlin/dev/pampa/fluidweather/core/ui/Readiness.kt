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
  ReadinessStage.CALIBRATING -> if (calibrationWaiting) {
    // I minuti UTILI, non quelli passati: e' quello che sta aspettando.
    stringResource(R.string.readiness_burst_waiting, calibrationCompletedSeconds / 60, calibrationTotalSeconds / 60)
  } else {
    stringResource(
      R.string.readiness_burst,
      "${calibrationCompletedSeconds / 60}:${String.format(Locale.ROOT, "%02d", calibrationCompletedSeconds % 60)}",
      "${calibrationTotalSeconds / 60}:00",
    )
  }
  // Zero ore esatte e' aritmeticamente giusto — subito dopo la raffica i seicento campioni sono
  // **un solo** punto aggregato, quindi non c'e' ancora nessuna ampiezza da misurare — ma "0 di 13
  // ore" si legge come un contatore inchiodato. E' letteralmente la frase che ha fatto pensare a
  // un'app rotta, quindi quando e' zero si dice cos'e': un inizio.
  ReadinessStage.HISTORY -> if (historyHours <= 0.0) {
    stringResource(R.string.readiness_history_start, hoursText(requiredHours))
  } else {
    stringResource(R.string.readiness_history, hoursText(historyHours), hoursText(requiredHours))
  }
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
