package dev.pampa.fluidweather.feature.home.pages

import dev.pampa.fluidweather.core.model.PrecipitationUnit
import dev.pampa.fluidweather.strings.UnitFormatter

/** Unknown, known zero and a positive amount below display precision remain distinct. */
internal fun precipitationSampleValue(mm: Double?, units: UnitFormatter): String {
  if (mm == null || !mm.isFinite() || mm < 0) return "\u2014"
  val quantumMm = if (units.units.precipitation == PrecipitationUnit.MM) 0.1 else 0.254
  return if (mm > 0 && mm < quantumMm / 2) "<" + units.precipitationValue(quantumMm)
    else units.precipitationValue(mm)
}

internal fun precipitationProbability(value: Double?): String = when {
  value == null || !value.isFinite() || value !in 0.0..100.0 -> "\u2014"
  value > 0 && value < 1 -> "<1%"
  else -> "${kotlin.math.round(value).toInt()}%"
}
