# Shared weather contract - F1

F1 introduces foundations for C1.contracts and C7.compatibility. It does not establish
C2 accuracy or C6 comparison. No precipitation evaluator, routine evaluator, UI redesign,
notification, new source, training or barometric policy change is included.

## Pure JVM API (`core-model`, package `dev.pampa.fluidweather.core.model`)

Constructor parameters below are in declaration order. `?` means unknown is allowed;
`= null`, `= emptyList()` and `= emptySet()` are the actual defaults.

```kotlin
data class GeoPoint(val latitude: Double, val longitude: Double)
data class WeatherInterval(val startMillis: Long, val endMillis: Long)
enum class PrecipitationState { WET, DRY, UNCERTAIN, UNAVAILABLE }
enum class WeatherEvidenceType { MODEL, RADAR_ESTIMATE, MEASUREMENT }
enum class WeatherEvidenceLimit {
  MODEL_ONLY, COARSE_TIME_RESOLUTION, COARSE_SPATIAL_RESOLUTION,
  STALE, PARTIAL_COVERAGE, CONFLICTING_SIGNALS, UNKNOWN_PROVENANCE
}
data class WeatherEvidence(
  val sourceId: String,
  val type: WeatherEvidenceType,
  val effectiveInterval: WeatherInterval? = null,
  val downloadedAtMillis: Long? = null,
  val temporalResolutionMillis: Long? = null,
  val spatialResolutionMeters: Double? = null,
  val limits: Set<WeatherEvidenceLimit> = emptySet(),
  val precipitationState: PrecipitationState? = null
)
data class LocalPrecipitationAssessment(
  val state: PrecipitationState,
  val evaluatedAtMillis: Long,
  val place: Place? = null,
  val coordinates: GeoPoint? = null,
  val evidence: List<WeatherEvidence> = emptyList(),
  val horizon: WeatherInterval? = null,
  val arrivalAtMillis: Long? = null,
  val endAtMillis: Long? = null,
  val limits: Set<WeatherEvidenceLimit> = emptySet()
)
data class CurrentWeatherSummary(
  val kind: WeatherKind? = null,
  val temperatureC: Double? = null,
  val evidence: List<WeatherEvidence> = emptyList()
)
data class WeatherBrief(
  val precipitation: LocalPrecipitationAssessment,
  val current: CurrentWeatherSummary? = null
)
data class RoutineWindow(
  val id: String,
  val name: String,
  val daysOfWeek: Set<DayOfWeek>,
  val start: LocalTime,
  val end: LocalTime
) { val crossesMidnight: Boolean }
enum class WeatherZoneSource { PLACE, SNAPSHOT, DEVICE_FALLBACK }
data class ResolvedWeatherZone(val zoneId: ZoneId, val source: WeatherZoneSource)
object WeatherZones {
  fun validId(value: String?): String?
  fun resolve(placeZoneId: String?, snapshotZoneId: String?, deviceZone: ZoneId): ResolvedWeatherZone
}
```

`DayOfWeek`, `LocalTime`, `ZoneId` are from `java.time`. All types have no Android,
Room, weather-module, UI, AI or radar dependency. No new module dependencies were added.

`GeoPoint` rejects nonfinite or out-of-range coordinates. Missing coordinates use null;
existing `Place.gps()` retains its legacy NaN placeholder for compatibility: adapt that to
null, never to `(0, 0)`. `WeatherInterval` requires end > start and means `[start, end)`.
All `*Millis` instants are UTC epoch milliseconds; durations/resolutions are milliseconds.
No local-time or UTC-offset adjustment is applied to epoch values.

Evidence IDs are stable source/product identifiers, never localized labels. Evidence can
have unknown effective interval, download time or resolution: leave null and explain a
known limitation rather than inventing metadata. Resolutions must be positive (and spatial
resolution finite). `MODEL` remains `MODEL` after its interval passes; provider analyses
and historical model values are not physical measurements. `RADAR_ESTIMATE` is an estimate,
not a ground gauge. Passing time or downloading a cache does not change provenance.

Any result other than UNAVAILABLE requires evidence. UNAVAILABLE may retain stale/unusable
evidence for explanation. It cannot carry arrival/end estimates. Arrival/end are optional
absolute estimates constrained to the horizon (arrival < horizon end, end <= horizon end,
end >= arrival); their null value does not mean "no rain". No confidence number is manufactured.
`CurrentWeatherSummary` is optional; known kind/temperature requires evidence, and temperature must be finite.
Current weather can also be model output; consumers must retain its evidence.

Routine days refer to the start day. Friday 23:00-01:00 ends on Saturday; equal start/end
is invalid, not an implicit whole day. ID/name must be nonblank and days nonempty. There
is no routine store/editor or DST wall-time evaluator in F1. F4 must decide gap/overlap
handling explicitly rather than assuming every day lasts 24 hours.

## F2 integration guidance

Build one `WeatherBrief` from the shared weather layer and let home/widget/AI consume it.
F1 provides the vocabulary only: no producer of assessments is wired into current screens.
Localize enums in presentation resources; do not put Italian prose into these models.
Do not infer a physical observation from `ForecastBundle.rainNowMm`, past hourly values,
weather codes, fused forecasts or a timestamp being earlier than now. Those retain MODEL
provenance. Supply the actual source interval for each evidence item. For Open-Meteo
precipitation the timestamp marks the END of the preceding accumulation interval; hourly
and 15-minute products have different durations. This document does not change the
numerical behavior of `rainSoonMm` or `rainLastHourMm`; corrections belong to F2.

