# F6 correction lifecycle integration tests

Parent-integrated test contract. The original implementer wrote two tests without execution;
the parent compiled them, diagnosed synchronization, added a third regression and corrected
the production recovery defect it exposed. See weather-f6-round1-fixes.md for execution evidence.
This document does not assert phase approval or field/performance accuracy.

## Preconditions and impact

Use the parent's owned Pampa_Tablet with the QA debug app. Before starting the runner the parent
must enable airplane mode, disable Wi-Fi/mobile data and disconnect VPN/other networks using its
bound Device command. Tests only READ Settings.Global and ConnectivityManager: airplane mode must
be on, activeNetwork null and allNetworks empty. Missing permissions/preconditions fail; no skips,
shell commands or network requests are introduced by the fixture. ACCESS_NETWORK_STATE comes from
the existing engine-net manifest. Production background attempts may run and fail offline.

The cached release configuration must permit MainActivity's normal Home. A gate fails explicitly;
tests never alter remote configuration. Onboarding is temporarily completed through OnboardingStore
and restored in finally/After, together with the previous session selected ID. Only newly created
reserved places -6601/-6602 are removed; collisions fail before mutations. Real graph.wipeAllData()
clears QA weather archives in setup (isolation), at the tested privacy boundary and in teardown
(remove synthetic archives even after assertion failure). Deleted
archives cannot be restored. Existing saved locations, routines and units are not edited. The wipe
test asserts those configuration values, engine settings and selected-place identity survive its tested wipe.
It does not read credentials or provider-key APIs. Actual barometer availability is compared with
SensorManager and logged; no absent-sensor claim is made when a sensor exists.
The parent's final Android15 run reports barometerAvailable=false; it therefore exercises these
whole-app flows without a pressure sensor, while physical sensor/calibration behavior stays untested.

## Test methods and runtime boundaries

1. `wipeInvalidatesRealHome_recreateReopenOfflineRejectLateRound_thenFreshRefreshRecovers`:
   seed actual graph store with current synthetic model wet weather; launch production MainActivity
   through ActivityScenario; assert actual Home rain
   headline, place and current temperature. A new disk-only WeatherSnapshotStore reads the exact
   real snapshot directory and preserves original source/download clocks. A production refresher
   using the SAME graph store and wipeGuard starts a delayed synthetic RoundSource. Invoke actual
   AppGraph.wipeAllData(), assert visible rain/current/timeline become unknown, then release the
   noncooperative source without cancelling its job. Its refresh must reject the originating epoch.
   Recreate the real Activity, then close/relaunch it offline; at each boundary assert unknown Home,
   absent memory/disk snapshots, no usable radar inputs and unavailable cache-only widget assessment.
   A new offline background attempt may persist explicit RadarSample.Unavailable; it is allowed,
   while any RadarSample.Ok fails. Missing snapshot must remain null, never an empty synthetic round.
   Explicit post-wipe acquisition through a real WeatherSnapshotRefresher with synthetic RoundSource
   must make dry/current model weather visible and persist its unchanged original context.
2. `activeHomeConsumesSameTimestampChanges_andRapidPlaceSwitches_acrossPauseResume`:
   actual active Home observes a changed current/rain payload with identical fetchedAt and source/
   download clocks. Assert changes token advances while updates timestamp stays fixed. Burst three
   A/B selection pairs through the production SelectedPlaceStore, settle on B and assert its identity,
   temperature and rain. Move real Activity to CREATED, write B content while stopped, resume and
   assert fresh B, then switch back and assert A's retained distinct content. No production hooks.
3. `activeHomeRecoversFromExplicitPostWipeRoundWithoutRecreation`:
   real Home wet -> actual graph wipe -> unknown and erased caches -> explicit synthetic dry round
   through the production refresher -> both real Home and cache-only widget source recover without
   recreating the Activity. A second wipe immediately followed by a wet round (without waiting for
   the empty state) tests recovery when clear/new-write emissions can be conflated.

Weather is explicitly synthetic MODEL evidence, using current System.currentTimeMillis with
separate original source/download clocks and valid known coordinates. Empty fused hours deliberately
exercise the shared current consumer independently of hourly forecasts. Widget assertions use the
actual FluidWeatherApp.snapshotStore and read-only cachedWeatherBriefs getter, production service/
evaluator and WidgetSharedRainFormatter. No replacement runtime/source interface or Home is used.
Assertions read the real Android UiAutomation window, including visible heading metadata, exact
localized current temperature/condition, place text and timeline descriptions. No replacement UI,
renderer or injected production test flag is used. Snapshot waits are bounded to 15s, methods to
120s; delayed-source entry/completion waits are bounded to 10s and
the noncooperative source itself to 45s. Assertion failures propagate.

The original Compose/Espresso reader stalled in Espresso.onIdle while the real graphics queue
continued rendering. Parent diagnostics captured that stack. Manual Compose clock control did not
resolve the native graphics-idle wait. Parent aborted those runs through the bound Device CLI;
their instrumentation Process crashed result records external termination, not a passed test or
an application crash diagnosis. Replacing only the assertion reader with native Android window
snapshots resolved synchronization. ActivityScenario and all actual graph/store/lifecycle calls
remain. The parent used animations off for this functional suite; this is not a motion-quality test.
The temporary diagnostic probe was removed. Three final methods passed without the recovery arg.

An optional explicit `recoverAbortedFixtures=true` runner argument can remove only exact matching
reserved fixture Place values left by an interrupted run; a mismatched value still fails. Normal
clean runs require no argument and retain the collision precondition. No general data reset is used.

## Known limits and production observation

Activity close/reopen is within the same application process, not force-stop/process death. Fresh
store instances prove snapshot disk reads without a second DataStore. No launcher widget/Glance
render, real provider acquisition, IA/chat, geocoder, GPS acquisition, field accuracy
or performance claim is established. GPS snapshot-coordinate fallback is not exercised by these
saved-place cases; no physical location or pressure sensor is required. Rapid switches intentionally allow StateFlow conflation; the
test verifies the settled context and separately observed A/B states, not every intermediate frame.
The selected-place control is driven via its production store, not the picker UI.

The parent reproduced the original HomeData recovery problem with the third test: the widget
received a valid new dry assessment while the still-open Home remained unavailable. HomeData now
accepts a matching guarded store read in the current wipe generation, rechecks generation after IO,
and re-arms its consumers. This also handles a conflated clear/new-write event. Caller cancellation
still propagates; an independently invalidated store read is ignored until another update.
No source/download timestamp is renewed to recover the display. A separate radar-positive/delayed radar
fixture is not injected: this integration proves post-wipe cache-only unknown but radar persistence
and late radar writes remain covered by the existing dedicated lower-level regressions.

## Parent commands (not executed by implementer)

From the repository root, build the app and test APK using the parent's existing build gates:

```powershell
.\gradlew.bat --max-workers=2 --no-daemon --no-watch-fs :app:assembleDebug :app:assembleDebugAndroidTest
```

After the parent installs both APKs and establishes the offline device precondition, execute only
this class with its owned emulator serial (runner invocation is permitted adb test-runner work;
all UI/device setup remains through the parent's bound Device command):

```powershell
adb -s <owned-emulator-serial> shell am instrument -w -r -e class dev.pampa.fluidweather.WeatherLifecycleIntegrationTest dev.pampa.fluidweather.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Verify the actual applicationId/testApplicationId from the built APK before substituting the runner
component. Capture method counts, failures and terminal instrumentation result; three written methods
are not passed tests. Parent restores device connectivity after the run even on failure.
