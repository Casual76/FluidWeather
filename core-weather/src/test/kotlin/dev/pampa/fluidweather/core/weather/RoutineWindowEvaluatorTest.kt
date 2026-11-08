package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RoutineWindowEvaluatorTest {
  private val evaluator = RoutineWindowEvaluator()
  private val utc = ResolvedWeatherZone(ZoneId.of("UTC"), WeatherZoneSource.PLACE)
  private val place = Place(42, "Here", null, 41.9, 12.5, "UTC")
  private val point = GeoPoint(41.9, 12.5)
  private val noon = time("2026-11-02T12:00:00Z") // Monday
  private fun time(s: String) = Instant.parse(s).toEpochMilli()
  private fun rule(id: String = "r", start: String = "12:00", end: String = "13:00",
    days: Set<DayOfWeek> = DayOfWeek.values().toSet()) = RoutineWindow(id, id, days, LocalTime.parse(start), LocalTime.parse(end))
  private fun bundle(hours: List<HourlyPoint> = emptyList(), minutes: List<MinutePoint> = emptyList()) = ForecastBundle(
    "provider", noon, point.latitude, point.longitude, hours, minutes, "UTC",
    hourlyPrecipitation = PrecipitationAccumulation(3_600_000L, AccumulationAnchor.FOLLOWING),
    minutelyPrecipitation = PrecipitationAccumulation(900_000L, AccumulationAnchor.PRECEDING), minutelyPossiblyInterpolated = false)
  private fun snapshot(b: ForecastBundle) = WeatherSnapshot("place-42", point.latitude, point.longitude, noon,
    emptyList(), FusedForecast(emptyList(), emptyMap()), b, zoneId = "UTC")
  private fun assess(b: ForecastBundle?, now: Long = noon, r: RoutineWindow = rule(), s: WeatherSnapshot? = b?.let(::snapshot)) =
    evaluator.evaluate(listOf(r), place, point, s, now, ZoneId.of("Asia/Tokyo")).single()

  @Test fun `one per rule ongoing overnight uses prior start day and stable order`() {
    val monday = setOf(DayOfWeek.MONDAY)
    val now = time("2026-11-03T00:30:00Z")
    val found = evaluator.nextOccurrences(listOf(rule("b", "23:00", "01:00", monday), rule("a", "23:00", "01:00", monday)), now, utc, 20)
    assertEquals(listOf("a", "b"), found.map { it.routine.id })
    assertEquals(time("2026-11-02T23:00:00Z"), found.first().interval.startMillis)
    assertEquals(time("2026-11-03T01:00:00Z"), found.first().interval.endMillis)
    assertEquals(1, evaluator.nextOccurrences(listOf(rule()), now, utc, 100).size)
    assertTrue(evaluator.nextOccurrences(emptyList(), now, utc).isEmpty())
  }

  @Test fun `place date wins over device and foreign timezone cannot be borrowed`() {
    val now = time("2026-11-02T23:30:00Z")
    val r = rule(start = "01:00", end = "02:00", days = setOf(DayOfWeek.TUESDAY))
    val local = evaluator.evaluate(listOf(r), place.copy(zoneId = "Asia/Tokyo"), point, null, now, ZoneId.of("America/Los_Angeles")).single()
    // Tokyo is already Tuesday morning, so this Tuesday's 01:00 has ended: next Tuesday.
    assertEquals(time("2026-11-09T16:00:00Z"), local.occurrence.interval.startMillis)
    val foreign = snapshot(bundle()).copy(placeKey = "place-99", zoneId = "Pacific/Auckland")
    val fallback = evaluator.evaluate(listOf(r), place.copy(zoneId = null), point, foreign, now, ZoneId.of("UTC")).single()
    assertEquals(WeatherZoneSource.DEVICE_FALLBACK, fallback.occurrence.zone.source)
    assertEquals(time("2026-11-03T01:00:00Z"), fallback.occurrence.interval.startMillis)
    assertTrue(WeatherEvidenceLimit.LOCATION_MISMATCH in fallback.limits)
  }

  @Test fun `DST gap shifts endpoints and collapsed or reversed occurrence is skipped`() {
    val zone = ResolvedWeatherZone(ZoneId.of("Europe/Rome"), WeatherZoneSource.PLACE)
    val now = time("2026-03-29T00:00:00Z")
    val shifted = evaluator.nextOccurrences(listOf(rule(start = "02:15", end = "04:00")), now, zone).single()
    assertEquals(time("2026-03-29T01:15:00Z"), shifted.interval.startMillis)
    assertTrue(shifted.adjustedForDst)
    for (end in listOf("03:00", "03:15")) {
      val skipped = evaluator.nextOccurrences(listOf(rule(start = "02:15", end = end, days = setOf(DayOfWeek.SUNDAY))), now, zone).single()
      assertEquals(LocalDate.of(2026, 4, 5), Instant.ofEpochMilli(skipped.interval.startMillis).atZone(zone.zoneId).toLocalDate())
      assertTrue(skipped.interval.endMillis > skipped.interval.startMillis)
    }
  }

  @Test fun `DST overlap chooses earlier offset for both boundaries exactly once`() {
    val zone = ResolvedWeatherZone(ZoneId.of("Europe/Rome"), WeatherZoneSource.PLACE)
    val found = evaluator.nextOccurrences(listOf(rule(start = "02:15", end = "02:45")), time("2026-10-25T00:20:00Z"), zone, 100)
    assertEquals(1, found.size)
    assertEquals(time("2026-10-25T00:15:00Z"), found.single().interval.startMillis)
    assertEquals(time("2026-10-25T00:45:00Z"), found.single().interval.endMillis)
    assertTrue(found.single().adjustedForDst)
  }

  @Test fun `transition within otherwise valid endpoints is flagged and midnight expires previous day`() {
    val zone = ResolvedWeatherZone(ZoneId.of("Europe/Rome"), WeatherZoneSource.PLACE)
    val found = evaluator.nextOccurrences(listOf(rule(start = "01:00", end = "04:00")), time("2026-03-29T00:30:00Z"), zone).single()
    assertTrue(found.adjustedForDst)
    assertEquals(2 * 3_600_000L, found.interval.endMillis - found.interval.startMillis)
    val before = evaluator.nextOccurrences(listOf(rule(start = "23:00", end = "00:00")), time("2026-11-02T23:59:59Z"), utc).single()
    val after = evaluator.nextOccurrences(listOf(rule(start = "23:00", end = "00:00")), time("2026-11-03T00:00:00Z"), utc).single()
    assertEquals(time("2026-11-02T23:00:00Z"), before.interval.startMillis)
    assertEquals(time("2026-11-03T23:00:00Z"), after.interval.startMillis)
  }

  @Test fun `complete explicit coverage sums once preserves source and dry is complete only`() {
    val quarters = (1..4).map { MinutePoint(noon + it * 900_000L, 0.5, 70.0) }
    val b = bundle(listOf(HourlyPoint(noon, temperatureC = 10.0, precipitationMm = 2.0)), quarters)
    val result = assess(b)
    assertTrue(result.hasCompleteCoverage)
    assertEquals(2.0, result.rainAmountMm!!, 0.0)
    assertEquals(3_600_000L, result.coveredMillis)
    assertEquals(PrecipitationState.WET, result.precipitation)
    assertEquals(10.0, result.minTemperatureC!!, 0.0)
    assertTrue(result.evidence.all { it.type == WeatherEvidenceType.MODEL && it.sourceId == "provider" && it.downloadedAtMillis == noon })
    assertTrue(result.evidence.any { it.effectiveInterval == WeatherInterval(noon, noon + 900_000L) })
    assertTrue(result.evidence.all { it.temporalResolutionMillis == null })
    assertEquals(PrecipitationState.DRY, assess(bundle(minutes = quarters.map { it.copy(precipitationMm = 0.0) })).precipitation)
    assertEquals(PrecipitationState.UNCERTAIN, assess(bundle(minutes = quarters.take(1).map { it.copy(precipitationMm = 0.0) })).precipitation)
  }

  @Test fun `ongoing excludes past rain and boundary fragments without prorating`() {
    val b = bundle(minutes = (1..4).map { MinutePoint(noon + it * 900_000L, if (it <= 2) 8.0 else 0.0) })
    val result = assess(b, noon + 1_800_000L)
    assertEquals(noon, result.occurrence.interval.startMillis)
    assertEquals(noon + 1_800_000L, result.assessedInterval.startMillis)
    assertEquals(0.0, result.rainAmountMm!!, 0.0)
    assertEquals(PrecipitationState.DRY, result.precipitation)
    val fragment = assess(b, noon + 1_800_001L)
    assertNull(fragment.rainAmountMm)
    assertEquals(900_000L, fragment.coveredMillis)
    assertEquals(PrecipitationState.UNCERTAIN, fragment.precipitation)
  }

  @Test fun `duplicates missing unknown duration and no samples never manufacture rain totals`() {
    assertEquals(PrecipitationState.UNAVAILABLE, assess(null).precipitation)
    val row = MinutePoint(noon + 900_000L, 2.0)
    assertEquals(PrecipitationState.UNAVAILABLE, assess(bundle(minutes = listOf(row, row))).precipitation)
    val unknown = assess(bundle(minutes = listOf(row)).copy(minutelyPrecipitation = null))
    assertEquals(PrecipitationState.UNAVAILABLE, unknown.precipitation)
    assertTrue(WeatherEvidenceLimit.UNKNOWN_PROVENANCE in unknown.limits)
    val partial = assess(bundle(minutes = listOf(row)))
    assertEquals(PrecipitationState.WET, partial.precipitation)
    assertNull(partial.rainAmountMm)
    assertTrue(WeatherEvidenceLimit.PARTIAL_COVERAGE in partial.limits)
    assertEquals(PrecipitationState.UNAVAILABLE, assess(bundle(minutes = listOf(row.copy(precipitationMm = null)))).precipitation)
  }

  @Test fun `stale outputs retain provenance but suppress known fields and matching is mandatory`() {
    val b = bundle(listOf(HourlyPoint(noon, temperatureC = 8.0, precipitationMm = 2.0)))
    val stale = assess(b.copy(fetchedAtMillis = noon - 3_600_001L))
    assertEquals(PrecipitationState.UNAVAILABLE, stale.precipitation)
    assertNull(stale.minTemperatureC); assertNull(stale.rainAmountMm)
    assertTrue(WeatherEvidenceLimit.STALE in stale.limits)
    assertEquals(noon - 3_600_001L, stale.evidence.first().downloadedAtMillis)
    assertEquals(PrecipitationState.WET, assess(b.copy(fetchedAtMillis = noon - 3_600_000L)).precipitation)
    val mismatch = assess(b, s = snapshot(b.copy(latitude = 0.0)))
    assertTrue(WeatherEvidenceLimit.LOCATION_MISMATCH in mismatch.limits)
    assertTrue(mismatch.evidence.isEmpty())
    val distant = assess(b, s = snapshot(b).copy(latitude = 42.0))
    assertEquals(PrecipitationState.UNAVAILABLE, distant.precipitation)
  }

  @Test fun `current and fused alone do not substitute original forecast intervals`() {
    val b = bundle().copy(current = ProviderCurrent(noon, temperatureC = 25.0, precipitationMm = 3.0))
    val result = assess(b)
    assertNull(result.minTemperatureC); assertNull(result.rainAmountMm)
    assertEquals(PrecipitationState.UNAVAILABLE, result.precipitation)
    val fused = snapshot(b).copy(context = null, fused = FusedForecast(listOf(FusedHour(noon, emptyMap(), WeatherKind.RAIN)), emptyMap()))
    assertEquals(PrecipitationState.UNAVAILABLE, assess(null, s = fused).precipitation)
  }

  @Test fun `unknown hourly duration preserves only actual temperatures and matching zone metadata`() {
    val b = bundle(listOf(HourlyPoint(noon, temperatureC = 12.0, precipitationMm = 8.0))).copy(hourlyPrecipitation = null)
    val result = assess(b)
    assertEquals(PrecipitationState.UNAVAILABLE, result.precipitation)
    assertEquals(12.0, result.minTemperatureC!!, 0.0)
    assertNull(result.rainAmountMm)
    val matching = evaluator.evaluate(listOf(rule()), place.copy(zoneId = null), point,
      snapshot(b.copy(zoneId = "Europe/Rome")).copy(zoneId = null), noon, ZoneId.of("UTC")).single()
    assertEquals(WeatherZoneSource.SNAPSHOT, matching.occurrence.zone.source)
    assertEquals(ZoneId.of("Europe/Rome"), matching.occurrence.zone.zoneId)
    val missing = evaluator.evaluate(listOf(rule()), place.copy(zoneId = null), null, snapshot(b), noon, ZoneId.of("Asia/Tokyo")).single()
    assertTrue(WeatherEvidenceLimit.MISSING_COORDINATES in missing.limits)
    assertEquals(WeatherZoneSource.DEVICE_FALLBACK, missing.occurrence.zone.source)
  }

  @Test fun `conflicting products never yield a dry total`() {
    val b = bundle(listOf(HourlyPoint(noon, precipitationMm = 0.0)), listOf(MinutePoint(noon + 900_000L, 1.0)))
    val result = assess(b)
    assertEquals(PrecipitationState.UNCERTAIN, result.precipitation)
    assertNull(result.rainAmountMm)
    assertTrue(WeatherEvidenceLimit.CONFLICTING_SIGNALS in result.limits)
  }

  @Test fun `invalid amounts and overlapping accumulations cannot establish coverage while probability stays separate`() {
    val probabilityOnly = assess(bundle(minutes = listOf(MinutePoint(noon + 900_000L, Double.NaN, 80.0))))
    assertEquals(PrecipitationState.UNAVAILABLE, probabilityOnly.precipitation)
    assertEquals(0L, probabilityOnly.coveredMillis)
    assertEquals(80.0, probabilityOnly.maxProbabilityPercent!!, 0.0)
    assertNull(probabilityOnly.rainAmountMm)
    val overlap = assess(bundle(minutes = listOf(MinutePoint(noon + 900_000L, 2.0), MinutePoint(noon + 1_200_000L, 2.0))))
    assertEquals(PrecipitationState.UNAVAILABLE, overlap.precipitation)
    assertEquals(0L, overlap.coveredMillis)
    val outside = assess(bundle(listOf(HourlyPoint(noon - 1, temperatureC = -90.0), HourlyPoint(noon + 3_600_000L, temperatureC = 90.0))))
    assertNull(outside.minTemperatureC); assertNull(outside.maxTemperatureC)
  }
}
