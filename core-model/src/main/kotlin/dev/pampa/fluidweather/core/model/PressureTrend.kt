package dev.pampa.fluidweather.core.model

/**
 * La tendenza grezza che decide il cambio di marcia (normale -> sorveglianza).
 *
 * Regressione lineare sui campioni cosi' come sono: la versione pulita e de-tidalizzata arriva
 * con la pipeline (fasi 2-3); per accendere la sorveglianza serve solo un numero robusto e
 * spiegabile, non un verdetto. Le soglie di riferimento della letteratura: 1,6 hPa/3h e' un
 * cambio di tempo (Zambretti), ~3-4 hPa/3h un'allerta tempesta.
 */
object PressureTrend {

  /** Sotto due punti (o due istanti distinti) una pendenza non esiste: null, non zero. */
  fun hPaPerHour(samples: List<PressureSample>): Double? {
    if (samples.size < 2) return null
    val first = samples.minOf { it.timestampMillis }
    val xs = samples.map { (it.timestampMillis - first) / 3_600_000.0 }
    val ys = samples.map { it.pressureHpa }
    val meanX = xs.average()
    val meanY = ys.average()
    val denominator = xs.sumOf { (it - meanX) * (it - meanX) }
    if (denominator == 0.0) return null
    val numerator = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
    return numerator / denominator
  }

  /**
   * Piu' sensibile del "cambio di tempo" di Zambretti (0,53 hPa/h) perche' la sorveglianza non e'
   * un'allerta: e' il momento in cui conviene guardare piu' spesso. Da tarare col banco di prova.
   */
  const val SURVEILLANCE_THRESHOLD_HPA_PER_HOUR: Double = 1.0

  fun callsForSurveillance(trendHPaPerHour: Double?): Boolean =
    trendHPaPerHour != null && kotlin.math.abs(trendHPaPerHour) >= SURVEILLANCE_THRESHOLD_HPA_PER_HOUR
}
