# F3 integration verification

Scope: shared home brief, compact rain hierarchy/timeline, retained detail tiles/order, sky and
motion. This is presentation/integration evidence, not minute-level weather accuracy evidence.
F2 remains approved independently; final C2 field and C6 matched performance gates remain NV.

## Parent integration

- Matching snapshot/context timezone fills an unknown place timezone before presentation. Foreign
  snapshots cannot donate a timezone. A focused controller test covers both cases.
- Approximate radar time buckets preserve the full source resolution, including when the lower
  edge has just elapsed. Time passing does not improve prediction precision.
- Source details are expandable through a Fluid Plain button. Compact source age and essential
  limitations remain visible; full original intervals/download times and timeline accessibility
  descriptions remain available. No new full-screen glass capture or engine source change.
- `FluidWeatherTheme` now installs `FluidMotionPolicyProvider`. Previously all consumers saw the
  fallback duration scale 1. The new Android test supplies Compose duration scale 0 and verifies
  the actual app theme exposes reduced motion and disallows decorative motion. The sky already
  gates frames and color transitions on this policy, RESUMED, quality and header visibility.

## Builds and tests

- `f3-integration-checks-compact.log`: relevant suites and debug/test APK build pass. Current XML
  contains home23, UI40, weather243, strings9 =315 results; zero failure/error/skipped. Unchanged
  suites may be UP-TO-DATE; these are results for the current source, not 315 newly executed tests.
- `f3-motion-build.log`: final debug and Android test APK build succeeds after the root motion fix.
- `tablet-compose-motion-tests.log`: 8/8 actual Android15 tests pass: six overview tests (360/411dp,
  unknown/conflict, 2x font, source expansion), one app-root motion test, one radar singleton test.
- `phone-compose-motion-tests-retry.log`: the same 8/8 tests pass on actual Android17, same APKs.
  This is a rerun after an infrastructure interruption, not a new source correction.
- Engine doctor succeeds with engine3.0.5; engine sources and production dependency versions stay
  unchanged. `versions-app.gradle` pins the test-only Espresso dependency to3.7.0.

## Device method and actual tablet checks

Devices were opened through the Device panel, one emulator at a time. All app launches, input,
settings and captures used the returned bound agent-device command. adb was limited to install,
the project's AndroidJUnitRunner and diagnostics. Physical devices and unrelated AVDs were untouched.
The serial emulator-5554 was reused: names/platforms, rather than the serial alone, identify captures.

Pampa_Tablet Android15, 2560x1600: fresh unavailable/GPS and real Roma model-only states captured.
The principal qualified rain answer, transition limitation and compact evolution appear in the
first viewport. Sources expand/collapse on the actual app and in instrumentation. Hourly detail
opens and returns (`tablet-hourly-open-tree.txt`); its unknown-provider state is preserved.
Daily/precipitation were reordered, the changed order survived process relaunch, then the default
HOURLY/DAILY/PRECIPITATION order was restored and observed again. Known hidden nowcast retention
and normalization are covered by JVM tests, not by this sensorless emulator.

System animations were turned off; the app remained usable (`tablet-motion-off.png`). System font
was set via agent-device to accessibility-extra-large; readback reported platformValue2.0. The main
answer and timeline are readable and daily/precipitation become reachable by a pan, without header
snap (`tablet-font-200*.png` and trees). Default text category large and animations on were restored.
`tablet-final-home.png` and tree show the final normal configuration. No full TalkBack session or
quantitative rendering/memory comparison was performed here.

Medium_Phone Android17, 1080x2400: the final unavailable state and real Roma model-only state were
captured after installation of the motion fix (`phone-final-unknown*`, `phone-final-rome*`). The
main qualified answer, transition limitation and compact timeline are visible above the first
detail tile and the floating chrome. These final captures supersede the earlier pre-compact
`phone-rome-initial` presentation evidence. The real forecast is not an independent observation.
The selected place resets to GPS on process restart by existing session-only design; saved Roma
and tile order persist. Both emulators were shut down after QA; no device is needed for review.

## Infrastructure issues and limits

An initial build found missing pre-existing top-level string helpers despite their class files
being present. A module-only core-strings clean/no-build-cache recompile restored Kotlin metadata;
the unchanged helper API then compiled. Initial failed and successful logs are kept separately.

Initial Android17 overview tests failed before assertions in Espresso's reflective
InputManager.getInstance path; wiring passed. The official AndroidX release notes document the fix
in stable Espresso3.7.0: https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0 .
The failed test XML/log are retained under failed-android17, separate from final successful runs.

After the compatibility fix, the first Android17 instrumentation attempt terminated before tests
with `shortMsg=Process crashed`. The crash buffer was empty; activity exit-info records a package
LOW_MEMORY termination at the time of that attempt. This does not prove a source-level crash.
The app was reopened and used successfully for final captures; one bounded retry with identical
APKs then passed all8 tests in15.086s. Keep the failed runner log and exit-info alongside the final
success. Do not count the interrupted attempt as a completed suite or hide the resource limitation.

A later emulator boot failed with "Could not communicate with device support"; another startup
exited3221225477. After refreshing discovery and panel cleanup, only our verified orphan Medium_Phone
VM processes were stopped. The tool-advised tablet diagnostic launch was then attached through
Device tools. No other session/process was stopped. Logs preserve the failed attempts. A subsequent
normal Device opening of Medium_Phone succeeded after the tablet was shut down.

Data lifecycle limitation for F6: the existing upstream WeatherSnapshotRefresher does not yet use
DataWipeGuard. Home rejects pre-wipe emissions using generation/watermark, but this does not prove
upstream persistence cannot repopulate after wipe across composition recreation. Track and fix in
the cross-cutting phase. Same-fetchedAt StateFlow conflation is rechecked after IO and within60s.

Daily's pre-existing fixed-width day label can wrap on the tablet; full large-font detail/TalkBack
checks remain part of F6. No current synthetic weather case or emulator observation closes C2.
