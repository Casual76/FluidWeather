package dev.pampa.fluidweather.core.ai.grounding

import dev.pampa.fluidweather.core.ai.data.ResolvedPlace
import dev.pampa.fluidweather.core.weather.haversineKm

/** Recheck the selected weather context without interpreting normal GPS jitter as a new place. */
internal fun sameSelectedWeatherPlace(before: ResolvedPlace?, after: ResolvedPlace?): Boolean {
  if (before == null || after == null) return before == after
  if (!before.isGps || !after.isGps) return before == after
  if (before.snapshotKey != after.snapshotKey || before.placeId != after.placeId ||
    before.isSelected != after.isSelected || before.isSaved != after.isSaved ||
    before.fromFreshFix != after.fromFreshFix || before.zoneId != after.zoneId) return false
  fun valid(place: ResolvedPlace) = place.latitude.isFinite() && place.longitude.isFinite() &&
    place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0
  return valid(before) && valid(after) &&
    haversineKm(before.latitude, before.longitude, after.latitude, after.longitude) <= 2.0
}
