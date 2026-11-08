# F6 contracts and staged ownership

F5-r1-6072ea3caedf accepted by both fresh independent Sol high reviewers, all five scores8.
F5 historical artifacts/manifests/archives are immutable. F6 integrates consumers and closes
cross-cutting correctness/accessibility gaps; no engine/provider upgrade, retraining, new alerts,
commercial source, publication, commit or push. Parent owns AppGraph, FluidWeatherApp, navigation,
HomeData change-signal wiring, all builds/devices, global status and independent reviews.
Max2 implementation agents at once, fresh Sol high per topic; no implementer builds/devices/children.

## A: upstream wipe and snapshot persistence

Write scope: core-weather WeatherSnapshot.kt (store/refresher/RegisteredRound), FusionCoordinator.kt,
ForecastVerifier.kt and LocalRoundRegistrar.kt only as needed, associated existing/new tests;
relevant existing weather HTTP persistence wrappers if an actual same-family race is found, without
editing B files below. Optional trailing constructor parameters preserve existing call sites.
No AppGraph/FluidWeatherApp/HomeData/engine/core-model/builder/widget/UI edits. DataWipeGuard's
existing public API remains stable (B relies on generation/writeIfUnchanged/wipe semantics).

Required: capture the shared wipe epoch BEFORE network/long IO and prevent late snapshot, cache
memory, verifier or registrar writes after wipe. Cancellation propagates. No network under the
wipe mutex, no reentrant guard calls or lock inversions. Registrar callbacks must retain originating
round epoch, rather than acquiring a new post-wipe epoch after stale work. Inspect provider-cache
persistence in the same pipeline; report precise coverage/residual limits, not a global unsupported
claim. Store reads racing clear must not repopulate memory/return old data as current. Serialize
file replacement/clear coherently; preserve old valid files on failed writes. No destructive cache
format migration; JSONv1 and Room8 preserved, no configuration deletion.

Keep WeatherSnapshotStore.updates timestamp semantics (existing tests/consumers). Add EXACT:
`val changes: StateFlow<Map<String, Long>>` as a per-key monotonic CONTENT change signal that emits
on every successful write including identical fetchedAt, and becomes empty on clear. Values are
revision tokens, not source/download timestamps. Parent switches home/widget listeners to changes.
Document any exact new guard constructor/callback fields in weather-f6-wipe-contract.md. Tests must
reproduce noncooperative late refresh after wipe, read/clear races, same fetchedAt changes, fresh
post-wipe refresh and shared callback epoch, with matching original data/time/identity preserved.

## B: persistent radar inputs and read-only common evaluation

Write scope: NEW core-weather RadarAssessmentCache.kt / codec helpers and CachedWeatherBriefSource.kt,
WeatherBriefService.kt, radar/RadarObservations.kt only for pure sample overload, their tests and
weather-f6-cache-contract.md. No A-owned snapshot/coordinator/verifier/registrar files, no AppGraph/
FluidWeatherApp/widget/home/UI/engine/core-model edits. Do not change radar algorithms or barometer
numerics. DataWipeGuard is existing shared primitive, not an owned file.

Stable consumer APIs (exact signatures; extra private helpers permitted):

```
interface CachedWeatherBriefSource {
  val cachedInputUpdates: StateFlow<Long>
  suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief
}
class RadarAssessmentCache(directory: File, wipeGuard: DataWipeGuard, clock: () -> Long = System::currentTimeMillis)
// Concrete cache internals/read/write/epoch API may be chosen by B; document exact API.
// Add trailing optional constructor params to existing WeatherBriefService:
radarCache: RadarAssessmentCache? = null, wipeGuard: DataWipeGuard? = null
// WeatherBriefService implements CachedWeatherBriefSource, retaining existing brief()/clear().
suspend fun radarSample(latitude: Double, longitude: Double): RadarSample
// RadarObservations adds pure overload, existing sampler overload delegates unchanged numerics:
fun of(sample: RadarSample): RainObservation?
```

