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
  /**
   * La raffica nominale e' finita ma il tempo fermo non basta: la taratura sta aspettando che il
   * telefono stia tranquillo. Senza questo flag la barra diceva "1:30 di 5:00" e sembrava rotta.
   */
  val calibrationWaiting: Boolean = false,
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

  /** Dove siamo: chi mostra la barra sceglie le parole (core-strings), qui solo lo stadio. */
  val stage: ReadinessStage
    get() = when {
      calibrationRunning -> ReadinessStage.CALIBRATING
      !ready -> ReadinessStage.HISTORY
      else -> ReadinessStage.READY
    }
}

enum class ReadinessStage { CALIBRATING, HISTORY, READY }

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
