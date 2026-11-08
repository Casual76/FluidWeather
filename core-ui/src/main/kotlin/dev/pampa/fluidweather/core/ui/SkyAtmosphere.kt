package dev.pampa.fluidweather.core.ui

/** Visual traits, not measured cloud geometry or a claim of local observation.
 * All fields are 0..1 except windSpeedFactor (0.25..2). Null SkyState.atmosphere uses legacy art.
 */
data class SkyAtmosphere(
  val cloudDepth: Float = 0f,
  val cloudCoverage: Float = 0f,
  val cloudDarkness: Float = 0f,
  val diffuseLight: Float = 0f,
  val haze: Float = 0f,
  val windSpeedFactor: Float = 1f,
  val precipitationStrength: Float = 0f,
  val storminess: Float = 0f,
)
