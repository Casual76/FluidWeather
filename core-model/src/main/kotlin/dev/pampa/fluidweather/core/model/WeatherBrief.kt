package dev.pampa.fluidweather.core.model

/** A known point. Missing coordinates are represented by a null GeoPoint, never (0, 0). */
data class GeoPoint(val latitude: Double, val longitude: Double) {
  init {
    require(latitude.isFinite() && latitude in -90.0..90.0)
    require(longitude.isFinite() && longitude in -180.0..180.0)
  }
}

/** UTC epoch milliseconds; an effective interval is [startMillis, endMillis). */
data class WeatherInterval(val startMillis: Long, val endMillis: Long) {
  init { require(endMillis > startMillis) }
}

enum class PrecipitationState { WET, DRY, UNCERTAIN, UNAVAILABLE }
enum class WeatherEvidenceType { MODEL, RADAR_ESTIMATE, MEASUREMENT }
enum class WeatherEvidenceLimit {
  MODEL_ONLY, COARSE_TIME_RESOLUTION, COARSE_SPATIAL_RESOLUTION,
  STALE, PARTIAL_COVERAGE, CONFLICTING_SIGNALS, UNKNOWN_PROVENANCE,
  POSSIBLY_INTERPOLATED, RADAR_GROUND_UNCERTAINTY, TRANSITION_EXPIRED,
  LOCATION_MISMATCH, MISSING_COORDINATES, FUTURE_DIVERGENCE,
  RADAR_UNAVAILABLE, RADAR_SUPPORT_UNKNOWN,
}

/**
 * Provenance survives caching and the passage of time: MODEL never becomes MEASUREMENT.
 * sourceId is a stable provider/product identifier, not localized display text.
 * Null effectiveInterval/downloadedAtMillis/resolutions mean unknown, not zero.
 * precipitationState describes this source's signal, allowing conflicting evidence to coexist.
 */
data class WeatherEvidence(
  val sourceId: String,
  val type: WeatherEvidenceType,
  val effectiveInterval: WeatherInterval? = null,
  val downloadedAtMillis: Long? = null,
  val temporalResolutionMillis: Long? = null,
  val spatialResolutionMeters: Double? = null,
  val limits: Set<WeatherEvidenceLimit> = emptySet(),
  val precipitationState: PrecipitationState? = null,
  /** Source valid time or composite generation, separate from download and evaluation. */
  val sourceTimestampMillis: Long? = null,
) {
  init {
    require(sourceId.isNotBlank())
    require(temporalResolutionMillis == null || temporalResolutionMillis > 0)
    require(spatialResolutionMeters == null || (spatialResolutionMeters.isFinite() && spatialResolutionMeters > 0))
  }
}

/**
 * Shared local result; no evaluator or confidence/arrival precision is invented by this model.
 * evaluatedAtMillis is computation time, NOT download time. horizon is the interval assessed.
 * Arrival/end are absolute estimates within that horizon; null means unknown.
 * UNAVAILABLE has no usable result (it may retain failed/stale evidence for explanation).
 */
data class LocalPrecipitationAssessment(
  val state: PrecipitationState,
  val evaluatedAtMillis: Long,
  val place: Place? = null,
  val coordinates: GeoPoint? = null,
  val evidence: List<WeatherEvidence> = emptyList(),
  val horizon: WeatherInterval? = null,
  val arrivalAtMillis: Long? = null,
  val endAtMillis: Long? = null,
  val limits: Set<WeatherEvidenceLimit> = emptySet(),
  val radarAvailability: RadarAvailability = RadarAvailability.UNKNOWN,
  val transitionResolutionMillis: Long? = null,
) {
  init {
    require(state == PrecipitationState.UNAVAILABLE || evidence.isNotEmpty())
    require(arrivalAtMillis == null || (horizon != null && arrivalAtMillis >= horizon.startMillis && arrivalAtMillis < horizon.endMillis))
    require(endAtMillis == null || (horizon != null && endAtMillis >= horizon.startMillis && endAtMillis <= horizon.endMillis))
    require(arrivalAtMillis == null || endAtMillis == null || endAtMillis >= arrivalAtMillis)
    require(state != PrecipitationState.UNAVAILABLE || (arrivalAtMillis == null && endAtMillis == null))
  }
}

/** Current weather can itself be model output. Unknown fields stay null. */
data class CurrentWeatherSummary(
  val kind: WeatherKind? = null,
  val temperatureC: Double? = null,
  val evidence: List<WeatherEvidence> = emptyList(),
) {
  init {
    require(temperatureC == null || temperatureC.isFinite())
    require((kind == null && temperatureC == null) || evidence.isNotEmpty())
  }
}

/** One result for home, widgets and AI. Consumers localize enum values; no display prose here. */
data class WeatherBrief(
  val precipitation: LocalPrecipitationAssessment,
  val current: CurrentWeatherSummary? = null,
  val timeline: List<PrecipitationPeriod> = emptyList(),
  val rainLastHour: RainAccumulation? = null,
  /** Identity of inputs, stable across evaluation clock changes. */
  val revision: String = "",
  /** Personal forecast windows, evaluated in the consulted place's zone; empty by default. */
  val routines: List<RoutineWindowAssessment> = emptyList(),
)


/** Capability/data availability, independent from the wet/dry conclusion. */
enum class RadarAvailability { AVAILABLE, OUTSIDE, UNKNOWN, UNAVAILABLE }
enum class PrecipitationPeriodRole { RECENT_ACCUMULATION, FORECAST, UNKNOWN }

data class PrecipitationPeriod(
  val interval: WeatherInterval,
  val state: PrecipitationState = PrecipitationState.UNAVAILABLE,
  val amountMm: Double? = null,
  val probabilityPercent: Double? = null,
  val evidence: List<WeatherEvidence> = emptyList(),
  val role: PrecipitationPeriodRole = PrecipitationPeriodRole.UNKNOWN,
) {
  init {
    require(amountMm == null || amountMm.isFinite() && amountMm >= 0)
    require(probabilityPercent == null || probabilityPercent.isFinite() && probabilityPercent in 0.0..100.0)
    require(state == PrecipitationState.UNAVAILABLE || evidence.isNotEmpty())
  }
}
