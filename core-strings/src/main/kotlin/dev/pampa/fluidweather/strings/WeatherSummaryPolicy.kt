package dev.pampa.fluidweather.strings

import dev.pampa.fluidweather.core.model.*
import java.security.MessageDigest

/** Consumption gates, not a second rain evaluator. Durations mirror F2/F4 source cutoffs. */
internal object WeatherSummaryPolicy {
  fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

  fun cutoff(e: WeatherEvidence, recent: Boolean = false, current: Boolean = false): Long {
    val download = e.downloadedAtMillis ?: return Long.MIN_VALUE
    val source = e.sourceTimestampMillis
    if (WeatherEvidenceLimit.STALE in e.limits || WeatherEvidenceLimit.LOCATION_MISMATCH in e.limits) return Long.MIN_VALUE
    return when (e.type) {
      WeatherEvidenceType.MODEL -> minOf(download + 3_600_000L,
        if (recent || current) (source ?: return Long.MIN_VALUE) + if (current) 1_800_000L else 1_200_000L else Long.MAX_VALUE)
      WeatherEvidenceType.RADAR_ESTIMATE -> minOf(download + 1_200_000L, (source ?: return Long.MIN_VALUE) + 1_200_000L)
      // No measurement freshness contract is available to this formatter.
      WeatherEvidenceType.MEASUREMENT -> Long.MIN_VALUE
    }
  }

  fun usable(e: WeatherEvidence, now: Long, recent: Boolean = false, current: Boolean = false): Boolean =
    e.downloadedAtMillis?.let { it <= now } == true &&
      // Forecast sample valid times may be in the future. Only current/recent observations
      // and radar frames have a no-future-source constraint; download time is always checked.
      (e.sourceTimestampMillis?.let {
        if (e.type == WeatherEvidenceType.MODEL && !recent && !current) true
        else it <= now + if (e.type == WeatherEvidenceType.RADAR_ESTIMATE) 60_000L else 0L
      } != false) &&
      now < cutoff(e, recent, current)

  fun rainUsable(brief: WeatherBrief?, now: Long): Boolean {
    val rain = brief?.precipitation ?: return false
    if (rain.evaluatedAtMillis > now || now >= rain.evaluatedAtMillis + 60_000L ||
      rain.horizon?.endMillis?.let { now >= it } == true) return false
    // A freshly reevaluated uncertainty is a valid shared result, including a transition
    // that has already passed. Preserve it without authorizing wet/dry certainty or an ETA.
    if (rain.state == PrecipitationState.UNCERTAIN) return true
    val signals = rain.evidence.filter { it.precipitationState != null }
    // Aggregate limits also describe excluded sources. An expired radar or a rejected
    // foreign model must not veto fresh contributing evidence selected by the evaluator.
    // Each contributing signal still passes its own provenance and freshness gates.
    return signals.isNotEmpty() && signals.all { usable(it, now, recent = true) } &&
      WeatherEvidenceLimit.TRANSITION_EXPIRED !in rain.limits &&
      listOfNotNull(rain.arrivalAtMillis, rain.endAtMillis).none { it <= now }
  }

  fun revision(brief: WeatherBrief?): String = hash(buildString {
    append(brief?.revision)
    // Normalize computation-only fields without constructing an invalid ETA-without-horizon DTO.
    append(brief?.precipitation?.copy(evaluatedAtMillis = 0, horizon = null, arrivalAtMillis = null, endAtMillis = null))
    append(listOf(brief?.precipitation?.arrivalAtMillis, brief?.precipitation?.endAtMillis))
    append(brief?.precipitation?.horizon?.let { it.endMillis - it.startMillis })
    append(brief?.current); append(brief?.timeline); append(brief?.rainLastHour)
    append(brief?.routines?.map { it.copy(evaluatedAtMillis = 0) })
  })
}
