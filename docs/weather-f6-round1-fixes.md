# F6 correction 1: consumer parity and real lifecycle

This record supersedes the open verification portions of the round0 report for the paths tested
below. It is evidence for independent re-review, not a phase approval. Historical round0 files,
manifest and archive remain immutable. No publication, engine/model/schema change or key access.

## Shared precipitation consumption

Round0 R2 identified that a rejected radar could add aggregate STALE while a fresh model still
supported WET/DRY. The widget and summary consumption gate incorrectly rejected that whole result.
The parent reproduced this with real evaluator -> public formatter -> production widget-model
regressions. The pre-fix run has three tests, two failures (`parity-before-fix.log/xml`). It also
exposes the same cause with an excluded stale/foreign model alongside valid local radar.

WeatherSummaryPolicy now applies source provenance/freshness to the evidence actually contributing
to the assessment. Aggregate STALE/LOCATION_MISMATCH still remain disclosed for excluded inputs,
but do not veto valid contributors. Assessment/horizon expiry, passed transitions, source clocks
and contributing-evidence limits remain enforced. No evaluator threshold or source timestamp was
changed. AppWidgetEvaluatorParityTest covers fresh model wet/dry + expired radar; fresh radar +
rejected model; and genuinely expired contributing sources. Widget+strings current results68/68.

## Actual Android lifecycle evidence

WeatherLifecycleIntegrationTest uses the target FluidWeatherApp.graph and production MainActivity
through ActivityScenario, not an isolated replacement composable or graph. Preconditions explicitly
require airplane mode on and no network, no blocking release gate and no conflicting reserved Place
IDs. Actual SensorManager and graph agree barometerAvailable=false on this Android15 emulator.
Weather acquisition is deliberately synthetic through a production WeatherSnapshotRefresher using
the actual graph store and wipeGuard. This is not a real provider/field-accuracy experiment.

Three final methods pass in `lifecycle-device-final.log`: **OK(3 tests),17.548s, terminal -1**,
without the aborted-fixture recovery argument. Native window assertions verify visible heading,
localized current values, selected place and timeline; they read the actual Android accessibility
window without waiting for Espresso graphics idle. The exact contract is in
weather-f6-lifecycle-test-contract.md.

- Wet/current fixture -> actual AppGraph.wipeAllData -> visible current/rain/timeline unknown.
  A delayed pre-wipe production refresher is released after wipe without cancelling its job;
  its originating generation is rejected. Actual memory and new disk-reader snapshots are absent,
  cached radar is absent/explicitly unavailable and the runtime widget source reports unavailable.
- Real Activity recreation, close/relaunch while offline and repeated unknown assertions. Saved
  places, routine configuration, units, engine settings, onboarding and selected identity survive.
  An explicit post-wipe synthetic refresh restores dry/current values with original clocks intact.
- Actual Home consumes changed weather with identical fetchedAt (content token advances, timestamp
  signal unchanged), settles on the correct A/B context after rapid selections, and consumes a
  changed B snapshot across actual CREATED -> RESUMED lifecycle. Then A retains its distinct data.
- The third test checks recovery in the SAME still-open Activity, plus another wipe immediately
  followed by new input without waiting for the empty emission. Home and widget source agree.

The third case exposed a second concrete defect: HomeData latched wiped=true and ignored all later
store emissions until reload/recreation. `lifecycle-active-recovery-before-fix.log` records the real
failure: widget already DRY/current24C, Home remained unavailable. HomeData now reads a matching
snapshot under the store's current epoch, checks generation after IO/backfill and re-arms consumers.
Generation changes invalidate the holder even if StateFlow conflates clear/new-write events. An
independently cancelled stale read does not kill the observer; cancelled composition jobs still
throw via ensureActive. The old jobs retain their generation guards. No source age is renewed.

## Runner diagnostics and distinct evidence

The original Compose/Espresso assertion reader stalled at Espresso.onIdle while the real graphics
queue continued drawing. Manual Compose-clock control did not resolve that native idle wait.
Temporary own-process thread diagnostics identified fetchSemanticsNodes -> Espresso.onIdle;
the diagnostic probe was removed. Three stalled attempts were externally terminated through the
bound Device CLI. Their Process crashed instrumentation result records that deliberate abort,
not an app crash diagnosis, pass or completed suite. A root-required trace reader was refused;
no elevation was attempted. All logs remain identified separately.

The native-window reader retains the real Activity, graph, production privacy action, source/store
and visible state checks. An explicit optional recovery argument can remove only exact fixture
values left by a known aborted run; other ID collisions fail. Final clean runs require no recovery.
An earlier native two-method pass predates the additional HomeData recovery fix and is not counted
as extra final methods. The failed recovery probe is an actual assertion failure, not suppressed.

On the final binary, a separate regression suite passes **19/19**,26.052s, terminal -1: summary UI,
actual-resource future forecast/parser, routine editor/home, rain overview, root motion policy and
graph wiring. This is **22 fresh Android method successes** across the two final logs. The prior56
parameterized F6 layout cases are unchanged-source historical evidence, not56 fresh runs here.

After the tests, the parent opened the actual launcher while still offline. The existing Glance
widget shows temperature unavailable, Rain data unavailable and unknown source/download times,
rather than the old Roma temperature. See widget-after-wipe-offline.png/tree.json. This supplements
the cache-source assertions with actual launcher invalidation evidence. Broader resize/other-click
coverage remains unverified; no unchanged bounds are called a successful tier switch.

## Final build and limits

The full relevant integration command passes in integration-checks.log:20s,622 tasks,6executed and
616up-to-date. Current copied XML has **925 tests**,zero failures/errors/skips, including cached
results, not925 fresh executions. The earlier68 results are a subset, not added to925. Debug app
and test APK packaging were UP-TO-DATE after the final Android runs, so no different final binary
is silently attributed. Exact hashes are in f6-round1/tested-apks.json. Engine-doctor passes with
clean engine3.0.5; Room8, JSON1, trained barometer model and AnalogPolicy.OFF remain unchanged.

The parent restored animations on, airplane off, Wi-Fi/mobile data on, and shut down the owned
tablet. Device inventory confirms all AVDs off and no Device-panel target; physical devices unused.

Still unverified: process death, real GPS acquisition/fallback in these saved-place tests, physical
sensor behavior, complete live chat/provider/audio, every launcher/tier/click/graphics quality and
every race interleaving. Accessibility remains proportionate per user steering; no expanded
TalkBack/quantified-contrast prerequisite. Global C2 independent field accuracy and the controlled
final p95/memory comparison remain open/NV. No synthetic or emulator result closes those gates.
