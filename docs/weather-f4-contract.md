# F4 stable interfaces and allocation

F3 approved on F3-88f49a34b5c4 by two independent Sol high critics, all five scoped criteria8.
Old phase artifacts/archives remain immutable. F4 starts from empty personal configuration, adds
no notifications, uses current providers and leaves engine3.0.5/barometer model unchanged.

## Model and public API (stabilized by parent before parallel work)

Existing `RoutineWindow(id,name,daysOfWeek,start,end)` is a recurring wall-time rule. Days identify
the START day, including overnight windows. Equal endpoints are invalid. Parent added
`core-model/RoutineAssessment.kt`: `RoutineOccurrence(routine,interval,zone,adjustedForDst=false)`
and `RoutineWindowAssessment(occurrence,evaluatedAtMillis,precipitation=UNAVAILABLE,coveredMillis=0,
rainAmountMm=null,maxProbabilityPercent=null,minTemperatureC=null,maxTemperatureC=null,
evidence=emptyList(),limits=emptySet(),assessedInterval=occurrence.interval)`. All times/coverage are absolute milliseconds. Amount
requires exact full coverage; nonempty known outputs require evidence. `hasCompleteCoverage`
compares the precipitation union with assessedInterval duration. For an ongoing window,
assessedInterval starts at evaluation time: earlier rain is not presented as remaining future rain.
Occurrence retains the full configured window for display. `WeatherBrief` has a trailing
`routines: List<RoutineWindowAssessment> = emptyList()`; existing callers stay compatible.

A implements these exact APIs:

```
// core-data, constructor Context, with injectable DataStore secondary/internal API for tests
class RoutineWindowStore(context: Context) {
  val windows: Flow<List<RoutineWindow>>
  suspend fun current(): List<RoutineWindow>
  suspend fun upsert(window: RoutineWindow)
  suspend fun remove(id: String)
  suspend fun clear()
}

// core-weather, no provider/radar request, no sensor requirement
class RoutineWindowEvaluator {
  fun nextOccurrences(windows: List<RoutineWindow>, evaluatedAtMillis: Long,
    zone: ResolvedWeatherZone, limit: Int = 2): List<RoutineOccurrence>
  fun evaluate(windows: List<RoutineWindow>, place: Place, coordinates: GeoPoint?,
    snapshot: WeatherSnapshot?, evaluatedAtMillis: Long, deviceZone: java.time.ZoneId,
    limit: Int = 2): List<RoutineWindowAssessment>
}
```

Return the next still-active or future occurrence **once per configured routine**, sorted by its
actual start instant (ongoing first), with stable id tie-break, then limited. Home requests all
configured routines and displays the first two by default; a details affordance can reveal other
configured moments. With one routine, show one next occurrence. No invented default routines.
Resolve zone with existing WeatherZones place > matching snapshot/context > explicit device
fallback. Never borrow timezone/weather from a foreign place/point. Dates/weekdays belong to
that resolved zone, not the device. Cover overnight previous start day, DST gaps/overlaps and
date changes with explicit deterministic policy, adjustment flag and tests; invalid/zero real
intervals must not become negative durations or silently one-day windows. Document chosen DST
policy in A's handoff, including a gap start whose normal end precedes the shifted start.

Forecast aggregation uses original context/model metadata, never radar ETA/current as a forecast
for a personal window. Preserve source/download, exact accumulation intervals and resolution.
Use finite, unique, actual available intervals; no missing->zero, duplicated sums, prorating
amounts across boundaries, implicit one-hour duration for an unknown provider product or double
counting minutely+hourly. Full DRY requires complete known coverage; partial dry cannot assert
the whole window dry. A contained wet interval can establish some wet forecast with explicit
partial-coverage limit. Show total rain only with exact full coverage. Temperature extrema use
only actual represented forecast samples; no extrapolation. If no known precipitation evidence,
return UNAVAILABLE; retain other known fields/evidence separately if available. Distinguish
partial/stale/location mismatch. Apply the existing60min model download freshness policy, retain
original timestamps and keep stale outputs explicit. No field accuracy claims.

