package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import java.time.*

/** Pure evaluation of original model products. No current, fused weather, radar or IO. */
class RoutineWindowEvaluator {
  fun nextOccurrences(
    windows: List<RoutineWindow>, evaluatedAtMillis: Long,
    zone: ResolvedWeatherZone, limit: Int = 2,
  ): List<RoutineOccurrence> {
    if (limit <= 0) return emptyList()
    val today = Instant.ofEpochMilli(evaluatedAtMillis).atZone(zone.zoneId).toLocalDate()
    return windows.distinctBy { it.id }.mapNotNull { rule ->
      // Yesterday catches overnight; two weeks also covers a skipped invalid DST occurrence.
      (-1L..14L).mapNotNull candidate@ { offset ->
        val date = today.plusDays(offset)
        if (date.dayOfWeek !in rule.daysOfWeek) return@candidate null
        val startWall = date.atTime(rule.start)
        val endWall = date.plusDays(if (rule.crossesMidnight) 1 else 0).atTime(rule.end)
        // java.time shifts gaps forward by their length and selects the earlier overlap offset.
        val start = startWall.atZone(zone.zoneId)
        val end = endWall.atZone(zone.zoneId)
        val from = start.toInstant().toEpochMilli(); val to = end.toInstant().toEpochMilli()
        if (to <= from || to <= evaluatedAtMillis) return@candidate null
        val transition = zone.zoneId.rules.nextTransition(start.toInstant().minusNanos(1))
        RoutineOccurrence(rule, WeatherInterval(from, to), zone,
          zone.zoneId.rules.getValidOffsets(startWall).size != 1 ||
            zone.zoneId.rules.getValidOffsets(endWall).size != 1 ||
            (transition != null && transition.instant.isBefore(end.toInstant())))
      }.minByOrNull { it.interval.startMillis }
    }.sortedWith(compareBy<RoutineOccurrence> { it.interval.startMillis }.thenBy { it.routine.id }).take(limit)
  }

