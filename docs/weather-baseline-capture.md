# F0 — baseline actually collected

Baseline application: clean commit `2445bd80ba59900bf50a0ce8f9d62f9e399fc53c`, debug 2.0.2 (code 20), Engine 3.0.5. Application behavior was unchanged during capture; F0 additions are JVM validation utilities, documentation and a saved-metrics parser.

Evidence directory: `artifacts/weather-improvement/baseline/`. APK SHA-256: `AC2CA52B863059A55CF9DFE8D690643E764B163ADC52265EE317EDEB93916AFF` (APK is git-ignored but preserved locally).

## Devices and flow

- Medium_Phone, emulator-5554, Android 17, 1080x2400 portrait. Fresh debug install, default appearance, English/US units; simulated Rome coordinates. Initial missing-data/loading state and onset of barometer calibration retained. Final forecast uses saved Roma rather than claiming the emulator fix is a real location.
- Pampa_Tablet, emulator-5556, Android 15, 2560x1600 landscape. Fresh debug install, default appearance, English/US units, location permission skipped, no barometer; saved Roma selected through the app search.
- The device command reports system text size **large** on both emulators; candidate comparisons must use this same size. Standard-size and 200% accessibility checks are separate scenarios.
- Open/installation, permissions, location injection, snapshots, taps, swipes and screenshots used the device panel's bound agent-device commands. adb was used only for logs, raw app-cache reads and gfxinfo/meminfo.
- Onboarding was traversed, then location search and saved-city selection. Foreground home with provider data was captured on both devices. Saved JSON snapshots preserve each device's actual input: the two live downloads differ and must not be compared as identical weather inputs.
- Screenshots and full accessibility trees are preserved alongside the provider snapshots. No screenshot alone proves TalkBack, haptics, sensor accuracy or every weather state.

## Rendering and memory

After onboarding and interaction warm-up, collect three runs per device. Each run: gfxinfo reset, six vertical ping-pong swipes with 500 ms pauses, gfxinfo framestats and meminfo. Phone gesture: (540,1800) to (540,650); tablet: (1280,1200) to (1280,450). On the tablet the source screen may already fit; this records that fact rather than inventing a scrollable overflow. Raw files are retained; the phone was collected before tablet capture and both emulators were booted by the tablet runs.

`python tools/weather_metrics.py artifacts/weather-improvement/baseline` reproduces `metrics-summary.json`. Frame p95 uses nearest rank on FrameCompleted minus IntendedVsync for Flags=0, excluding invalid/sentinel timestamps only. Slow frames are retained. PSS is read in KiB from each raw meminfo sample.

These are initial **debug emulator** baseline samples, with substantial run dispersion and differing host load during setup. They do not close C6's final <=10% matched comparison. F6/F7 must repeat baseline and candidate under matched device/load/settings/input conditions, using the preserved old APK and payloads. Missing controlled before/after evidence remains NV; neither emulator timing nor synthetic fixtures establish real-world rain accuracy.

F0 correction round 1 hardens the saved-metrics parser: missing/unclosed PROFILEDATA blocks, malformed or truncated rows, no measurable frames, absent/empty directories, mismatched gfxinfo/meminfo pairs and missing PSS now fail explicitly without a JSON success report. Stdlib regression suite: `python -B -m unittest discover -s tools -p test_weather_metrics.py`. Valid baseline metrics must reproduce unchanged; slow outliers are retained. Original parser/document versions are archived in `artifacts/weather-improvement/versions/F0-3cd11fc7a76e/`.

## Verified checks

- Original `:app:assembleDebug :core-model:test :core-weather:testDebugUnitTest :core-ai:testDebugUnitTest :feature-home:testDebugUnitTest`: BUILD SUCCESSFUL, 288 tests, zero failures/errors.
- New validation test suite: BUILD SUCCESSFUL (`validation-tests.log`).
- Full `:testbench:test` plus the new CLI fixture evaluation: BUILD SUCCESSFUL, 256 tests, zero failures/errors (`testbench-tests.log`).
- CLI reported evidence SYNTHETIC, 10 slots/8 scored; its example error counts test the evaluator and are not measurements of application accuracy.
- Source contract URLs and the reproducible future field protocol are in `weather-baseline.md`. Field observations have not been collected; C2 stays open until F7.

The frozen F0 manifest identifies all current source, documentation and evidence files. Review C1.scenarios, C6.baseline and C7.reproducibility for this phase; full C6 and C2 are later gates.
