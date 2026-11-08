# F4 personal routine data and time contract

Implementation A owns only the new store/codec, evaluator, focused tests and this document.
Parent model/API files are unchanged. Engine remains pinned; no build, tests or device work
was executed by A. The stabilized signatures in `weather-f4-contract.md` are retained.

## Persistence

`RoutineWindowStore(Context)` uses application context and a dedicated `routine_windows`
Preferences DataStore, key `rules`. An internal `RoutineWindowStore(DataStore<Preferences>)`
constructor supports real disk tests. `windows`, `current`, `upsert`, `remove`, `clear` are
the complete interface. New installations return an empty list. DataStore edit transforms
read and write the complete collection atomically, avoiding lost independent concurrent edits.
Replacement preserves ID and position. Removal uses stable ID. No archive/global wipe changes.

The version-1 codec is Base64 of a bounded binary document: version/count int32, then
length-prefixed strict UTF-8 ID/name, weekday bitmask int32, start/end nanoseconds int64.
Limits are 1024 rules and 1 MiB decoded payload. Unknown version, malformed/truncated/trailing
data, invalid times/days/models/UTF-8 or repeated IDs reject the whole document. Reads show no
invented settings. Edits on unreadable encoded settings throw IOException rather than destroy
them; explicit clear is a recovery action. Underlying Preferences file corruption follows the
existing replacement-with-empty preference convention. Other read/write errors reach callers.
No flow/store exception handler swallows cancellation. Tests use real file recreation and
concurrent upserts, plus failure/cancellation fakes. No new test dependency is required.

## Calendar and DST

Each rule returns its next still-active or upcoming occurrence exactly once. Start-day weekdays
are evaluated in the resolved consulted-place zone. Yesterday is searched for overnight rules;
today through fourteen days ahead covers a weekly rule whose DST occurrence is invalid. Sort
is by actual start then stable ID, followed by the requested limit. Callers needing all rules
must pass their configured count; default is two. No configured rules means no occurrences.

Zone precedence: valid place zone, matching snapshot zone, matching context zone, explicit
device fallback. Snapshot must match place key AND supplied coordinates within 2 km. Context
must independently match coordinates within 2 km. Foreign metadata is never borrowed.

DST policy is java.time's deterministic local-time resolution: shift each gap endpoint forward
by the transition duration; choose the earlier offset for each ambiguous overlap endpoint.
Mark adjustedForDst whenever either wall endpoint is ambiguous/nonexistent or the interval
contains a zone transition. Resolve endpoints
independently. If shifted start is equal to or later than end, skip that date, retaining the
configured next weekly/daily occurrence. Never add a fake day to repair a collapsed interval.
An overlap produces one occurrence, never two. Full occurrence remains available to UI;
assessedInterval starts at max(now, full start). Exact end is expired. Re-evaluate on clock,
place, zone or settings changes; the evaluator has no hidden system clock or device zone.

## Forecast evidence and availability

Use only original matching snapshot.context. FusedHour lacks original accumulation metadata;
neither it nor provider current/radar/weather codes substitute routine forecast evidence.
Download freshness is <=60 minutes, with future download timestamps rejected. Stale source
evidence remains explicit, while weather values and coverage are withheld. Source ID, original
download and point timestamps, and actual accumulation intervals are retained. Context fetch
time is authoritative even if snapshot was refreshed more recently.

Precipitation requires explicit positive accumulation duration AND PRECEDING/FOLLOWING anchor.
No default quarter/hour, series-spacing inference, boundary prorating or extrapolation. Only
whole contained intervals inside assessedInterval qualify. Nonfinite/negative amounts and
out-of-range probabilities are absent. Duplicate timestamps are rejected, including identical
duplicates; overlapping intervals within a product are all rejected. Minutely and hourly are
never summed together. Select the product with greatest known amount coverage, tie preferring
minutely. This deliberately leaves complementary mixed-product gaps unknown. Coverage is the
sum of disjoint selected known-amount intervals. Total requires exact coverage and no detected
wet/dry conflict between overlapping products. Conflicting products return UNCERTAIN. A wholly
contained wet interval can establish some rain with partial coverage, even from the other
product; dry requires full coverage. Partial dry is UNCERTAIN, no known amount is UNAVAILABLE.
Probabilities alone never prove wet or dry or count as amount coverage.

Temperature extrema use unique actual hourly sample timestamps inside assessedInterval only.
They describe represented samples, not interpolated extrema throughout the entire interval.
Probability is the maximum of actual qualifying accumulation samples, not a calibrated whole-
window probability. It can survive independently of absent amounts with provenance. No native
temporal/spatial resolution is stored in ForecastBundle; evidence leaves resolutions null and
marks coarse time/unknown provenance instead of inventing resolution from accumulation duration.
Minutely interpolation uncertainty is retained. MODEL_ONLY and partial/stale/location limits
remain explicit; no field accuracy claim is made.

## Verification handoff

Written, not executed: RoutineWindowStoreTest, RoutineWindowEvaluatorTest,
RoutineAssessmentContractTest. Static checks only. Parent must compile/run these and integrate
all-rules Home calls, lifecycle/clock refresh, visible error handling and localized limits.
No UI/provider/F2 evaluator/AppGraph/navigation/string/model source edits were made by A.
No dependencies, publication, commit, push, notifications or sensor changes.

Conservative limitations: snapshot context exposes one original model product bundle, unknown
duration providers are unavailable for amounts, partial mixed-resolution products are not
stitched, and temperature coverage is sample-based. Rare historical midnight/date-line zone
transitions use the same java.time policy but have no dedicated fixture yet. File-level
Preferences corruption recovery is configured but not instrumented in these local tests.
