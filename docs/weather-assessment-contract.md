# F2 C - Shared local assessment contract

Implementation handoff, 2026-11-07. Tests are written, **not executed**. Parent owns compilation,
serialized Gradle checks and two independent reviews. No C1/C7 self-rating, C2 field accuracy
or C6 performance claim is made.

## Exact producer API

Package `dev.pampa.fluidweather.core.weather`:

```kotlin
class LocalPrecipitationEvaluator {
  fun evaluate(
    place: Place,
    coordinates: GeoPoint?,
    snapshot: WeatherSnapshot?,
    radarSample: RadarSample?,
    evaluatedAtMillis: Long,
  ): WeatherBrief
  companion object {
    fun revision(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?, radar: RadarSample? = null): String
  }
}
class WeatherBriefService(
  scope: CoroutineScope,
  radarLoader: suspend (Double, Double) -> RadarSample,
  clock: () -> Long = System::currentTimeMillis,
  evaluator: LocalPrecipitationEvaluator = LocalPrecipitationEvaluator(),
  loadTimeoutMillis: Long = 2_500L,
  inputTtlMillis: Long = 60_000L,
  maxEntries: Int = 16,
) {
  suspend fun brief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief
  suspend fun clear(): Unit
}
```

`AppGraph.weatherBriefService` is the single application instance, after the existing
`weatherSnapshotStore`, using `applicationScope` and the existing singleton `radarSampler`.
No second store, provider fetchAll, model call, disk cache or sensor dependency is introduced.
`wipeAllData` clears the service inside the existing wipe guard, before data deletion.
Existing AI/nowcast adapters, barometric contribution, trained model and AnalogPolicy.OFF remain
as they were; they do not yet consume this new result. No new engine dependency is needed.

## Additive model API

In `dev.pampa.fluidweather.core.model`, trailing defaults preserve existing constructors:

```kotlin
// WeatherEvidence:
val sourceTimestampMillis: Long? = null
// LocalPrecipitationAssessment:
val radarAvailability: RadarAvailability = RadarAvailability.UNKNOWN
val transitionResolutionMillis: Long? = null
// WeatherBrief:
val timeline: List<PrecipitationPeriod> = emptyList()
val rainLastHour: RainAccumulation? = null
val revision: String = ""

enum class RadarAvailability { AVAILABLE, OUTSIDE, UNKNOWN, UNAVAILABLE }
enum class PrecipitationPeriodRole { RECENT_ACCUMULATION, FORECAST, UNKNOWN }
data class PrecipitationPeriod(
  val interval: WeatherInterval,
  val state: PrecipitationState = PrecipitationState.UNAVAILABLE,
  val amountMm: Double? = null,
  val probabilityPercent: Double? = null,
  val evidence: List<WeatherEvidence> = emptyList(),
  val role: PrecipitationPeriodRole = PrecipitationPeriodRole.UNKNOWN,
)
```

Additional `WeatherEvidenceLimit` entries: POSSIBLY_INTERPOLATED, RADAR_GROUND_UNCERTAINTY,
TRANSITION_EXPIRED, LOCATION_MISMATCH, MISSING_COORDINATES, FUTURE_DIVERGENCE,
RADAR_UNAVAILABLE, RADAR_SUPPORT_UNKNOWN. No prose/localization enters the model.
`RadarEvaluation` only adds the frame generation timestamp to its evidence. Its physical
`effectiveInterval` remains null; radar policy is unchanged.

## Present, recent accumulation and future

The point is explicit. A caller resolves GPS first, validates finite/range values and constructs
`GeoPoint`, or passes null. `Place.gps()` NaNs are never converted to zero coordinates. A null
point immediately yields UNAVAILABLE and skips loading radar. Real (0,0) remains a valid point.
Snapshot placeKey must match gps/place-ID, and both snapshot coordinates and context coordinates
must be within 2 km of the requested point. This is a conservative reuse boundary, not a claim
about model spatial resolution. Wrong-place model data is discarded independently of radar.
No snapshot and no radar produces UNAVAILABLE. Future forecast alone cannot establish present rain.

