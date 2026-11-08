package dev.pampa.fluidweather.core.model

/** One real occurrence in the consulted place's zone; days refer to the configured START day. */
data class RoutineOccurrence(
  val routine: RoutineWindow,
  val interval: WeatherInterval,
  val zone: ResolvedWeatherZone,
  /** True when resolving the wall times crosses a gap or overlap in the zone rules. */
  val adjustedForDst: Boolean = false,
)

/** Forecast only: missing parts never count as dry or as zero accumulated rain. */
data class RoutineWindowAssessment(
  val occurrence: RoutineOccurrence,
  val evaluatedAtMillis: Long,
  val precipitation: PrecipitationState = PrecipitationState.UNAVAILABLE,
  /** Union of actually represented precipitation intervals contained in the occurrence. */
  val coveredMillis: Long = 0L,
  /** Total only when the occurrence has exact, nonduplicated precipitation coverage. */
  val rainAmountMm: Double? = null,
  val maxProbabilityPercent: Double? = null,
  val minTemperatureC: Double? = null,
  val maxTemperatureC: Double? = null,
  val evidence: List<WeatherEvidence> = emptyList(),
  val limits: Set<WeatherEvidenceLimit> = emptySet(),
  /** For an ongoing occurrence, only the remaining portion is evaluated as a future forecast. */
  val assessedInterval: WeatherInterval = occurrence.interval,
) {
  init {
    require(assessedInterval.startMillis >= occurrence.interval.startMillis && assessedInterval.endMillis <= occurrence.interval.endMillis)
    require(coveredMillis in 0L..(assessedInterval.endMillis - assessedInterval.startMillis))
    require(rainAmountMm == null || rainAmountMm.isFinite() && rainAmountMm >= 0)
    require(maxProbabilityPercent == null || maxProbabilityPercent.isFinite() && maxProbabilityPercent in 0.0..100.0)
    require(minTemperatureC == null || minTemperatureC.isFinite())
    require(maxTemperatureC == null || maxTemperatureC.isFinite())
    require(minTemperatureC == null || maxTemperatureC == null || minTemperatureC <= maxTemperatureC)
    require(precipitation == PrecipitationState.UNAVAILABLE || evidence.isNotEmpty())
    require((minTemperatureC == null && maxTemperatureC == null && maxProbabilityPercent == null) || evidence.isNotEmpty())
    require(coveredMillis == 0L || evidence.isNotEmpty())
    require(rainAmountMm == null || hasCompleteCoverage)
  }
  val hasCompleteCoverage: Boolean get() =
    coveredMillis == assessedInterval.endMillis - assessedInterval.startMillis
}
