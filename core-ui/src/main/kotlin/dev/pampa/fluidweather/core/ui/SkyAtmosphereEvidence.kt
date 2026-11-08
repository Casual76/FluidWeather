package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.*
import kotlin.math.*

/** One cache-only adapter for both the live home and its static launcher frame. */
object SkyAtmosphereEvidence {
  fun derive(
    context: ForecastBundle?, assessment: LocalPrecipitationAssessment?,
    latitude: Double?, longitude: Double?, nowMillis: Long,
  ): SkyAtmosphere? {
    val bundle = context?.takeIf {
      samePoint(latitude, longitude, it.latitude, it.longitude) &&
        it.fetchedAtMillis <= nowMillis && nowMillis.toDouble() - it.fetchedAtMillis < 90 * 60_000L
    }
    fun sample(point: HourlyPoint) = SkyAtmosphereSample(
      point.timestampMillis, bundle?.fetchedAtMillis, point.cloudCoverPercent,
      point.relativeHumidityPercent, point.visibilityMeters, point.windSpeedKmh,
      rate(point.precipitationMm, bundle?.hourlyPrecipitation),
    )
    val hours = bundle?.hourly.orEmpty().groupBy { it.timestampMillis }.values.mapNotNull { it.singleOrNull() }
    val current = hours.minByOrNull { abs(it.timestampMillis.toDouble() - nowMillis) }
      ?.takeIf { abs(it.timestampMillis.toDouble() - nowMillis) < 90 * 60_000L &&
        it.cloudCoverPercent?.let { c -> c.isFinite() && c in 0.0..100.0 } == true }?.let(::sample)
    val approaching = hours.filter { it.timestampMillis > nowMillis + 30 * 60_000L &&
      it.timestampMillis <= nowMillis + 120 * 60_000L }
      .maxByOrNull { point ->
        val cloud = point.cloudCoverPercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
        val rain = rate(point.precipitationMm, bundle?.hourlyPrecipitation) ?: 0.0
        (cloud / 100.0 + rain / (rain + 3.0)) *
          (1.0 - (point.timestampMillis.toDouble() - nowMillis) / (150 * 60_000L))
      }?.let(::sample)
    val assessmentCurrent = assessment?.takeIf {
      it.evaluatedAtMillis <= nowMillis && nowMillis.toDouble() - it.evaluatedAtMillis <= 120_000L &&
        it.coordinates?.let { point -> samePoint(latitude, longitude, point.latitude, point.longitude) } != false
    }
    val conflict = assessmentCurrent?.limits.orEmpty().any { it in setOf(
      WeatherEvidenceLimit.CONFLICTING_SIGNALS, WeatherEvidenceLimit.LOCATION_MISMATCH,
      WeatherEvidenceLimit.TRANSITION_EXPIRED,
    ) }
    val evidence = assessmentCurrent?.evidence.orEmpty().filter {
      it.precipitationState == PrecipitationState.WET &&
        WeatherEvidenceLimit.STALE !in it.limits && WeatherEvidenceLimit.LOCATION_MISMATCH !in it.limits
    }.maxByOrNull { it.sourceTimestampMillis ?: Long.MIN_VALUE }
    val currentRain = bundle?.current
    val quarter = bundle?.rainNow(nowMillis, 20 * 60_000L)
    val intensity = when {
      evidence == null || evidence.type != WeatherEvidenceType.MODEL || evidence.sourceId != bundle?.providerId -> null
      currentRain != null && evidence.effectiveInterval != null &&
        evidence.effectiveInterval == currentRain.precipitationInterval &&
        currentRain.accumulationIntervalMillis?.let { it <= 900_000L } == true &&
        evidence.downloadedAtMillis == currentRain.downloadedAtMillis ->
        rate(currentRain.precipitationMm, PrecipitationAccumulation(
          currentRain.accumulationIntervalMillis, AccumulationAnchor.PRECEDING))
      bundle?.minutelyPrecipitation?.durationMillis == 900_000L &&
        bundle.minutelyPrecipitation?.anchor == AccumulationAnchor.PRECEDING &&
        quarter != null && quarter.interval == evidence.effectiveInterval &&
        quarter.interval.endMillis <= bundle.fetchedAtMillis &&
        evidence.downloadedAtMillis == bundle.fetchedAtMillis -> quarter.amountMm * 4.0
      else -> null
    }
    val present = assessmentCurrent?.let {
      SkyPresentPrecipitation(it.state, evidence?.sourceTimestampMillis,
        evidence?.downloadedAtMillis, intensity, conflict)
    }
    val derived = SkyAtmospherePolicy.derive(SkyAtmosphereInput(nowMillis, current, approaching, present))
    // No invented neutral cloud deck on an old/legacy snapshot. Preserve the existing condition
    // renderer unless fresh optical fields or a usable present precipitation signal exist.
    return derived.takeIf { current != null || it.precipitationStrength > 0f }
  }

  private fun rate(amount: Double?, metadata: PrecipitationAccumulation?): Double? {
    val duration = metadata?.durationMillis ?: return null
    if (metadata.anchor == AccumulationAnchor.UNKNOWN || duration !in 1..10_800_000L) return null
    return amount?.takeIf { it.isFinite() && it >= 0 }?.let { it * 3_600_000.0 / duration }
  }

  private fun samePoint(a: Double?, b: Double?, c: Double, d: Double): Boolean {
    if (a == null || b == null || !a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite() ||
      a !in -90.0..90.0 || c !in -90.0..90.0 || b !in -180.0..180.0 || d !in -180.0..180.0) return false
    val lat = Math.toRadians(c-a)
    val lon = Math.toRadians(d-b)
    val h = sin(lat/2).pow(2) + cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(lon/2).pow(2)
    return 6371.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0))) <= 2.0
  }
}