Original context download age must be <=60 minutes. Current has its own original download,
also <=60 minutes; null provenance is not borrowed from bundle/round. Current valid time must
be <= its original download and <= evaluation time. Temperature and known weather kind expire
30 minutes after valid time; their evidence is instantaneous (null effective interval plus
sourceTimestampMillis). UNKNOWN kind never proves dry. Precipitation uses its preceding interval,
only when duration <=15 minutes and closure <=20 minutes ago. Current precipitation is preferred;
otherwise the latest unique closed quarter may contribute, requiring explicit 15-minute PRECEDING
metadata, valid amount, original download age <=60 minutes and closure age <=20 minutes.
The quarter endpoint must already have been reached at original download: downloaded forecasts
never become present evidence merely through clock passage. The existing rainNow helper alone
cannot establish that distinction. Model remains MODEL throughout.

The 20-minute recent limit bounds extrapolation of an ended accumulation; it does not turn it
into instantaneous measurement. The 60-minute download limit avoids reusing old forecasts just
because their valid times are close. These are explicit conservative product policies, not
validated field accuracy. Future downloads/valid times are rejected for model use. The radar
adapter retains its separately specified 60-second future-frame tolerance and 20-minute cutoff.
Neither new snapshot fetchedAt nor contextCarriedFrom can refresh the original source download.
No hourly fused proxy, weather-code dry proxy, 3h amount, barometer or history is used here.
Parent integration preserves an explicitly wet current weather code as an instantaneous MODEL
signal within the same current freshness gates. It can establish a wet model signal when the
amount is missing; a preceding zero accumulation then conflicts explicitly with that current
wet signal. Non-wet or UNKNOWN codes never establish dry. This avoids a rain current condition
silently accompanying a dry assessment from an earlier accumulation interval.

All model evidence conservatively declares 60-minute temporal resolution: the available metadata
does not establish native quarter-hour precision. possiblyInterpolated true/null keeps the
POSSIBLY_INTERPOLATED limit; null precision metadata additionally keeps UNKNOWN_PROVENANCE.
Cadence alone is never proof of native resolution. No model ETA or heuristic accuracy percentage
is produced. rainLastHour is a separate complete four-quarter accumulation with its actual interval,
not an intensity/rate; it obeys the same download/closure gates.

## Radar and combination

Every evaluation calls `RadarSample.evaluateAt(now)`. Only usable source-local current signals
participate; radar >=10 dBZ is wet, below is no-echo/dry source signal. Evidence always retains
RADAR_ESTIMATE and RADAR_GROUND_UNCERTAINTY, including no echo. This is not certainty of dry ground.
Radar availability is independent: AVAILABLE for usable, OUTSIDE for known geographical outside,
UNKNOWN for unknown support, UNAVAILABLE for supported but unusable. Nonavailable input also carries
RADAR_UNAVAILABLE/PARTIAL_COVERAGE, and unknown support carries RADAR_SUPPORT_UNKNOWN. Network failure
with unknown support is thus explicit without claiming a geographic outside area. Detailed transport
error text is not public model prose. Stale evidence from the radar adapter remains for explanation,
with null precipitationState, and cannot vote dry or wet.

Valid model and radar disagreeing => UNCERTAIN + CONFLICTING_SIGNALS, preserving both. Valid model
without usable radar can stand alone with MODEL_ONLY. Radar without model can stand alone with its
estimate limits. Neither valid => UNAVAILABLE. An expired radar transition is exceptional: the old
frame signal cannot assert current dry/wet based on that passed trajectory hypothesis; the result
is UNCERTAIN + TRANSITION_EXPIRED, with no transition ETA, even if a model signal is present.
This persists when a short trajectory horizon ends before the 20-minute frame cutoff; the
end of that horizon does not restore a previously rejected frame wet/dry assertion.

Only absolute radar ETA surviving existing quality gates is admitted, strictly after evaluation,
inside the radar horizon and the next-hour brief horizon. Transition resolution remains 10 minutes.
Null arrival/end means unknown, never no rain during the horizon. Any present conflict suppresses
both transitions. A full model wet forecast period starting before a claimed first radar arrival,
or ending after a claimed cessation, suppresses both ETAs and marks UNCERTAIN/FUTURE_DIVERGENCE/
CONFLICTING_SIGNALS. This intentionally includes possible recurrence after cessation, rather than
silently promising a dry remainder. No averaging of future disagreement.

