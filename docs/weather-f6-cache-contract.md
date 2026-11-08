# F6 B: persistent radar inputs and read-only brief contract

Allocation B implementation handoff. Implementation and focused regression sources are written;
**no compilation, Gradle, tests, device, live provider request, secret, commit or push was executed**.
Parent owns graph wiring, builds, device verification and independent reviews. C2 field accuracy
and C6 final performance/full accessibility are not established by this work.

## Consumer API (widget needs only this section)

Package `dev.pampa.fluidweather.core.weather`:

```kotlin
interface CachedWeatherBriefSource {
  val cachedInputUpdates: StateFlow<Long>
  suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief
}
```

Parent supplies this interface to the widget along with the existing snapshot and resolved exact
point. Call `cachedBrief` when snapshot content changes, `cachedInputUpdates` changes, or a widget
refresh/time boundary requires local reevaluation. Supply null when coordinates/snapshot are unknown.
It performs local disk/memory reads and `LocalPrecipitationEvaluator.evaluate(..., clock())` only.
It does not call radarLoader, providers, GPS, nowcast/model inference, or launch background fetches.
It returns newly evaluated facts, never cached output/prose. Use shared presentation/formatters on
the result; preserve its original evidence clocks, uncertainty and model-only qualifiers. A missing,
corrupt, mismatched or unusable radar input does not establish dry weather. The caller remains
responsible for rejecting obsolete location/snapshot results and scheduling local time reevaluation.
The token is a monotonic content revision in the running instance, not a source/download timestamp;
it starts at zero after recreation. Initial consumers must read regardless of its initial value.

Disk reads are dispatched to IO. No cache input is rewritten by evaluation. Persisted inputs can
remain present after the radar cutoff; the common evaluator rejects them at the actual consumption
clock and retains the existing expired-transition/conflict policy. Disk data is not subject to the
60-second *network reload* TTL: old frame freshness remains governed by F2's 20-minute source cutoff.

## Parent integration API

Existing constructor fields remain in order; these optional trailing fields were added:

```kotlin
radarCache: RadarAssessmentCache? = null,
wipeGuard: dev.pampa.fluidweather.core.data.DataWipeGuard? = null,
```

`WeatherBriefService` now implements `CachedWeatherBriefSource`. Existing `brief(...)` and suspend
`clear()` remain. New public API:

```kotlin
suspend fun radarSample(latitude: Double, longitude: Double): RadarSample
```

