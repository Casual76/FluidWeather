package dev.pampa.fluidweather.core.model

enum class BriefRainHeadline { MODEL_WET, MODEL_DRY, RADAR_WET, RADAR_NO_ECHO, WET, DRY, CONFLICT, UNCERTAIN, UNAVAILABLE }
data class BriefRainTransition(val arrival: Boolean, val startMillis: Long, val endMillis: Long)

/** Existing F3 policy, shared with summaries/chat without depending on a UI module. */
object WeatherBriefPresentation {
  fun headline(brief: WeatherBrief?): BriefRainHeadline {
    val rain = brief?.precipitation ?: return BriefRainHeadline.UNAVAILABLE
    if (rain.state == PrecipitationState.UNAVAILABLE) return BriefRainHeadline.UNAVAILABLE
    if (WeatherEvidenceLimit.CONFLICTING_SIGNALS in rain.limits) return BriefRainHeadline.CONFLICT
    if (rain.state == PrecipitationState.UNCERTAIN) return BriefRainHeadline.UNCERTAIN
    val types = rain.evidence.filter { it.precipitationState != null }.map { it.type }.toSet()
    val modelOnly = WeatherEvidenceLimit.MODEL_ONLY in rain.limits || types == setOf(WeatherEvidenceType.MODEL)
    val radarOnly = types == setOf(WeatherEvidenceType.RADAR_ESTIMATE)
    return when (rain.state) {
      PrecipitationState.WET -> if (modelOnly) BriefRainHeadline.MODEL_WET else if (radarOnly) BriefRainHeadline.RADAR_WET else BriefRainHeadline.WET
      PrecipitationState.DRY -> if (modelOnly) BriefRainHeadline.MODEL_DRY else if (radarOnly) BriefRainHeadline.RADAR_NO_ECHO else BriefRainHeadline.DRY
      else -> BriefRainHeadline.UNAVAILABLE
    }
  }

  fun transition(brief: WeatherBrief?, now: Long): BriefRainTransition? {
    val rain = brief?.precipitation ?: return null
    if (rain.state !in setOf(PrecipitationState.WET, PrecipitationState.DRY)) return null
    if (rain.limits.any { it in setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS, WeatherEvidenceLimit.TRANSITION_EXPIRED, WeatherEvidenceLimit.FUTURE_DIVERGENCE, WeatherEvidenceLimit.MODEL_ONLY) }) return null
    if (rain.evidence.none { it.type == WeatherEvidenceType.RADAR_ESTIMATE && it.precipitationState != null }) return null
    val arrival = rain.state == PrecipitationState.DRY
    val instant = (if (arrival) rain.arrivalAtMillis else rain.endAtMillis) ?: return null
    if (instant <= now || rain.horizon?.endMillis?.let { instant > it } != false) return null
    val resolution = maxOf(600_000L, rain.transitionResolutionMillis ?: 600_000L)
    val start = (instant / resolution) * resolution
    return BriefRainTransition(arrival, start, start + resolution)
  }
}
