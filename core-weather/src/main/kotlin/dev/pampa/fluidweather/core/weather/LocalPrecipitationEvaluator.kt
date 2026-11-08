package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.radar.*
import java.security.MessageDigest

/** Pure policy. No fused/hourly proxy, sensor, IO or clock reads. */
class LocalPrecipitationEvaluator {
  fun evaluate(
    place: Place,
    coordinates: GeoPoint?,
    snapshot: WeatherSnapshot?,
    radarSample: RadarSample?,
    evaluatedAtMillis: Long,
  ): WeatherBrief {
    val now = evaluatedAtMillis
    val limits = mutableSetOf<WeatherEvidenceLimit>()
    val evidence = mutableListOf<WeatherEvidence>()
    val identity = revision(place, coordinates, snapshot, radarSample)
    if (coordinates == null) return WeatherBrief(LocalPrecipitationAssessment(
      PrecipitationState.UNAVAILABLE, now, place, limits = setOf(WeatherEvidenceLimit.MISSING_COORDINATES)), revision = identity)
    val key = if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)
    val local = snapshot?.takeIf {
      it.placeKey == key && validPoint(it.latitude, it.longitude) &&
        it.distanceKmTo(coordinates.latitude, coordinates.longitude) <= MAX_DISTANCE_KM
    }
    if (snapshot != null && local == null) limits += WeatherEvidenceLimit.LOCATION_MISMATCH
    val bundle = local?.context?.takeIf {
      validPoint(it.latitude, it.longitude) &&
        haversineKm(it.latitude, it.longitude, coordinates.latitude, coordinates.longitude) <= MAX_DISTANCE_KM
    }
    if (local?.context != null && bundle == null) limits += WeatherEvidenceLimit.LOCATION_MISMATCH
    var currentSummary: CurrentWeatherSummary? = null
    var lastHour: RainAccumulation? = null
    var timeline = emptyList<PrecipitationPeriod>()
    if (bundle != null) {
      val downloadFresh = fresh(bundle.fetchedAtMillis, now, DOWNLOAD_MAX_AGE)
      if (!downloadFresh) limits += WeatherEvidenceLimit.STALE
      val current = bundle.current
      var wetCurrentSignal: WeatherEvidence? = null
      if (current != null) {
        val origin = current.downloadedAtMillis
        val coherent = origin != null && current.timestampMillis <= origin
        if (origin == null) limits += WeatherEvidenceLimit.UNKNOWN_PROVENANCE
        val usable = coherent && downloadFresh && fresh(origin, now, DOWNLOAD_MAX_AGE)
        if (origin != null && !fresh(origin, now, DOWNLOAD_MAX_AGE)) limits += WeatherEvidenceLimit.STALE
        val e = modelEvidence(bundle, null, origin, current.timestampMillis, current.possiblyInterpolated)
        if (usable && fresh(current.timestampMillis, now, CURRENT_MAX_AGE) &&
          (current.temperatureC != null || current.kind?.takeIf { it != WeatherKind.UNKNOWN } != null)) {
          currentSummary = CurrentWeatherSummary(current.kind?.takeIf { it != WeatherKind.UNKNOWN }, current.temperatureC, listOf(e))
        }
        val interval = current.precipitationInterval
        val currentAmount = current.precipitationMm
        if (usable && fresh(current.timestampMillis, now, RECENT_MAX_AGE) && current.kind in WET_KINDS) {
          wetCurrentSignal = e.copy(precipitationState = PrecipitationState.WET)
        }
        if (usable && fresh(current.timestampMillis, now, RECENT_MAX_AGE) && interval != null &&
          interval.endMillis - interval.startMillis <= QUARTER && currentAmount != null) {
          evidence += modelEvidence(bundle, interval, origin, current.timestampMillis, current.possiblyInterpolated)
            .copy(precipitationState = state(currentAmount))
        }
      }
      val metadata = bundle.minutelyPrecipitation
      val quarterKnown = metadata?.durationMillis == QUARTER && metadata.anchor == AccumulationAnchor.PRECEDING
      if (!quarterKnown && bundle.minutely.isNotEmpty()) limits += WeatherEvidenceLimit.UNKNOWN_PROVENANCE
      if (downloadFresh && quarterKnown) {
        // A forecast does not become current evidence just because its endpoint has passed.
        val recent = bundle.rainNow(now, RECENT_MAX_AGE)?.takeIf { it.interval.endMillis <= bundle.fetchedAtMillis }
        if (evidence.isEmpty() && recent != null) evidence += modelEvidence(bundle, recent.interval,
          bundle.fetchedAtMillis, recent.interval.endMillis, bundle.minutelyPossiblyInterpolated)
          .copy(precipitationState = state(recent.amountMm))
        lastHour = bundle.rainLastHour(now, RECENT_MAX_AGE)?.takeIf { it.interval.endMillis <= bundle.fetchedAtMillis }
        timeline = timeline(bundle, now)
      }
      // An instantaneous wet condition can disagree with a preceding dry accumulation.
      // Preserve that signal; a non-wet/unknown weather code never proves dry.
      wetCurrentSignal?.let { evidence += it }
    }
    val radar = radarSample?.evaluateAt(now)
    val availability = when {
      radar?.geographicCoverage == GeographicCoverage.OUTSIDE -> RadarAvailability.OUTSIDE
      radar?.usable == true -> RadarAvailability.AVAILABLE
      radar?.geographicCoverage == GeographicCoverage.SUPPORTED -> RadarAvailability.UNAVAILABLE
      else -> RadarAvailability.UNKNOWN
    }
    if (availability != RadarAvailability.AVAILABLE) {
      limits += WeatherEvidenceLimit.PARTIAL_COVERAGE
      limits += WeatherEvidenceLimit.RADAR_UNAVAILABLE
    }
    if (radar?.geographicCoverage == GeographicCoverage.UNKNOWN || radar == null) limits += WeatherEvidenceLimit.RADAR_SUPPORT_UNKNOWN
    val expired = radar?.eta?.transitionAlreadyPassed == true
    radar?.evidence?.let {
      evidence += it.copy(precipitationState = if (radar?.usable == true && !expired) stateDbz(radar.atPointDbz) else null,
        limits = it.limits + WeatherEvidenceLimit.RADAR_GROUND_UNCERTAINTY)
    }
    if (expired) limits += WeatherEvidenceLimit.TRANSITION_EXPIRED
    val states = evidence.mapNotNull { it.precipitationState }.toSet()
    var result = when {
      states.isEmpty() && expired -> PrecipitationState.UNCERTAIN
      states.isEmpty() -> PrecipitationState.UNAVAILABLE
      states.size > 1 -> PrecipitationState.UNCERTAIN
      expired -> PrecipitationState.UNCERTAIN
      else -> states.single()
    }
    if (states.size > 1) limits += WeatherEvidenceLimit.CONFLICTING_SIGNALS
    if (evidence.any { it.type == WeatherEvidenceType.MODEL && it.precipitationState != null } && radar?.usable != true)
      limits += WeatherEvidenceLimit.MODEL_ONLY
    evidence.forEach { limits += it.limits }
    val horizon = WeatherInterval(now, now + HORIZON)
    val eta = radar?.eta?.takeIf { radar?.usable == true && !expired && result != PrecipitationState.UNCERTAIN && result != PrecipitationState.UNAVAILABLE }
    var arrival = eta?.arrivalAtMillis?.takeIf { it > now && it < horizon.endMillis }
    var end = eta?.endAtMillis?.takeIf { it > now && it <= horizon.endMillis }
    // Do not claim first arrival later than a wet forecast interval, or cessation across wet forecast.
    val divergent = timeline.any { period -> period.state == PrecipitationState.WET &&
      ((arrival != null && period.interval.startMillis < arrival!!) ||
        (end != null && period.interval.endMillis > end!!)) }
    if (divergent) {
      limits += WeatherEvidenceLimit.FUTURE_DIVERGENCE
      limits += WeatherEvidenceLimit.CONFLICTING_SIGNALS
      arrival = null; end = null; result = PrecipitationState.UNCERTAIN
    }
    return WeatherBrief(LocalPrecipitationAssessment(result, now, place, coordinates, evidence,
      horizon, arrival, end, limits, availability, if (arrival != null || end != null) eta?.temporalResolutionMillis else null),
      currentSummary, timeline, lastHour, identity)
  }

  private fun timeline(bundle: ForecastBundle, now: Long): List<PrecipitationPeriod> {
    val periods = mutableListOf<PrecipitationPeriod>()
    var cursor = now
    val end = now + HORIZON
    val groups = bundle.minutely.groupBy { it.timestampMillis }
    val firstEnd = now - Math.floorMod(now, QUARTER) + QUARTER
    var endpoint = firstEnd
    while (endpoint <= end) {
      val start = endpoint - QUARTER
      // Boundary fragments are unknown, never prorated accumulations.
      if (start >= now) {
        if (cursor < start) periods += PrecipitationPeriod(WeatherInterval(cursor, start))
        val point = groups[endpoint]?.singleOrNull()
        val mm = point?.precipitationMm?.takeIf { it.isFinite() && it >= 0 }
        val interval = WeatherInterval(start, endpoint)
        val e = modelEvidence(bundle, interval, bundle.fetchedAtMillis, endpoint, bundle.minutelyPossiblyInterpolated)
        periods += PrecipitationPeriod(interval, mm?.let(::state) ?: PrecipitationState.UNAVAILABLE, mm,
          point?.precipitationProbabilityPercent?.takeIf { mm != null && it.isFinite() && it in 0.0..100.0 },
          if (point != null) listOf(e) else emptyList(), PrecipitationPeriodRole.FORECAST)
        cursor = endpoint
      }
      endpoint += QUARTER
    }
    if (cursor < end) periods += PrecipitationPeriod(WeatherInterval(cursor, end))
    return periods
  }

  private fun modelEvidence(b: ForecastBundle, interval: WeatherInterval?, download: Long?, time: Long, interpolated: Boolean?): WeatherEvidence {
    val limits = mutableSetOf<WeatherEvidenceLimit>()
    if (interpolated != false) limits += WeatherEvidenceLimit.POSSIBLY_INTERPOLATED
    // No field in the provider contract establishes native resolution, even when cadence is 15m.
    limits += WeatherEvidenceLimit.COARSE_TIME_RESOLUTION
    if (interpolated == null || download == null) limits += WeatherEvidenceLimit.UNKNOWN_PROVENANCE
    return WeatherEvidence(b.providerId, WeatherEvidenceType.MODEL, interval, download, 3_600_000L,
      limits = limits, sourceTimestampMillis = time)
  }

  companion object {
    const val QUARTER = 900_000L
    const val RECENT_MAX_AGE = 20 * 60_000L
    const val CURRENT_MAX_AGE = 30 * 60_000L
    const val DOWNLOAD_MAX_AGE = 60 * 60_000L
    const val HORIZON = 60 * 60_000L
    const val MAX_DISTANCE_KM = 2.0
    private val WET_KINDS = setOf(WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN,
      WeatherKind.SLEET, WeatherKind.SNOW, WeatherKind.HEAVY_SNOW, WeatherKind.THUNDERSTORM)
    private fun fresh(time: Long?, now: Long, maxAge: Long) = time != null && time <= now && now - time <= maxAge
    private fun state(mm: Double) = if (mm > 0.0) PrecipitationState.WET else PrecipitationState.DRY
    private fun stateDbz(dbz: Int?) = dbz?.let { if (it >= 10) PrecipitationState.WET else PrecipitationState.DRY }
    private fun validPoint(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    /** Hash all supplied immutable input values, not just fetch time; excludes evaluation time. */
    fun revision(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?, radar: RadarSample? = null): String {
      // Age/sample clocks and registration bookkeeping are not changes in weather input.
      val stableRadar = (radar as? RadarSample.Ok)?.let {
        it.copy(reading = it.reading.copy(frameAgeMin = 0, sampledAtMillis = null, notes = emptyList()))
      } ?: radar
      val stableSnapshot = snapshot?.copy(predictionsRegisteredAtMillis = null)
      return MessageDigest.getInstance("SHA-256").digest("$place|$coordinates|$stableSnapshot|$stableRadar".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
  }
}