Create one cache/service before constructing nowcast. Supply the SAME shared DataWipeGuard used by
parent wipe (service defaults to its cache's guard; supplying a different guard fails construction).
Use the existing singleton sampler as radarLoader. Existing nowcast callback can call:

```kotlin
RadarObservations.of(service.radarSample(latitude, longitude))
```

`radar/RadarObservations.kt` adds `fun of(sample: RadarSample): RainObservation?`. The existing
suspend sampler overload delegates to it after sampling, retaining cancellation propagation,
sampledAt usability and confidence gates, rain threshold and Marshall-Palmer numerical conversion.
No sampler, motion, barometer algorithm, singleton configuration or trained AnalogPolicy.OFF change.

Loads use one shared implementation with the existing application-owned lazy deferred, load and
consumer deadlines, individual waiter cancellation isolation, upstream cancellation propagation,
retryable failures, capacity bound and 60-second successful-memory-input TTL. Brief work retains
point/place/snapshot-revision dedup to preserve F2 semantics. `radarSample` can share existing work
at its exact point; brief can share a point-only nowcast request already in flight/cached. Differing
brief revisions can still load separately as before. Every completed current request persists the
original sample, including explicit unavailable. A newer-started request supersedes older in-flight
work for cache publication, while older consumers retain their own correctly matched result.
Latest unavailable removes earlier successful memory revisions at that point and replaces disk data;
no failure fallback reads an older dry sample. Cache-only reads prefer disk whenever configured,
including missing/corrupt/tombstone results, without falling back to older service memory.

## Cache API and format

```kotlin
class RadarAssessmentCache(
  directory: File,
  wipeGuard: DataWipeGuard,
  clock: () -> Long = System::currentTimeMillis,
) {
  data class Epoch internal constructor(val clear: Long, val wipe: Long)
  val updates: StateFlow<Long>
  fun epoch(): Epoch
  suspend fun put(point: GeoPoint, sample: RadarSample, epoch: Epoch): Boolean
  suspend fun read(point: GeoPoint, epoch: Epoch = epoch()): RadarSample?
  suspend fun clear()
}
```

These methods support service integration/testing; widget code must use the read-only interface.
Capture Epoch BEFORE loading/long work. `put` returns false on invalidation or persistence failure.
Use one application-owned writer instance and a dedicated directory such as `radar-assessment-inputs`;
never pass existing snapshot/provider/raster directories. Parent calls service.clear in the shared
wipe callback, which also clears this directory and emits its token. Separate cache clear is optional
only when service.clear already ran. No reentrant guard acquisition occurs during clear.

Internal `RadarAssessmentCodec` uses magic RACH, binary version1, strict positional fields, explicit
nullable tags and enum names. It rejects unknown versions/tags/enums, missing/truncated/trailing data,
nonfinite numbers and invalid core scalar bounds. Max32KiB per file, lists max64, strings max2048
characters; invalid encodings cannot be published. Required metadata slots must exist even when
explicitly unknown/null. No manufactured complete/coverage/timestamp defaults. All RadarReading
constructor inputs, optional nearest/motion/ETA, notes and compatibility nowcast list are preserved.
Files contain original frame/sample/manifest/tile/coverage download times, original spatial/temporal
resolution and quality flags, exact sampled coordinates and a separate save time used only to reject
clock rollback. Save time never supplies source freshness. Coordinates use raw IEEE754 bits in
filenames and full Double identity checks in envelopes: no rounded point borrowing.

At most16 input files are retained (32KiB each, plus one replacement temp file). New files are
atomically moved over their point file. Explicit unavailable is a tombstone with original reason and
coverage. A failed write removes the prior point file to avoid falling back to older inputs; failed
clear deletion throws rather than reporting successful wipe. Existing app cache formats are untouched.
No previous version of this new separate input store requires migration.

## Concurrency and wipe boundaries

Both service and cache capture shared wipe and local clear generations before work. Publication and
cache reads acquire guard before their own mutex. Network is outside every guard/mutex. Cache file
IO is local and serialized under guard/cache mutex so wipe cannot overtake a partially published read
or replacement. Service final evaluation/publication rechecks its captured generations. Clear takes
service then cache mutex, never acquires the guard, and is safe inside `DataWipeGuard.wipe`.
A separate publication mutex serializes finished loads before cache persistence, then memory
publication; it is never acquired by clear or wipe. Guard acquisition is never nested. Invalidation
also prevents noncooperative loaders from persisting/returning an old epoch after cancellation.
If the shared generation advances without explicit service.clear, subsequent access invalidates old
memory and the cache instance removes old input files before reading/writing. Parent should still
invoke service.clear during wipe for immediate disk deletion and token emission.

Filesystem errors cannot guarantee durable deletion when the OS refuses it; clear surfaces that
failure. Atomic replacement assumes the dedicated local app filesystem supports ATOMIC_MOVE.
No multi-process/multi-writer same-directory coordination is introduced. Coroutines cannot forcibly
terminate indefinitely noncooperative dependencies; existing deadlines/capacity/lifecycle limits remain.

## Files and verification

Production sources in `core-weather/src/main/kotlin/dev/pampa/fluidweather/core/weather/`:
`CachedWeatherBriefSource.kt`, `RadarAssessmentCache.kt`, `RadarAssessmentCodec.kt`,
`WeatherBriefService.kt`, and `radar/RadarObservations.kt` only.

New tests in `core-weather/src/test/kotlin/dev/pampa/fluidweather/core/weather/`:
`RadarAssessmentCacheTest.kt` and `CachedWeatherBriefSourceTest.kt`. They cover exact disk recreation
and all input metadata, unknown/missing/corrupt/oversized/unknown-version fields, precise location
mismatch, unknown coverage, rollback, bounded files, explicit unavailable after dry and after an
older in-flight completion, local/shared epochs, queued-read versus clear, reentrant-clear lock order,
loader acquiring guard, concurrent nowcast/brief and isolated cancellation, noncooperative completion,
cache-only no-loader behavior, ETA expiry/cutoff, model conflict and pure adapter numerics.
Existing WeatherBriefServiceTest and radar tests are unchanged and remain parent regression gates.

Actually performed: source/contract inspection, targeted diff whitespace check, scoped static source
checks and manual concurrency/API review. All new and existing tests are **written/unrun**, and no
compile result is claimed. Graph/widget/home/A-owned sources, engine, versions/manifests and historical
F0-F5 artifacts were not edited by B. Parent must compile and run the focused suites plus existing F2
regressions, integrate graph/wipe/nowcast/widget listeners and perform device/review validation.
