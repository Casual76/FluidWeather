# Home local hydration contract

Scope: reliability of the home consumer only, based on committed parent
`88655da79e208819da16c251b8b2051f969f4cda`. These are source findings and regression
expectations, not evidence of a particular hardware incident.

## Local archive ownership

For the GPS/current-place home with a supported barometer, a separate RESUMED,
visible-home coroutine reads cleaning, latest raw pressure, 24-hour verdict history,
and the existing readiness API. Its first read does not await the snapshot store,
a GPS fix, weather refresh, air quality, or geocoding. Denied location and network
failure therefore cannot erase or postpone usable local history.

Room `PressureRepository.latest(1)` invalidations and calibration start/end trigger
new reads. A 60-second timer also reads history written without a pressure change
and ages readiness while sampling is stopped. An ordinary observation failure
falls back to polling. Ordinary read failures preserve the corresponding displayed
value; subsequent reads retry. Successful confirmed empty reads clear the old
value, and wipe resets the holder. Home opts into `failOnLocalReadError = true`
for `NowcastUseCase.clean`, `readiness`, and its ordinary `evaluate` call. This
mode propagates acquisition/cleaning failures and cancellation to Home's existing
failure boundary instead of returning a successful null or zero-hour fallback.
Other callers retain the established optional-read behavior by default. The
cleaning, readiness and model formulas are unchanged.

Only read APIs are called by this consumer: `NowcastUseCase.clean`,
`NowcastUseCase.readiness`, `PressureRepository.samplesSince`, and
`NowcastHistoryStore.since`. It does not evaluate the model, record verdicts,
request a sensor burst, change calibration, or fetch weather. The existing
weather-load evaluation and its recording policy remain in place. Live verdict
inference is not introduced into the polling loop. Readiness formulas are unchanged.
Saved places and devices without a barometer do not start this consumer.

## Snapshot identity and recovery

A snapshot arriving after an initial no-fix return can establish the holder's
coordinates only after the existing key, finite-coordinate, and distance rules
accept it. A GPS holder with no point can adopt a `gps` snapshot's own point;
`place-N` weather can never be relabelled as GPS weather. Once GPS point A is
known, a snapshot from B beyond the existing 2 km tolerance is rejected. Saved
places always validate against their selected coordinates. Existing wipe recovery
may adopt the new epoch's GPS point, as before.

An accepted initial late snapshot re-arms the returned load and installs coordinates
and solar phase. It preserves `fetchedAtMillis`, context provenance, and all source
clocks. Resolving GPS B after cached A discards A's weather but preserves this
device's local archive. Selected-place persistence is unchanged.

## Wipe and cancellation

Every local read captures the data generation. Publication checks generation and
coroutine activity after disk work and again after switching to the UI coroutine.
Snapshot startup checks both disk-read and optional timezone-backfill boundaries.
The existing snapshot observer retains its post-backfill generation and cancellation
checks. Legacy load-state writes now also check their owning coroutine's activity.
Obsolete location jobs and pre-wipe reads cannot republish into the current holder.
The upstream snapshot-store generation policy remains authoritative; this change
does not invent a second store generation or alter the existing invalidation cutoff.

## Original regression handoff (historical implementation-agent scope)

`HomeLocalHydrationTest` adds scenarios for blocked GPS/network and no location,
local failure followed by sampling recovery, timer-only history/readiness updates,
wipe during disk work and current-epoch recovery, swallowed cancellation, wipe
at snapshot-read/backfill boundaries, late GPS identity recovery, A/B and saved-city
rejection, unsupported-sensor scope, and empty local data.

No Gradle, build, test runner, device, provider, network, or secret access was used.
The root owns compilation, JVM test execution, Compose lifecycle integration,
and hardware checks. These unit regressions exercise extracted production helpers;
they do not establish device behavior or execute the complete composable.
No dependencies, engine files, versions, model, shared weather service, permission,
notification, feedback, or persistence-policy files are changed.

## Root correction2 validation

The correction1 technical review identified the real swallowed-error boundary:
an exception thrown inside the old `clean`/`readiness` APIs became a successful
null/zero before reaching the hydration helper. The strict Home acquisition mode
now preserves that exception identity. Successful empty acquisition remains a
replacement, including in the ordinary weather-load path.

`WeatherLocalReadFailureTest` exercises the actual `NowcastUseCase` and
`PressureRepository` with a read-only synthetic DAO and the app graph's existing
cleaner/stores. It reproduces the legacy fallback, checks strict clean/readiness/
evaluate failure identity, successful empty results, unchanged cleaning numerics
and recovery over 14 hours of synthetic samples, and cancellation identity.
All three methods passed on the sensorless SM-X710 in 0.349s. The synthetic
capability flag is API-boundary coverage, not a physical sensor test. The test
does not write pressure, calibration or history into the real archive.

`HomeLocalHydrationTest` also passes an actual CleaningPipeline result through
failure-preserving merge and confirmed-empty replacement. Existing wipe,
cancellation, generation and independent-read scenarios remain. Full execution
accounting and APK identities are in `weather-premium-verification.md` and the
frozen correction2 inputs; no process-death or multi-day continuity claim follows.

## Correction3 caller boundary

Correction2's independent hydration handled strict failures correctly, but its
ordinary evaluate call was not guarded. Home now acquires that result through
the cancellation-preserving `homeCatching` boundary. It replaces verdict,
cleaning, raw pressure and readiness only on success. Ordinary acquisition
failure retains those fields and continues air/name work. Forecast/local-load
completion clears loading/refreshing before optional auxiliary lookups. A
successful empty result remains an explicit replacement.

The caller regression invokes the actual `rememberHomeState` Compose effects
and actual NowcastUseCase/PressureRepository/cleaner. A private snapshot/cache,
synthetic read-only pressure DAO, memory history DAO and private Preferences
baseline prevent writes into the app's real archives/settings. The baseline
store's Context constructor still resolves the identical preferences store and
keys; a DataStore constructor permits isolated ownership. The default name
lookup is unchanged, with an optional dependency callback so this test uses no
geocoding/network/GPS. Synthetic capability on the sensorless tablet exercises
the local caller, not hardware. Execution results and the initial test-clock
failure are recorded in the verification document after completion.