Persist ORIGINAL numerical RadarSample inputs (RadarReading is small, not a raster), with exact
sampled point, absolute frame/download times, coverage/completeness and motion/ETA quality metadata.
Bound size/count and strict version/field parsing; malformed/unknown/missing data => unavailable,
not dry. Never reset original timestamps or manufacture usable defaults during decode. Match exact
coordinates (do not borrow a point's atPointDbz for another location); coordinate identity/cache
keys preserve full precision. Honor cancellation. Latest explicit unavailability must not silently
resurrect an older usable sample. Persistent cache/late noncooperative completion is protected by
both clear and shared wipe epochs, with tested lock order and no network under guard. Optional
absent cache preserves F2 test/API behavior. Old app cache files remain readable; no previous format
is required for this new separate input store. Keep source cache bounded and time validity from F2.

cachedBrief performs only local cache reads and calls the SAME LocalPrecipitationEvaluator at the
actual consumption clock with caller-provided snapshot; zero provider/radar/GPS/model requests,
no background launch that fetches. Missing inputs remain explicit/model-only as F2 decides. It does
not redisplay cached output as current or promote forecast to observation. Existing brief and new
radarSample share safe loading/dedup/timeout/cancellation semantics and populate the input store;
clear invalidates memory and persistent input caches and publishes the cache-change token.
Parent creates cache/service before nowcast and uses radarSample + RadarObservations.of(sample)
for the existing nowcast callback, without extra radar requests. Widget gets only the read-only
CachedWeatherBriefSource interface. Tests cover disk recreation/codec original times, stale/expired
ETA/model conflicts, coordinate mismatch, missing/corrupt/unknown coverage, latest unavailable,
concurrent consumers/cancellation and wipe/clear during noncooperative IO. No field accuracy claim.

## Subsequent units (not launched yet)

C after B API validation: feature-appwidget reads CachedWeatherBriefSource and existing snapshot,
uses shared current/precipitation/qualified wording and source age, removes independent null=false
rain rule. No widget-owned requests/sensor work. Listen to snapshot content/cache input changes;
keep old cache/temp/forecast/deep-link/theme/unit behavior available with explicit provenance.
Parent wires read-only runtime properties. C owns feature-appwidget plus dedicated EN/IT strings/tests.

D can run when a slot frees (independent of A/B): feature-home UI/accessibility only. Daily2x
responsive labels and values without fixed narrow widths, preserve row order/openings/units;
sub-minute timeline semantics must not give equal apparent interval endpoints, retain true source
precision and handle DST repeated wall times; details/precipitation page use existing state.weatherBrief
and original source age/limits for the present before model-only hourly details. No new IO/evaluator,
no core-model/WeatherSnapshot/WeatherBriefService/graph edits. Actual Fluid components/backdrops/
motion and stable tile IDs/order retained. Exact allocation follows when launched.

## F6 review/verification scope

C1.consumer-consistency: common per-place/time assessment, original source clocks/age, no missing
as dry, read-only widget cache and explicit source-qualified model details.
C7.wipe-persistence: no late pre-wipe refresh/cache/callback resurrection; disk/memory consistency,
old JSON/schema/settings conservative and content changes observed.
C7.lifecycle: rapid location/context updates, refresh/chat/close/reopen/offline/sensor-absent/cache/
clear cases with cancellation and meaningful end-to-end tests/build/doctor.
C6.targeted-accessibility: targeted new/fixed UI semantics, EN/IT fonts2x/phone/tablet/reduced motion/
glass levels and detail reachability. Full TalkBack/quantified contrast and controlled p95/memory
comparison remain separately evidenced rather than inferred; global C6 final comparison is F7.
Global C2 field remains NV. Missing mandatory phase evidence is not an automatic pass. Two fresh
Sol high critics per round,8=verified satisfied, max3 correction rounds after initial review.

User steering2026-11-07: accessibility effort should remain proportionate. Keep the practical
readability/reachable-control fixes and existing targeted tests; do not expand into full TalkBack
or quantified-contrast certification as a prerequisite for F6. Functional data/wipe/widget behavior
remains the priority. The original independent field and matched rendering/memory gates are unchanged.
Evaluate missing functional runtime evidence honestly; this steering does not authorize invented
test outcomes or an average score overriding an unmet mandatory functional requirement.
