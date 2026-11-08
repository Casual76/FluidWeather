package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.PrecipitationState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** UTC clocks belong to the original source, never the UI evaluation/round clock.
 * Hourly fields are model context, not present precipitation evidence. No probability input.
 */
data class SkyAtmosphereSample(
  val validAtMillis: Long,
  val downloadedAtMillis: Long?,
  val cloudCoverPercent: Double? = null,
  val relativeHumidityPercent: Double? = null,
  val visibilityMeters: Double? = null,
  val windSpeedKmh: Double? = null,
  /** Only an accumulation with known duration/anchor may be converted to this mean rate. */
  val precipitationRateMmPerHour: Double? = null,
)

data class SkyPresentPrecipitation(
  val state: PrecipitationState,
  val sourceAtMillis: Long?,
  val downloadedAtMillis: Long?,
  val rateMmPerHour: Double? = null,
  val conflicting: Boolean = false,
)

data class SkyAtmosphereInput(
  val nowMillis: Long,
  val current: SkyAtmosphereSample? = null,
  val approaching: SkyAtmosphereSample? = null,
  val precipitation: SkyPresentPrecipitation? = null,
)

/** Pure, continuous art direction. Constants are calibration knobs, not meteorological thresholds.
 * Future context can thicken cloud/filter light; it can never create present rain or lightning.
 */
object SkyAtmospherePolicy {
  private const val MINUTE = 60_000L

  fun derive(input: SkyAtmosphereInput): SkyAtmosphere {
    val now = input.nowMillis
    val c = input.current
    val trust = c?.let {
      downloadWeight(it.downloadedAtMillis, now) *
        (1.0 - abs(now.toDouble() - it.validAtMillis) / (90 * MINUTE)).coerceIn(0.0, 1.0)
    } ?: 0.0
    val coverage = percent(c?.cloudCoverPercent) ?: 0.35
    val humidity = percent(c?.relativeHumidityPercent)
    val rain = input.precipitation
    val rainTrust = if (rain?.state == PrecipitationState.WET && !rain.conflicting &&
      rain.sourceAtMillis != null && rain.downloadedAtMillis != null &&
      rain.sourceAtMillis <= rain.downloadedAtMillis) {
      downloadWeight(rain.downloadedAtMillis, now) * recentWeight(rain.sourceAtMillis, now)
    } else 0.0
    // Unknown intensity remains a faint shower, even if a weather code says heavy/thunder.
    val rate = positive(rain?.rateMmPerHour)
    val strength = rainTrust * (rate?.let { it / (it + 3.0) } ?: 0.12)
    val currentRate = positive(c?.precipitationRateMmPerHour) ?: 0.0
    val wetCloud = currentRate / (currentRate + 3.0)
    var depth = (0.12 + coverage * 0.55 + wetCloud * 0.22) * coverage
    var darkness = coverage * (depth * 0.48 + wetCloud * 0.22)
    var filtered = coverage * (0.32 + depth * 0.68)
    val f = input.approaching
    val lead = f?.let { (it.validAtMillis.toDouble() - now) / MINUTE }
    val approach = if (f != null && lead != null && lead > 0 && lead <= 120) {
      (1.0 - lead / 150.0) * 0.28 * downloadWeight(f.downloadedAtMillis, now)
    } else 0.0
    val futureCoverage = percent(f?.cloudCoverPercent) ?: coverage
    val futureRate = positive(f?.precipitationRateMmPerHour) ?: 0.0
    val front = approach * max(max(0.0, futureCoverage - coverage),
      futureCoverage * max(0.0, futureRate / (futureRate + 3.0) - wetCloud))
    depth += front * (0.6 + 0.4 * futureRate / (futureRate + 3.0))
    darkness += front * 0.5
    filtered += front * 0.7
    val visibility = positive(c?.visibilityMeters)
    // Humidity alone cannot prove fog; it adds at most a subtle veil.
    val haze = visibility?.let { (1.0 - it / 20_000.0).coerceIn(0.0, 1.0) * 0.85 }
      ?: humidity?.let { ((it - 0.75) / 0.25).coerceIn(0.0, 1.0) * 0.16 } ?: 0.0
    val wind = positive(c?.windSpeedKmh)?.let { 0.25 + 1.75 * it / (it + 30.0) } ?: 1.0
    return SkyAtmosphere(
      cloudDepth = bounded(depth * trust),
      cloudCoverage = bounded(0.35 + (coverage - 0.35) * trust + front * trust),
      cloudDarkness = bounded(darkness * trust),
      diffuseLight = bounded(filtered * trust),
      haze = bounded(haze * trust),
      windSpeedFactor = (1.0 + (wind - 1.0) * trust).toFloat().coerceIn(0.25f, 2f),
      precipitationStrength = bounded(strength),
      // No available contract proves active local lightning. Keep it off, including for fronts.
      storminess = 0f,
    )
  }

  private fun percent(v: Double?) = v?.takeIf { it.isFinite() && it in 0.0..100.0 }?.div(100.0)
  private fun positive(v: Double?) = v?.takeIf { it.isFinite() && it >= 0.0 }
  private fun bounded(v: Double) = v.toFloat().coerceIn(0f, 1f)
  private fun downloadWeight(time: Long?, now: Long): Double {
    if (time == null || time > now) return 0.0
    return min(1.0, ((90 * MINUTE - (now.toDouble() - time)) / (30 * MINUTE))).coerceAtLeast(0.0)
  }
  private fun recentWeight(time: Long?, now: Long): Double {
    if (time == null || time > now) return 0.0
    return min(1.0, ((20 * MINUTE - (now.toDouble() - time)) / (5 * MINUTE))).coerceAtLeast(0.0)
  }
}
