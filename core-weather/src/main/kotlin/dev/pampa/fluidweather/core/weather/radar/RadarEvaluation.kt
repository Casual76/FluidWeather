package dev.pampa.fluidweather.core.weather.radar

import dev.pampa.fluidweather.core.model.WeatherEvidence
import dev.pampa.fluidweather.core.model.WeatherEvidenceLimit
import dev.pampa.fluidweather.core.model.WeatherEvidenceType

/** A source-local gate, not the shared multi-source precipitation verdict. */
data class RadarEvaluation(
  val evaluatedAtMillis: Long,
  val usable: Boolean,
  val geographicCoverage: GeographicCoverage,
  val frameTimeMillis: Long? = null,
  val atPointDbz: Int? = null,
  val evidence: WeatherEvidence? = null,
  val eta: AbsoluteRadarEta? = null,
)

/** Entry point for F2 C. No echo is a radar signal, not proof of dry ground. */
fun RadarSample.evaluateAt(evaluatedAtMillis: Long): RadarEvaluation {
  val reading = (this as? RadarSample.Ok)?.reading
    ?: return RadarEvaluation(evaluatedAtMillis, false, (this as RadarSample.Unavailable).geographicCoverage)
  val usable = reading.isUsableAt(evaluatedAtMillis)
  val limits = mutableSetOf(WeatherEvidenceLimit.COARSE_TIME_RESOLUTION, WeatherEvidenceLimit.COARSE_SPATIAL_RESOLUTION)
  if (evaluatedAtMillis - reading.frameTimeMillis > RadarSafety.MAX_FRAME_AGE_MILLIS) limits += WeatherEvidenceLimit.STALE
  if (!reading.complete || reading.geographicCoverage != GeographicCoverage.SUPPORTED) limits += WeatherEvidenceLimit.PARTIAL_COVERAGE
  return RadarEvaluation(
    evaluatedAtMillis, usable, reading.geographicCoverage, reading.frameTimeMillis,
    reading.atPointDbz.takeIf { usable },
    WeatherEvidence(
      sourceId = "rainviewer-radar", type = WeatherEvidenceType.RADAR_ESTIMATE,
      // Composite generation time is not a known physical measurement interval.
      sourceTimestampMillis = reading.frameTimeMillis,
      effectiveInterval = null, downloadedAtMillis = reading.tilesDownloadedAtMillis,
      temporalResolutionMillis = reading.temporalResolutionMillis,
      spatialResolutionMeters = reading.spatialResolutionMeters, limits = limits,
    ),
    reading.absoluteEta(evaluatedAtMillis),
  )
}