## Timeline consumption

Timeline covers the next hour when fresh explicit quarter metadata is available. Real complete
preceding-quarter intervals are used, with FORECAST role, MODEL evidence and valid finite/nonnegative
amounts. Probabilities must be finite 0..100, and are never manufactured. Duplicates, missing endpoints
and invalid/null amounts yield UNAVAILABLE periods; gaps and partial boundary fragments have no amount.
No partial-quarter prorating, hourly interpolation or dBZ accumulation conversion occurs. Empty timeline
means unavailable product, not a dry hour. Recent model accumulation is in precipitation evidence and
rainLastHour, distinct from the future timeline; RECENT_ACCUMULATION is reserved for later consumers.

F3 should consume graph service output, localize states/limits, present current/recent/future separately,
and retain radar generation/download ages and model precision limitations. F5 should provide its already
available snapshot and the same explicit resolved point, consuming this same result instead of deriving
another rain verdict. Its presentation cache must additionally key language, units and routines. F6
should use this service for widget input and own its future disk cache; no F2 disk brief is written.
Consumers must reconsume for time-sensitive values, even when revision is unchanged: revision is input
identity, not a freshness certificate. They must not cache/localize ETA as a perpetually valid instant.

## Cache, concurrency and wipe

SHA-256 revision hashes supplied data-class values (including coordinates, source IDs,
timestamps, current values and series), plus radar input for the returned revision. Evaluation clock,
derived radar frameAgeMin/sampledAtMillis/diagnostic notes and prediction-registration bookkeeping
are excluded. A content change with identical fetch time changes revision; the same inputs evaluated
later keep it. This is deterministic input identity within the current schema, not a versioned wire
serialization or a persistence contract. Unrelated snapshot value changes may conservatively invalidate.

The key for radar input work is point/place plus complete snapshot revision. Up to16 successful inputs
and16 in-flight loads are retained. Successful inputs expire after60 seconds of load age, including
clock rollback rejection. Failure is not cached. Extra requests at in-flight capacity immediately fall
back to model rather than starting unbounded work. Different place/revision work never conflates.
The loader runs outside the mutex in an application-owned deferred. Concurrent waiters share it;
individual waiter cancellation propagates but does not cancel application work. All loader cancellations
propagate, while normal exceptions yield unavailable radar. Await and load both have2.5-second deadlines;
a loader temporarily ignoring cancellation cannot hold home indefinitely. It remains bounded by the
in-flight cap and application lifecycle; an indefinitely noncooperative dependency cannot be forcibly
terminated by coroutines. No unobserved free-standing launch is created.

Only input is cached: every consumption recomputes model freshness, radar cutoff and absolute ETA.
`clear()` increments a generation, cancels pending work and clears inputs under the mutex. A pre-clear
request cannot return/repopulate a late result. Calls begun after clear are new work; callers own rejecting
obsolete UI location results and must not intentionally resubmit a pre-wipe snapshot as new input.
No later location request cancels other locations' legitimate consumers; each receives its own point/data.

## Written tests, not executed

`WeatherBriefExtensionsTest`: additive defaults, unknown periods, source timestamp versus physical
interval and invalid amounts/probabilities. `LocalPrecipitationEvaluatorTest`: present/arrival/end,
intermittent periods, both conflict directions, carried old context, outside/failure, no source/coordinates,
NaN/key/distance mismatch, closed versus future quarters, legacy/coarse/unknown data, download and valid
age, stale current origin, expired transitions/cutoff, duplicates/gaps/invalid probabilities/boundary
fragments, future disagreement, last-hour accumulation and stable/content-sensitive revision.
`WeatherBriefServiceTest`: ten concurrent consumers/one load, isolated waiter cancellation, cached
input crossing ETA/cutoff, same-fetch content and location changes during flight, wipe with a late
noncancellable loader, timeout/retry, noncancellable consumer deadline, input eviction, no-coordinate
short circuit, propagated upstream cancellation/retry.

Parent must compile and execute these plus the relevant existing suites and review F2 integration.
No Gradle/build, device, training, new source, key, commit/push or publication was performed by C.
