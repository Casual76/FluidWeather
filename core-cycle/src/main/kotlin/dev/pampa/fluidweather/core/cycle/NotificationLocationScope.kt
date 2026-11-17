package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.LocalNotificationLedger
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationLocation
import kotlin.math.*

/** Compare to the stored anchor, not a rounded GPS cell whose edges can cause duplicate alerts. */
internal fun NotificationLocation.matches(other: NotificationLocation): Boolean {
  if (placeKey != other.placeKey) return false
  val lat = Math.toRadians(other.latitude - latitude)
  val lon = Math.toRadians(other.longitude - longitude)
  val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) *
    cos(Math.toRadians(other.latitude)) * sin(lon / 2).pow(2)
  return 2 * 6_371.0 * asin(sqrt(a.coerceIn(0.0, 1.0))) <= 5.0
}

internal fun NotificationLedger.atLocation(point: NotificationLocation?): NotificationLedger {
  if (point == null || location?.matches(point) == true) return this
  val restored = previousLocations.lastOrNull { it.location.matches(point) }
  val previous = previousLocations.filterNot { it.location.matches(point) }.toMutableList()
  location?.let {
    previous += LocalNotificationLedger(it, nowcastAlertAtMillis, nowcastAlertProbability,
      nowcastAlertActive, precipitationOnsetMillis, precipitationEndMillis)
  }
  return copy(
    location = restored?.location ?: point,
    nowcastAlertAtMillis = restored?.nowcastAlertAtMillis,
    nowcastAlertProbability = restored?.nowcastAlertProbability,
    // A system alert belongs to the point currently being evaluated; re-entry must earn a new post.
    nowcastAlertActive = false,
    precipitationOnsetMillis = restored?.precipitationOnsetMillis,
    precipitationEndMillis = restored?.precipitationEndMillis,
    previousLocations = previous.takeLast(NotificationLedger.MAX_LOCAL_LOCATIONS),
  )
}
