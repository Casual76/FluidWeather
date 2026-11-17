package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*

/**
 * A view of original provider products, not an interpolator. Instantaneous fields keep their
 * source timestamp. Only a known one-hour accumulation can contribute to the fused hour ending
 * at that timestamp; a three-hour total is never divided or repeated into hourly amounts.
 */
internal class ForecastProducts(val bundle: ForecastBundle) {
  private val points = bundle.hourly.groupBy { it.timestampMillis }
    .mapNotNull { (time, rows) -> rows.singleOrNull()?.takeIf { time >= 0L }?.let { time to it } }.toMap()
  private val rain = intervalPoints(bundle.hourlyPrecipitation)
  private val probability = when (ProviderWindowPop.conventionOf(bundle.providerId)) {
    PopConvention.HOUR_ENDING -> intervalPoints(PrecipitationAccumulation(HOUR_MILLIS, AccumulationAnchor.PRECEDING))
    PopConvention.HOUR_STARTING -> intervalPoints(PrecipitationAccumulation(HOUR_MILLIS, AccumulationAnchor.FOLLOWING))
    // A probability over three hours is not an hourly probability either.
    PopConvention.THREE_HOUR_ENDING, PopConvention.NONE -> emptyMap()
  }

  val timestamps: Set<Long> = points.keys + rain.keys + probability.keys
  private val orderedTimestamps = timestamps.sorted()

  fun pointAt(timestamp: Long): HourlyPoint? = points[timestamp]

  fun valueAt(timestamp: Long, variable: String): Double? {
    val point = when (variable) {
      FusionVariables.PRECIPITATION -> rain[timestamp]
      FusionVariables.PRECIP_PROBABILITY -> probability[timestamp]
      else -> points[timestamp]
    } ?: return null
    return FusionVariables.of(point, variable)?.takeIf { validValue(variable, it) }
  }

  fun firstValueAtOrAfter(timestamp: Long, variable: String, withinMillis: Long): Pair<Long, Double>? =
    orderedTimestamps.asSequence().filter { it >= timestamp && it - timestamp <= withinMillis }
      .mapNotNull { at -> valueAt(at, variable)?.let { at to it } }.firstOrNull()

  private fun intervalPoints(metadata: PrecipitationAccumulation?): Map<Long, HourlyPoint> {
    if (metadata?.durationMillis != HOUR_MILLIS || metadata.anchor == AccumulationAnchor.UNKNOWN) return emptyMap()
    return points.values.mapNotNull { point ->
      val end = when (metadata.anchor) {
        AccumulationAnchor.PRECEDING -> point.timestampMillis.takeIf { it >= HOUR_MILLIS }
        AccumulationAnchor.FOLLOWING -> runCatching { Math.addExact(point.timestampMillis, HOUR_MILLIS) }.getOrNull()
        AccumulationAnchor.UNKNOWN -> null
      }
      end?.let { it to point }
    }.toMap()
  }

  companion object {
    const val HOUR_MILLIS = 3_600_000L

    fun validValue(variable: String, value: Double): Boolean = value.isFinite() && when (variable) {
      FusionVariables.PRECIPITATION, FusionVariables.WIND_SPEED, FusionVariables.WIND_GUST,
      FusionVariables.UV_INDEX, FusionVariables.VISIBILITY -> value >= 0.0
      FusionVariables.PRECIP_PROBABILITY, FusionVariables.CLOUD_COVER, FusionVariables.HUMIDITY -> value in 0.0..100.0
      FusionVariables.PRESSURE_MSL -> value > 0.0
      else -> true
    }
  }
}

/** The returned data must cover a useful product now; the existence of a Kotlin object is not data. */
internal fun ForecastBundle.usableAt(now: Long, latitude: Double = this.latitude, longitude: Double = this.longitude): Boolean {
  if (fetchedAtMillis < 0L || fetchedAtMillis > now || !validForecastPoint(this.latitude, this.longitude) ||
    !validForecastPoint(latitude, longitude) || haversineKm(this.latitude, this.longitude, latitude, longitude) > WeatherSnapshotRefresher.MAX_DISTANCE_KM) return false
  val horizon = ProviderRegistry.all.firstOrNull { it.id == providerId }?.horizonHours ?: 24
  val end = now + horizon * ForecastProducts.HOUR_MILLIS
  val products = ForecastProducts(this)
  if (products.timestamps.any { at -> at >= now - ForecastProducts.HOUR_MILLIS && at <= end &&
      (FusionVariables.all.any { products.valueAt(at, it) != null } ||
        products.pointAt(at)?.kind?.let { it != WeatherKind.UNKNOWN } == true ||
        products.pointAt(at)?.windDirectionDeg?.let { it.isFinite() && it in 0.0..360.0 } == true) }) return true
  current?.let { value ->
    if (value.timestampMillis in (now - 30 * 60_000L)..now &&
      value.downloadedAtMillis?.let { it in 0L..now && value.timestampMillis <= it } == true &&
      (value.temperatureC?.isFinite() == true || value.kind?.let { it != WeatherKind.UNKNOWN } == true ||
        value.precipitationMm != null && value.precipitationInterval != null)) return true
  }
  if (minutelyPrecipitation?.durationMillis == 900_000L && minutelyPrecipitation.anchor == AccumulationAnchor.PRECEDING &&
    now - fetchedAtMillis <= LocalPrecipitationEvaluator.DOWNLOAD_MAX_AGE) {
    if (minutely.groupBy { it.timestampMillis }.values.any { rows ->
        val row = rows.singleOrNull()
        row != null && row.timestampMillis in (now - LocalPrecipitationEvaluator.RECENT_MAX_AGE)..(now + 2 * ForecastProducts.HOUR_MILLIS) &&
          (row.precipitationMm?.let { it.isFinite() && it >= 0.0 } == true ||
            row.precipitationProbabilityPercent?.let { it.isFinite() && it in 0.0..100.0 } == true)
      }) return true
  }
  return false
}

internal fun validForecastPoint(latitude: Double, longitude: Double): Boolean =
  latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

/** Preserve every attempt, including a decoded-but-useless provider response, in diagnostics. */
internal fun ProviderFetch.validatedAt(now: Long, latitude: Double, longitude: Double): ProviderFetch = when {
  bundle == null -> this
  error != null -> copy(bundle = null)
  bundle.usableAt(now, latitude, longitude) -> this
  else -> copy(bundle = null, error = "nessun prodotto meteo utilizzabile per il punto e il momento richiesti")
}
