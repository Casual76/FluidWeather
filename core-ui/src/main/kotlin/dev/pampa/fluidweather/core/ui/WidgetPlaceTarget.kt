package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.Place

/** Resolve only repository identities. Missing/deleted targets fall back explicitly to GPS. */
object WidgetPlaceTarget {
  fun resolve(requestedId: Long?, places: List<Place>): Long? = when {
    requestedId == null -> null // Legacy/assistant links preserve the session selection.
    requestedId == Place.GPS_ID -> Place.GPS_ID
    places.any { !it.isGps && it.id == requestedId } -> requestedId
    else -> Place.GPS_ID
  }
}
