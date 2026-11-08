package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.flow.StateFlow

/** Read-only local evaluation. Implementations must not start provider, radar or location work. */
interface CachedWeatherBriefSource {
  val cachedInputUpdates: StateFlow<Long>
  suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief
}
