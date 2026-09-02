package dev.pampa.fluidweather.core.model

/**
 * Quanto manca perche' il barometro "sia pronto", visto dall'utente come una sola barra: prima
 * la raffica iniziale (dieci minuti), poi la storia che il modello vuole vedere (tredici ore di
 * segnale pulito). Chiesta esplicitamente sul telefono il 2026-09-02, dopo diciassette ore
 * senza verdetto — che erano un baco (finestra di 12 ore contro 13 richieste), non un'attesa.
 */
data class BarometerReadiness(
  val calibrationRunning: Boolean,
  val calibrationCompletedSeconds: Int,
  val calibrationTotalSeconds: Int,
  val calibrated: Boolean,
  val historyHours: Double,
  val requiredHours: Double,
) {
  val calibrationFraction: Float
    get() = when {
      calibrated -> 1f
      calibrationTotalSeconds <= 0 -> 0f
      else -> (calibrationCompletedSeconds.toFloat() / calibrationTotalSeconds).coerceIn(0f, 1f)
    }

  val historyFraction: Float
    get() = if (requiredHours <= 0.0) 1f else (historyHours / requiredHours).toFloat().coerceIn(0f, 1f)

  /** La storia e' pronta: il verdetto puo' esistere. */
  val ready: Boolean get() = historyHours >= requiredHours

  /** Una barra sola: un quinto la raffica, quattro quinti la storia (e' li' che si aspetta). */
  val overallFraction: Float get() = (0.2f * calibrationFraction + 0.8f * historyFraction).coerceIn(0f, 1f)

  val stageLabel: String
    get() = when {
      calibrationRunning -> {
        val done = calibrationCompletedSeconds
        "Raffica iniziale: ${done / 60}:${String.format(java.util.Locale.ROOT, "%02d", done % 60)} di ${calibrationTotalSeconds / 60}:00"
      }
      !ready -> "Storia barometrica: ${formatHours(historyHours)} di ${formatHours(requiredHours)} ore"
      else -> "Barometro pronto"
    }

  private fun formatHours(hours: Double): String {
    val whole = hours.toInt()
    val minutes = ((hours - whole) * 60).toInt()
    return if (whole == 0 && minutes > 0) "${minutes} min" else if (minutes == 0) "$whole" else "$whole h $minutes min"
  }
}

object NowcastReadiness {

  /** Le ore di storia pulita che il modello pretende: coincide con FeatureExtractor.MIN_HISTORY_HOURS. */
  const val REQUIRED_HOURS: Double = 13.0

  fun of(
    calibration: CalibrationRecord?,
    calibrationProgress: Pair<Int, Int>?,
    historyHours: Double,
    requiredHours: Double = REQUIRED_HOURS,
  ): BarometerReadiness = BarometerReadiness(
    calibrationRunning = calibrationProgress != null,
    calibrationCompletedSeconds = calibrationProgress?.first ?: 0,
    calibrationTotalSeconds = calibrationProgress?.second ?: CalibrationBurst.DURATION_SECONDS,
    calibrated = calibration != null,
    historyHours = historyHours.coerceAtLeast(0.0),
    requiredHours = requiredHours,
  )
}