Store starts empty, uses a dedicated Preferences DataStore with versioned robust codec, no Room
migration. Unicode names and overnight/day/time roundtrips; stable ids, atomic updates and no
lost concurrent edits. Handle corruption/unknown versions conservatively, cancellation must
propagate; write failures reach callers so UI can report them. Follow existing app preference
conventions. Personal times are settings and remain when the existing archive-only wipe preserves
settings; explicit removal/clear is available in this store. Don't modify global wipe behavior.

## Ownership

- A (Sol high): new core-data RoutineWindowStore/codec + focused tests and optional existing-engine
  test-only dependency in core-data/build.gradle; new core-weather RoutineWindowEvaluator/tests;
  focused core-model tests for parent contracts. New docs/weather-routine-data-contract.md.
  No edits to parent-stabilized RoutineAssessment.kt/WeatherBrief.kt/RoutineWindow.kt without
  reporting the need; no AppGraph, HomeData/controller/UI/navhost/strings/engine edits.
- B (Sol high): new feature-settings RoutineWindowsScreen/editor; SettingsScreen category callback;
  new feature-home HomeRoutineWindows overview and HomeScreen integration below rain synthesis;
  HomeData/HomeBriefController consume store changes and produce WeatherBrief.routines with A's
  evaluator. Can extend HomeDependencies with required `routineWindowStore:RoutineWindowStore`.
  Store changes/location changes/wipe/clock changes must not publish obsolete routine assessments.
  New core-strings resources/formatter EN/IT in dedicated routines files, relevant UI/controller
  tests and optional app Android routine tests (new file only). New docs/weather-routine-ui-contract.md.
  No core-data/core-weather/core-model/AppGraph/navhost/build.gradle edits.
- Parent: graph singleton `routineWindowStore`, all constructor call sites and navigation route;
  integration/build/device/tests, immutable manifest/reviews/status. Max two implementers, no
  child agents, no Gradle/device/commit/push by implementers. Emulators remain off until actual QA.

## B UI and lifecycle contract

Expose `RoutineWindowsScreen(store:RoutineWindowStore,onBack:()->Unit)` in feature-settings.
SettingsScreen gains `onOpenRoutines:()->Unit`; HomeScreen gains that callback, parent wires both
to a new settings/routines route. The editor creates/edits/removes name, localized days and time
range with actual Fluid fields/buttons/chips, validation, accessible names and save/error state.
Support overnight and require a day/name/non-equal endpoints. Empty state is discreet, home add
link below the rain overview. No inert CTA, keys, debug jargon or unsolicited notification flow.
Screen content scrolls at2x. New draft starts with explicit user entry, not a silently persisted
commute schedule. Errors stay visible and drafts survive ordinary recomposition.

Home consumes saved windows and matching snapshot using A evaluator on a background dispatcher,
on routine/snapshot/place change and at least each minute while active/RESUMED. Reuse existing
structured controller, cancellation/generation and recheck-after-IO protections. Include routine
configuration in consumer identity; a radar request started under old config cannot restore old
windows. Do not change F2 radar/provider policy or caches. WeatherBrief.routines holds the shared
domain output, localized only by UI. Render first two named windows with actual place-zone date,
times and short qualified forecast/coverage/age; other configured windows accessible through an
expansion/details affordance. Explicitly label DEVICE_FALLBACK, unknown/partial/stale and relevant
DST adjustment. Keep principal rain answer above this block and preserve tile ids/order/drag.

## Phase criteria and evidence

C4.persistence-editor: empty default, roundtrip/recreation, edit/delete/validation/errors without
lost entries. C4.temporal: next occurrence/day/overnight/place zone/DST and actual coverage correct.
C1.routine-evidence: provenance, limits, no missing zeros/false precision. C3.quick-windows: first
two below synthesis and others/edit reachable, main answer still visible. C7.integration: pertinent
tests/build/end-to-end. Both critics independent Sol high, scale0-10;8 verified satisfied; below8
requires correction. Full global C2/C6 remain NV. Parent tests only after both implementers finish.
