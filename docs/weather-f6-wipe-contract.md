# F6 A: upstream wipe and snapshot persistence handoff

Implementation only, 2026-11-07. Parent owns graph integration, all compilation/tests,
devices and independent reviews. Tests below are written and unrun. No C2 field accuracy,
C6 final performance or full accessibility satisfaction is claimed.

## Exact files and APIs

Changed production files in `core-weather/.../core/weather/`:

- `WeatherSnapshot.kt`: store/refresher/RegisteredRound and internal epoch helpers;
- `FusionCoordinator.kt`: originating epoch around the entire provider/verifier/fusion round;
- `ForecastVerifier.kt`: guarded final registration and settlement writes;
- `LocalRoundRegistrar.kt`: originating callback epoch, capture before queue, cancellation;
- `ProviderHttp.kt`: actual late URL-cache write race found in the same pipeline.

New tests: `core-weather/src/test/kotlin/dev/pampa/fluidweather/core/weather/WeatherWipePersistenceTest.kt`.
No other source files, historical evidence, versions, manifests, JSON format or Room schema changed
by this allocation. Existing uncommitted changes to WeatherSnapshot were retained.

Trailing constructor additions (use named arguments in parent):

```kotlin
WeatherSnapshotStore(directory: File, wipeGuard: DataWipeGuard? = null,
  ioContext: CoroutineContext = Dispatchers.IO)
WeatherSnapshotRefresher(coordinator, store, onGpsRoundRegistered = {}, clock = ...,
  wipeGuard: DataWipeGuard? = null)
FusionCoordinator(repository, verifier, fusion, fusionSettings, clock = ...,
  wipeGuard: DataWipeGuard? = null)
ForecastVerifier(store, minTruthOpinions = 3, clock = ...,
  wipeGuard: DataWipeGuard? = null)
ProviderHttp(http, cache, clock = ..., wipeGuard: DataWipeGuard? = null)
// LocalRoundRegistrar's existing trailing wipeGuard remains unchanged.

// RegisteredRound's additive trailing field:
val originatingWipeGeneration: Long? = null
// WeatherSnapshotStore additive public signal:
val changes: StateFlow<Map<String, Long>>
```

Secondary constructors preserve old trailing-clock lambda forms for refresher, coordinator,
verifier and ProviderHttp. RoundSource and DataWipeGuard public APIs are unchanged. The store's
ioContext permits deterministic queued read/clear and cancellation tests; production should use
the IO default, without a Job or custom lifecycle context.

## Required parent propagation

Inject the SAME `AppGraph.wipeGuard` into all five constructors above and the existing registrar.
Do not use independently created guards. Internal `WeatherWipeEpoch` is a coroutine context
element carrying guard identity and generation; nested stages inherit it rather than renew it.
Different explicitly configured guard identities fail fast. Repository async children inherit it.
The refresher captures it before the per-place mutex; coordinator captures it before fetchAll;
standalone verifier/HTTP/store operations capture it before their own waiting/IO/computation.

Keep callback `onGpsRoundRegistered = { round -> localRoundRegistrar.register(round) }`, forwarding
the SAME RegisteredRound including originatingWipeGeneration. Do not rebuild a round with a fresh
generation. The registrar captures an explicit round generation or inherited/current generation
before its mutex/compute dispatch; manually created legacy rounds with null still work. A legacy
round without an inherited epoch submitted after wipe is new work and cannot prove pre-wipe origin.

The callback runs OUTSIDE the wipe mutex, still inside the per-place mutex. Registrar independently
locks the guard only around final store/archive writes. Parent must not wrap callback/refresh/register
in `writeIfUnchanged` or `wipe`. Its `recordIssue` callback must perform the existing short archive
write directly and must not acquire the same guard again. Evaluation/climatology callbacks must
remain read/compute work; this allocation cannot roll back side effects in arbitrary callbacks.
The registrar installs its originating epoch into its compute context too, so any nested guarded
HTTP/store/verifier operation started during evaluation retains that origin after a wipe.

Keep wipe sequence under the existing `wipeGuard.wipe { ... weatherSnapshotStore.clear();
delete provider-cache directory ... }`. Store.clear itself never acquires the guard. Do not invoke
refresh/network while holding the wipe guard. Switch home/widget listeners from updates to changes
in parent-owned integration files. Existing updates remains placeKey -> fetchedAtMillis metadata.

## Behavior and locking

Pre-wipe late work fails conservatively with CancellationException before snapshot/cache/verifier
writes, even when transport ignores cancellation. Nested stages cannot acquire a post-wipe epoch
for old work. A cancelled parent is checked after long work and before writes; callback, evaluator,
climatology, archive and sensor cancellation is propagated. Ordinary callback/archive errors retain
their existing best-effort policy. Stale explicit registrar generations return null without writes.
Post-wipe requests can write normally with their new epoch. No provider, fusion, verification,
barometer or timestamp algorithms were changed.