  fun evaluate(
    windows: List<RoutineWindow>, place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?,
    evaluatedAtMillis: Long, deviceZone: ZoneId, limit: Int = 2,
  ): List<RoutineWindowAssessment> {
    val key = if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)
    val local = snapshot?.takeIf { coordinates != null && it.placeKey == key &&
      nearby(it.latitude, it.longitude, coordinates) }
    val bundle = local?.context?.takeIf { coordinates != null && nearby(it.latitude, it.longitude, coordinates) }
    val zone = WeatherZones.resolve(place.zoneId,
      WeatherZones.validId(local?.zoneId) ?: WeatherZones.validId(bundle?.zoneId), deviceZone)
    val baseLimits = mutableSetOf<WeatherEvidenceLimit>()
    if (coordinates == null) baseLimits += WeatherEvidenceLimit.MISSING_COORDINATES
    if (coordinates != null && snapshot != null && (local == null || (local.context != null && bundle == null)))
      baseLimits += WeatherEvidenceLimit.LOCATION_MISMATCH
    return nextOccurrences(windows, evaluatedAtMillis, zone, limit).map { occurrence ->
      assess(occurrence, evaluatedAtMillis, bundle, baseLimits)
    }
  }

  private data class Sample(val interval: WeatherInterval, val amount: Double?, val probability: Double?, val evidence: WeatherEvidence)

  private fun assess(o: RoutineOccurrence, now: Long, b: ForecastBundle?, base: Set<WeatherEvidenceLimit>): RoutineWindowAssessment {
    val interval = WeatherInterval(maxOf(o.interval.startMillis, now), o.interval.endMillis)
    val limits = base.toMutableSet()
    fun unavailable(evidence: List<WeatherEvidence> = emptyList()) = RoutineWindowAssessment(o, now,
      evidence = evidence, limits = limits + WeatherEvidenceLimit.PARTIAL_COVERAGE, assessedInterval = interval)
    if (b == null || b.providerId.isBlank()) return unavailable()
    val fresh = b.fetchedAtMillis <= now && now - b.fetchedAtMillis <= 3_600_000L
    if (!fresh) limits += WeatherEvidenceLimit.STALE
    limits += WeatherEvidenceLimit.MODEL_ONLY
    // Accumulation duration is retained in effectiveInterval; native model resolution is unknown.
    val sourceLimits = setOf(WeatherEvidenceLimit.MODEL_ONLY, WeatherEvidenceLimit.COARSE_TIME_RESOLUTION,
      WeatherEvidenceLimit.UNKNOWN_PROVENANCE) + if (fresh) emptySet() else setOf(WeatherEvidenceLimit.STALE)
    fun evidence(time: Long, range: WeatherInterval?, interpolated: Boolean? = null) = WeatherEvidence(
      b.providerId, WeatherEvidenceType.MODEL, range, b.fetchedAtMillis,
      limits = sourceLimits + if (interpolated != false) setOf(WeatherEvidenceLimit.POSSIBLY_INTERPOLATED) else emptySet(),
      sourceTimestampMillis = time)
    fun samples(rows: List<Triple<Long, Double?, Double?>>, metadata: PrecipitationAccumulation?, interpolated: Boolean?): List<Sample> {
      val duration = metadata?.durationMillis
      if (metadata == null || duration == null || metadata.anchor == AccumulationAnchor.UNKNOWN) {
        if (rows.isNotEmpty()) limits += WeatherEvidenceLimit.UNKNOWN_PROVENANCE
        return emptyList()
      }
      val candidates = rows.groupBy { it.first }.values.mapNotNull { group ->
        // Even identical duplicates are rejected, rather than summed or silently selected.
        val row = group.singleOrNull() ?: return@mapNotNull null
        val time = row.first
        val range = try {
          if (metadata.anchor == AccumulationAnchor.PRECEDING) WeatherInterval(Math.subtractExact(time, duration), time)
          else WeatherInterval(time, Math.addExact(time, duration))
        } catch (_: ArithmeticException) { return@mapNotNull null }
        if (range.startMillis < interval.startMillis || range.endMillis > interval.endMillis) return@mapNotNull null
        val amount = row.second?.takeIf { it.isFinite() && it >= 0 }
        val probability = row.third?.takeIf { it.isFinite() && it in 0.0..100.0 }
        if (amount == null && probability == null) return@mapNotNull null
        Sample(range, amount, probability, evidence(time, range, interpolated).copy(
          precipitationState = amount?.let { if (it > 0) PrecipitationState.WET else PrecipitationState.DRY }))
      }
      // Irregular overlapping accumulations cannot be summed or prove complete coverage.
      return candidates.filter { a -> candidates.none { c -> c !== a && overlaps(a.interval, c.interval) } }
    }
    val minute = samples(b.minutely.map { Triple(it.timestampMillis, it.precipitationMm, it.precipitationProbabilityPercent) },
      b.minutelyPrecipitation, b.minutelyPossiblyInterpolated)
    val hour = samples(b.hourly.map { Triple(it.timestampMillis, it.precipitationMm, it.precipitationProbabilityPercent) },
      b.hourlyPrecipitation, null)
    fun coverage(s: List<Sample>) = s.filter { it.amount != null }.sumOf { it.interval.endMillis - it.interval.startMillis }
    // Select one product with greatest known coverage; tie prefers the sub-hourly product.
    val selected = if (coverage(minute) >= coverage(hour)) minute else hour
    val all = minute + hour
    val conflicting = minute.any { m -> hour.any { h -> overlaps(m.interval, h.interval) &&
      m.amount != null && h.amount != null && ((m.amount > 0) != (h.amount > 0)) } }
    if (conflicting) limits += WeatherEvidenceLimit.CONFLICTING_SIGNALS
    val temperatures = b.hourly.groupBy { it.timestampMillis }.values.mapNotNull { group ->
      group.singleOrNull()?.takeIf { it.timestampMillis >= interval.startMillis && it.timestampMillis < interval.endMillis }
        ?.takeIf { it.temperatureC?.isFinite() == true }
    }
    val provenance = (all.map { it.evidence } + temperatures.map { evidence(it.timestampMillis, null) }).distinct()
    provenance.forEach { limits += it.limits }
    if (!fresh) return unavailable(provenance)
    val covered = coverage(selected)
    val complete = covered == interval.endMillis - interval.startMillis
    if (!complete) limits += WeatherEvidenceLimit.PARTIAL_COVERAGE
    val wet = all.any { it.amount?.let { amount -> amount > 0.0 } == true }
    val state = when {
      conflicting -> PrecipitationState.UNCERTAIN
      wet -> PrecipitationState.WET
      complete -> PrecipitationState.DRY
      covered > 0 -> PrecipitationState.UNCERTAIN
      else -> PrecipitationState.UNAVAILABLE
    }
    val sum = selected.mapNotNull { it.amount }.sum().takeIf { complete && !conflicting && it.isFinite() }
    return RoutineWindowAssessment(o, now, state, covered, sum,
      all.mapNotNull { it.probability }.maxOrNull(), temperatures.mapNotNull { it.temperatureC }.minOrNull(),
      temperatures.mapNotNull { it.temperatureC }.maxOrNull(), provenance, limits, interval)
  }

  private fun overlaps(a: WeatherInterval, b: WeatherInterval) = a.startMillis < b.endMillis && b.startMillis < a.endMillis
  private fun nearby(lat: Double, lon: Double, point: GeoPoint) = lat.isFinite() && lon.isFinite() &&
    lat in -90.0..90.0 && lon in -180.0..180.0 && haversineKm(lat, lon, point.latitude, point.longitude) <= 2.0
}
