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
value; subsequent reads retry. A successful empty history remains empty. The
cleaning API itself returns null on missing data or internal failure, so null does
not discard an already displayed cleaning result. Wipe resets the holder instead.

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

## Regression handoff (not executed)

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
