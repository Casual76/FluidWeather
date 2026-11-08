package dev.pampa.fluidweather.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.ui.*
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.haversineKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Reads only the existing local snapshot. No feed, request, sensor or per-frame work.
 * Composition/lifecycle/visibility cancellation also stops the minute consumption clock.
 */
@Composable
internal fun homeSkyAtmosphere(
  state: HomeUiState,
  deps: HomeDependencies,
  place: Place,
  active: Boolean,
): SkyAtmosphere? {
  val owner = LocalLifecycleOwner.current
  val snapshotKey = if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)
  return key(snapshotKey, state.latitude, state.longitude) {
    val atmosphere by produceState<SkyAtmosphere?>(null, state.dataAtMillis, state.weatherBrief,
      state.latitude, state.longitude, snapshotKey, active, owner) {
      if (!active) return@produceState
      owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        val snapshot = deps.snapshotStore.read(snapshotKey)
        while (isActive) {
          value = deriveHomeSkyAtmosphere(state, snapshot, System.currentTimeMillis())
          delay(60_000L)
        }
      }
    }
    atmosphere
  }
}

/** Adapter keeps original context clocks; snapshot.fetchedAtMillis is only a round timestamp.
 * FusedHour values have no per-contributor download or accumulation interval, so are deliberately
 * not used for intensity/freshness. Old snapshots without a context download degrade to neutral.
 */
internal fun deriveHomeSkyAtmosphere(
  state: HomeUiState,
  snapshot: WeatherSnapshot?,
  nowMillis: Long,
): SkyAtmosphere {
  val lat = state.latitude
  val lon = state.longitude
  val bundle = snapshot?.context?.takeIf {
    lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
      haversineKm(lat, lon, it.latitude, it.longitude) <= 2.0
  }
  fun sample(point: HourlyPoint): SkyAtmosphereSample = SkyAtmosphereSample(
    point.timestampMillis, bundle?.fetchedAtMillis,
    point.cloudCoverPercent, point.relativeHumidityPercent, point.visibilityMeters,
    point.windSpeedKmh, knownRate(point.precipitationMm, bundle?.hourlyPrecipitation),
  )
  val hours = bundle?.hourly.orEmpty()
  // Context near now is model atmosphere only. It never authorizes present rain.
  val current = hours.minByOrNull { abs(it.timestampMillis.toDouble() - nowMillis) }
    ?.takeIf { abs(it.timestampMillis.toDouble() - nowMillis) <= 90 * 60_000L }
    ?.let(::sample)
  val approaching = hours.filter { it.timestampMillis > nowMillis + 30 * 60_000L &&
    it.timestampMillis <= nowMillis + 120 * 60_000L }
    .minByOrNull { it.timestampMillis }?.let(::sample)
  val assessment = state.weatherBrief?.precipitation
  val conflict = assessment?.limits.orEmpty().any { it in setOf(
    WeatherEvidenceLimit.CONFLICTING_SIGNALS, WeatherEvidenceLimit.LOCATION_MISMATCH,
    WeatherEvidenceLimit.TRANSITION_EXPIRED,
  ) }
  val evidence = assessment?.evidence.orEmpty().filter {
    it.precipitationState == PrecipitationState.WET &&
      WeatherEvidenceLimit.STALE !in it.limits &&
      WeatherEvidenceLimit.LOCATION_MISMATCH !in it.limits
  }.maxByOrNull { it.sourceTimestampMillis ?: Long.MIN_VALUE }
  // Intensity must be from the very same supported recent model interval as the wet evidence.
  // A future quarter passing on the UI clock does not become current evidence.
  val currentRain = bundle?.current
  val recentQuarter = bundle?.rainNow(nowMillis, 20 * 60_000L)
  val rate = when {
    evidence == null || evidence.type != WeatherEvidenceType.MODEL || evidence.sourceId != bundle?.providerId -> null
    currentRain != null && evidence.effectiveInterval != null &&
      evidence.effectiveInterval == currentRain.precipitationInterval &&
      currentRain.accumulationIntervalMillis?.let { it <= 900_000L } == true &&
      evidence.downloadedAtMillis == currentRain.downloadedAtMillis ->
      knownRate(currentRain.precipitationMm,
        PrecipitationAccumulation(currentRain.accumulationIntervalMillis, AccumulationAnchor.PRECEDING))
    bundle?.minutelyPrecipitation?.durationMillis == 900_000L &&
      bundle.minutelyPrecipitation?.anchor == AccumulationAnchor.PRECEDING &&
      recentQuarter != null && recentQuarter.interval == evidence.effectiveInterval &&
      recentQuarter.interval.endMillis <= bundle.fetchedAtMillis &&
      evidence.downloadedAtMillis == bundle.fetchedAtMillis -> recentQuarter.amountMm * 4.0
    else -> null
  }
  val present = assessment?.let {
    SkyPresentPrecipitation(it.state, evidence?.sourceTimestampMillis,
      evidence?.downloadedAtMillis, rate, conflict || bundle == null)
  }
  return SkyAtmospherePolicy.derive(SkyAtmosphereInput(nowMillis, current, approaching, present))
}

private fun knownRate(amount: Double?, metadata: PrecipitationAccumulation?): Double? {
  val duration = metadata?.durationMillis ?: return null
  if (metadata.anchor == AccumulationAnchor.UNKNOWN || duration !in 1..10_800_000L) return null
  return amount?.takeIf { it.isFinite() && it >= 0 }?.let { it * 3_600_000.0 / duration }
}