No evidence / location unavailable:

```kotlin
val unavailable = WeatherBrief(LocalPrecipitationAssessment(
  state = PrecipitationState.UNAVAILABLE,
  evaluatedAtMillis = nowMillis,
)) // place, coordinates, horizon, arrival/end and current weather all unknown
```

Illustrative conflict (the gauge ID is an example, not a newly connected source):

```kotlin
val interval = WeatherInterval(nowMillis - 900_000, nowMillis)
val conflict = WeatherBrief(LocalPrecipitationAssessment(
  state = PrecipitationState.UNCERTAIN,
  evaluatedAtMillis = nowMillis,
  coordinates = GeoPoint(43.83, 11.2),
  evidence = listOf(
    WeatherEvidence("open-meteo", WeatherEvidenceType.MODEL,
      effectiveInterval = interval, downloadedAtMillis = originalModelDownload,
      temporalResolutionMillis = 900_000,
      limits = setOf(WeatherEvidenceLimit.MODEL_ONLY),
      precipitationState = PrecipitationState.WET),
    WeatherEvidence("example-gauge", WeatherEvidenceType.MEASUREMENT,
      effectiveInterval = interval, downloadedAtMillis = originalGaugeDownload,
      precipitationState = PrecipitationState.DRY)
  ),
  limits = setOf(WeatherEvidenceLimit.CONFLICTING_SIGNALS)
)) // No arrival time, no fabricated confidence, and both source signals survive.
```

These are contract examples, not classification rules or proof of source availability.
F2 must define freshness, coverage, conflict policy and supported precision using existing
sources. Do not recompute contradictory independent summaries in each consumer.

## Place timezone, persistence and compatibility

The following existing constructors gain a final optional `val zoneId: String? = null`:
`Place`, `ForecastBundle`, `WeatherSnapshot`, and `SavedLocationEntity`. Source callers and
legacy persisted snapshots retain their defaults. `WeatherZones.validId` accepts installed
IANA/tzdb identifiers (including UTC), rejects invalid IDs and arbitrary fixed offsets;
no silent coercion to UTC. `resolve` chooses valid place, then valid snapshot, then the
caller-supplied device zone. DEVICE_FALLBACK is explicit and must never be persisted as
location metadata. Resolved IANA rules handle DST offsets of absolute instants.

Geocoding reads `timezone`. Open-Meteo asks `timezone=auto&timeformat=unixtime`, reads
`timezone` and keeps epoch seconds * 1000 without adding/subtracting `utc_offset_seconds`.
`WeatherSnapshot.from` takes the first valid zone from this round's provider bundles.
No extra request or new service is introduced. Snapshot codec stays version 1 and stores
optional `zoneId` on root and context; old snapshots omit it and decode to null, invalid
zones also decode to null. Incomplete context missing coordinates or download time is
rejected as context=null, preserving the rest of the snapshot rather than inventing zeros.

Room version is 8. `MIGRATION_7_8` adds only nullable TEXT `saved_locations.zoneId`;
`ALL_MIGRATIONS` includes 6->7 and 7->8. Existing IDs/order/data remain unchanged. Schema 8
was generated by the parent build; no handwritten schema JSON was created.

`SavedLocationsDao.backfillZoneId(id: Long, zoneId: String): Int` updates only an existing
row with NULL zone. `SavedLocationsRepository.backfillZoneId(placeId: Long, zoneId: String?):
Boolean` validates the zone, skips GPS and returns true only when a row changed. Saving
new geocoded places also persists the validated zone; reading maps it back to Place.
Storage failure during optional backfill returns false so forecast loading can continue;
coroutine cancellation is rethrown so an obsolete place load still stops.
HomeData invokes backfill for a useful saved-place cached/network/background snapshot;
it does not replace rows, duplicate locations, change sortOrder or alter layout.

`WeatherSnapshot.fetchedAtMillis` is the round timestamp, not a universal source download
time. `ForecastBundle.fetchedAtMillis` remains its original network-download timestamp,
including when served from URL cache. Evidence must copy that original timestamp, never
the assessment time. `contextCarriedFromMillis` and the carried bundle's download timestamp
are preserved independently. An old context does not become fresh because a new round ran.

## Parent verification (not run by implementation agent)

```powershell
.\gradlew.bat :core-model:test :core-weather:testDebugUnitTest :core-data:testDebugUnitTest :feature-home:compileDebugKotlin :core-data:assembleDebugAndroidTest
.\gradlew.bat :core-data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pampa.fluidweather.core.data.MigrationTest
```

The parent owns builds and device validation. Ensure KSP exported schema 8 exists before
instrumentation tests. Added/extended tests cover unknowns, evidence conflicts and model
provenance, routine overnight invariants, invalid zones, DST gap/overlap, geocoder metadata,
Open-Meteo auto/absolute times, snapshot compatibility/original timestamps, migration chains
and repository metadata backfill preserving ordering.
