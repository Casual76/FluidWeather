package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.CachedWeatherBriefSource
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> widgetLocalRead(block: suspend () -> T): T? = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }

internal data class AppWidgetPlace(val place: Place, val snapshot: WeatherSnapshot?) {
  val point: GeoPoint? get() = snapshot?.let { widgetPoint(it.latitude, it.longitude) }
    ?: widgetPoint(place.latitude, place.longitude)
}

internal fun widgetPoint(latitude: Double, longitude: Double): GeoPoint? =
  if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
    GeoPoint(latitude, longitude) else null

internal fun widgetSnapshotMatches(place: Place, snapshot: WeatherSnapshot): Boolean =
  snapshot.placeKey == (if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)) &&
    widgetPoint(snapshot.latitude, snapshot.longitude) != null &&
    (place.isGps || (widgetPoint(place.latitude, place.longitude) != null &&
      snapshot.distanceKmTo(place.latitude, place.longitude) <= 2.0))

/** A bounded retry drops unstable contexts; the caller renders unavailable until the next update. */
internal suspend fun readAppWidgetBrief(source: CachedWeatherBriefSource,
  resolve: suspend () -> AppWidgetPlace?): Pair<AppWidgetPlace, WeatherBrief>? {
  repeat(3) {
    val before = resolve() ?: return null
    val token = source.cachedInputUpdates.value
    val brief = widgetLocalRead { source.cachedBrief(before.place, before.point, before.snapshot) }
      ?: WeatherBrief(LocalPrecipitationAssessment(PrecipitationState.UNAVAILABLE,
        System.currentTimeMillis(), before.place, before.point))
    currentCoroutineContext().ensureActive()
    val after = resolve()
    currentCoroutineContext().ensureActive()
    if (before == after && token == source.cachedInputUpdates.value &&
      brief.precipitation.place == before.place && brief.precipitation.coordinates == before.point) return before to brief
  }
  return null
}
