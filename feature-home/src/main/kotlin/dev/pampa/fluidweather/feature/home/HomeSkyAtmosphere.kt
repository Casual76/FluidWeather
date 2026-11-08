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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

/** Live home and launcher use the same cache-only evidence adapter. */
internal fun deriveHomeSkyAtmosphere(
  state: HomeUiState, snapshot: WeatherSnapshot?, nowMillis: Long,
): SkyAtmosphere? = SkyAtmosphereEvidence.derive(
  snapshot?.context, state.weatherBrief?.precipitation, state.latitude, state.longitude, nowMillis,
)
