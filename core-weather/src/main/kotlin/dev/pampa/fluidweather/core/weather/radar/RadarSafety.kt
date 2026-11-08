package dev.pampa.fluidweather.core.weather.radar

/** Absolute UTC epoch milliseconds; raw Eta minutes are relative to frame generation time. */
data class AbsoluteRadarEta(
  val arrivalAtMillis: Long?,
  val endAtMillis: Long?,
  val horizonStartMillis: Long,
  val horizonEndMillis: Long,
  val transitionAlreadyPassed: Boolean,
  val temporalResolutionMillis: Long = 600_000L,
)

object RadarSafety {
  const val MAX_FRAME_AGE_MILLIS = 20 * 60_000L
  const val FUTURE_TOLERANCE_MILLIS = 60_000L

  fun absoluteEta(frameTimeMillis: Long, eta: Eta, evaluatedAtMillis: Long): AbsoluteRadarEta? {
    if (evaluatedAtMillis - frameTimeMillis !in -FUTURE_TOLERANCE_MILLIS..MAX_FRAME_AGE_MILLIS) return null
    if (eta.horizonMin <= 0 || eta.horizonMin > EtaEstimator.MAX_HORIZON_MIN) return null
    val horizonEnd = frameTimeMillis + eta.horizonMin * 60_000L
    fun instant(minutes: Int?): Long? = minutes?.takeIf { it in 1..eta.horizonMin }
      ?.let { frameTimeMillis + it * 60_000L }
    val arrival = instant(eta.arrivesInMin)
    val end = instant(eta.endsInMin)
    val transitionPassed = (arrival != null && arrival <= evaluatedAtMillis) || (end != null && end <= evaluatedAtMillis)
    // A passed transition remains relevant until the frame itself expires. Losing it when
    // the trajectory horizon ends would restore the earlier frame's wet/dry assertion.
    if (horizonEnd <= evaluatedAtMillis && !transitionPassed) return null
    return AbsoluteRadarEta(
      arrivalAtMillis = arrival?.takeIf { it > evaluatedAtMillis && it < horizonEnd },
      endAtMillis = end?.takeIf { it > evaluatedAtMillis },
      horizonStartMillis = frameTimeMillis,
      horizonEndMillis = horizonEnd,
      transitionAlreadyPassed = transitionPassed,
    )
  }
}
