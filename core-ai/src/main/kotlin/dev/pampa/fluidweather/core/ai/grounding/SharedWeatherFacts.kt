package dev.pampa.fluidweather.core.ai.grounding

import dev.pampa.fluidweather.core.ai.data.*
import dev.pampa.fluidweather.core.ai.tools.ToolContext
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.*
import dev.pampa.fluidweather.strings.WeatherBriefFormatter
import kotlinx.coroutines.currentCoroutineContext
import java.time.ZoneId

/** No duplicate precipitation algorithm: both consumers call F2 and the same F4 evaluator. */
suspend fun sharedWeatherFacts(place: ResolvedPlace, resolver: WeatherPlaceResolver, ctx: ToolContext): String {
  val sources = ctx.sources
  val generation = sources.generation()
  val windows = sources.routineWindowStore.current()
  val snapshot = resolver.snapshot(place)
  val point = GeoPoint(place.latitude, place.longitude)
  val domain = Place(place.placeId ?: PlaceResolver.adHocId(place.latitude, place.longitude),
    place.name, place.region, place.latitude, place.longitude, place.zoneId)
  val brief = sources.weatherBriefService.brief(domain, point, snapshot)
  val latest = sources.snapshotStore.read(place.snapshotKey)
  val grounding = currentCoroutineContext()[ChatGrounding]
  if (generation != sources.generation() || latest != snapshot || windows != sources.routineWindowStore.current()) {
    grounding?.invalidate()
    return grounding?.changed() ?: "Weather context changed; retry."
  }
  val now = System.currentTimeMillis()
  val displayPlace = domain.copy(zoneId = WeatherZones.validId(domain.zoneId) ?: consultedSnapshotZone(place, snapshot))
  val enriched = brief.copy(precipitation = brief.precipitation.copy(place = displayPlace),
    routines = RoutineWindowEvaluator().evaluate(windows, domain, point, snapshot, now, ZoneId.systemDefault(), Int.MAX_VALUE))
  val document = WeatherBriefFormatter.document(ctx.resources, enriched, place.label, ctx.units, ctx.locale, now)
  return grounding?.document(document) ?: document.render()
}

/** Context metadata can itself belong to another point; it must pass the same distance bound. */
internal fun consultedSnapshotZone(place: ResolvedPlace, snapshot: WeatherSnapshot?): String? {
  val local = snapshot?.takeIf { it.placeKey == place.snapshotKey &&
    it.distanceKmTo(place.latitude, place.longitude) <= 2.0 } ?: return null
  return WeatherZones.validId(local.zoneId) ?: local.context?.takeIf {
    haversineKm(it.latitude, it.longitude, place.latitude, place.longitude) <= 2.0
  }?.zoneId?.let(WeatherZones::validId)
}
