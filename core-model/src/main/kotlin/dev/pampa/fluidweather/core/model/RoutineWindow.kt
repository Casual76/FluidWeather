package dev.pampa.fluidweather.core.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * Recurring local wall-clock window. daysOfWeek refers to the START day; end < start
 * ends on the next day. Equal start/end is rejected (not an implicit 24-hour window).
 * DST evaluation, persistence and editing are intentionally deferred to F4.
 */
data class RoutineWindow(
  val id: String,
  val name: String,
  val daysOfWeek: Set<DayOfWeek>,
  val start: LocalTime,
  val end: LocalTime,
) {
  init {
    require(id.isNotBlank() && name.isNotBlank())
    require(daysOfWeek.isNotEmpty())
    require(start != end)
  }
  val crossesMidnight: Boolean get() = end < start
}

enum class WeatherZoneSource { PLACE, SNAPSHOT, DEVICE_FALLBACK }
data class ResolvedWeatherZone(val zoneId: ZoneId, val source: WeatherZoneSource)

/** IANA/tzdb identifiers only, including UTC; arbitrary fixed offsets are not place zones. */
object WeatherZones {
  fun validId(value: String?): String? = value?.takeIf { it in ZoneId.getAvailableZoneIds() }

  /** The caller supplies the device zone explicitly; it is never persisted as a place fact. */
  fun resolve(placeZoneId: String?, snapshotZoneId: String?, deviceZone: ZoneId): ResolvedWeatherZone =
    validId(placeZoneId)?.let { ResolvedWeatherZone(ZoneId.of(it), WeatherZoneSource.PLACE) }
      ?: validId(snapshotZoneId)?.let { ResolvedWeatherZone(ZoneId.of(it), WeatherZoneSource.SNAPSHOT) }
      ?: ResolvedWeatherZone(deviceZone, WeatherZoneSource.DEVICE_FALLBACK)
}
