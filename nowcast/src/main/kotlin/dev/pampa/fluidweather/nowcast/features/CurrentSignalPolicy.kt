package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.nowcast.cleaning.FilteredPoint

/** Eligibility for a new operational verdict, independent of network/context availability. */
object CurrentSignalPolicy {
  // Allows the 30-minute saving mode plus scheduling jitter, without renewing hours-old data.
  const val MAX_AGE_MILLIS: Long = 45 * 60_000L
  private const val HOUR_MILLIS: Long = 3_600_000L
  private const val RECENT_WINDOW_MILLIS: Long = 3 * HOUR_MILLIS

  fun isCurrent(filtered: List<FilteredPoint>, nowMillis: Long): Boolean {
    val measured = filtered.filterNot { it.gated }
    val latest = measured.lastOrNull() ?: return false
    if (nowMillis - latest.timestampMillis !in 0..MAX_AGE_MILLIS) return false
    if (filtered.any { it.timestampMillis > nowMillis || !it.levelHpa.isFinite() }) return false
    // A lone reading after a long outage must not turn a disconnected old curve into 'now'.
    // Each of the last three hour buckets needs a cleaned sensor point. Bursts cannot inflate it.
    val covered = measured.asSequence().map { nowMillis - it.timestampMillis }
      .filter { it in 0 until RECENT_WINDOW_MILLIS }.map { it / HOUR_MILLIS }.toSet()
    return covered.size == 3
  }
}