Store read/decode, memory publication, file replacement and clear share a mutex. An independent
local clear epoch invalidates reads/writes queued before clear even without a shared guard. A read
racing clear cannot later refill cleared memory or return the invalidated queued disk read as current.
Memory and both flows update only after a successful atomic file replacement. Encoding occurs outside
the wipe mutex; short disk commit uses guard -> store lock. Read/clear use only store lock, so wipe
can take guard -> store lock without inversion. Neither network nor fusion/evaluation runs under guard.
Read IO/decoding releases the store lock before acquiring guard -> store lock for final publication;
this also waits out a wipe already in progress when the read began, then rechecks the local clear epoch.

Writes use a unique sibling temp file and Files.move(ATOMIC_MOVE, REPLACE_EXISTING), never truncate
the previous file as fallback. Unsupported atomic replacement/failing IO propagates failure, retains
the previous valid target and does not publish memory/signals. Temp cleanup is best effort. Clear
finishes local deletion under NonCancellable once invalidation begins, then checks caller cancellation;
deletion failure clears memory/signals, blocks disk reads and further writes in that instance, and
propagates failure. Parent must treat a failed wipe as failed; no process-recreation guarantee is
possible for undeletable files. Serialization/file operations remain bounded by ordinary snapshot IO;
there is no fsync/power-loss durability claim and no multi-instance shared-directory locking protocol.

Every successful write advances the content revision, including equal fetchedAt and identical data.
Revision values increase within the store instance and survive clear within that instance. Clear
empties changes and updates. Tokens are not download times or durable IDs across process recreation;
StateFlow retains its normal conflation behavior. Snapshot JSON remains v1, original timestamps,
place identity, coordinates, provider data and existing optional metadata are preserved.

## Provider-cache coverage and limits

Source audit found the production UrlCache instance in AppGraph and its only production writes in
ProviderHttp. This allocation guards that wrapper's cache writes and rejects invalidated cache hits;
download stamps and useCache=false behavior remain unchanged. Parent must inject the guard into the
singleton ProviderHttp to cover direct callers too. All WeatherClientFactory providers use this
wrapper: Open-Meteo variants, MET Norway, NWS points/hourly, OWM and Meteosource. Other wrapper users
include geocoding, PointWeatherClient, official alerts, RainViewer frame metadata and truth/archive
clients. These receive per-HTTP-operation protection; a full originating refresh epoch is retained
when called inside the refresher/coordinator context. Independent multi-step callers that start a
new HTTP operation after wipe have no broader transaction epoch unless their caller carries one.

UrlCache itself is unchanged: it has no independent wipe barrier, no in-memory cache, and retains
its existing best-effort direct-file persistence and mtime timestamp policy. No production bypass
write was found outside ProviderHttp. External direct UrlCache users would need their own barrier.
Raw URL-cache reads can overlap deletion but the wrapper checks the originating generation after
the read and rejects stale results. Unrelated failures/partial-file atomicity within UrlCache are
outside this correction; snapshot atomic replacement does not imply URL-cache atomic replacement.

RadarTileStore raster/empty-marker/download-sidecar writes bypass ProviderHttp and are NOT fixed
by this allocation. RadarAssessmentCache/WeatherBriefService and radar files belong to B and were
not edited. Guarding RainViewer HTTP metadata is not a claim that all radar persistence is covered.
EngineHttp downloads outside this wrapper and other data services retain their own existing rules.

## Written regressions and actual checks

WeatherWipePersistenceTest covers equal-fetch content revision/disk recreation, clear token reset,
queued read/clear without memory resurrection, legacy JSON v1 without optional metadata, cancelled
queued write preserving prior disk/memory/revision, noncooperative late refresh plus pre-wipe queued
refresh rejection, fresh post-wipe refresh with original source times and identity, same-guard callback
to registrar (no outer guard lock), explicit old callback epoch, registrar evaluation spanning wipe,
fresh archive writes, propagated evaluator cancellation, nested verifier old-epoch rejection, verifier
read spanning wipe with no settlement side effects, and noncooperative late HTTP response/cache
recreation followed by fresh successful HTTP persistence with the original stamp.

Actual checks: source/call-site/lock-order review, production cache-write search, and focused
`git diff --check` (tracked files), plus whitespace inspection of the new test and this handoff.
No Gradle, compiler, test runner, build, device/emulator, live provider, secrets, child agent,
commit, push or publication was run. Parent must compile/run the new tests plus existing
SnapshotAndAlertsTest, LocalRoundRegistrarTest, VerifierAndFusionTest, RoundWiringTest and
DownloadTimeTest, and validate runtime graph injection and platform atomic replacement.
No real-device timing, field accuracy, global accessibility or final performance evidence is implied.
