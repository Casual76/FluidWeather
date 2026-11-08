# F6 integration verification

Pre-review integration record. No F6 acceptance is asserted until both independent reviews
are available. Historical F0–F5 snapshots remain immutable. Base HEAD remains
`2445bd80ba59900bf50a0ce8f9d62f9e399fc53c`; engine remains pinned to 3.0.5.

## Integrated behavior

The graph passes one DataWipeGuard through weather HTTP, snapshots, fusion, verifier, registrar,
radar rasters and radar assessment inputs. WeatherBriefService is constructed before nowcast;
the existing callback consumes its shared radarSample through RadarObservations.of(sample).
The trained barometer model and AnalogPolicy.OFF remain unchanged. Home and widget listeners
use snapshot content tokens, including writes whose fetchedAt did not change.

The widget receives only CachedWeatherBriefSource. It evaluates local original inputs with the
same evaluator, without radar/provider/GPS/model requests. It displays an absolute assessment
clock and original source/download clocks, coverage and limitations. It omits moving ETAs.
Shared current values are distinct from hourly forecasts. A collection makes source details
scrollable. Details, precipitation and nowcast preserve shared present evidence before their
missing-hour/model-history early returns. Missing samples remain gaps; available-sample sums
are qualified and do not imply complete accumulation coverage.

Additional parent integration fixes beyond the allocation handoffs:

- WeatherBriefService inherits the originating WeatherWipeEpoch across its application-owned
  loader job; an old callback cannot obtain a fresh post-wipe epoch. Two cross-path regressions
  exercise the real snapshot refresher callback and cache-only/brief consumption.
- RadarTileStore guards PNGs, empty markers, original download metadata and cached publication.
  It downloads outside the durable cache and all guard locks, then commits only under its captured
  epoch. Four new regressions cover late noncooperative downloads, markers, decode and old origins.
- RadarAssessmentCodec optional field callbacks are invoked explicitly; the original compile
  failure caused by DataInputStream.read shadowing the callback is retained in the first log.
- Widget tests exposed an older summary revision bug: normalization retained an ETA while removing
  its required horizon. Revision normalization now hashes absolute transitions separately and has
  stability/change regressions. Freshly reevaluated UNCERTAIN remains the same shared state across
  consumers; expired positive results do not gain renewed validity.
- Short timeline captions use one approximate minute label, with the real source resolution
  retained. Longer and repeated-DST ranges retain dates, offsets and zones.

## JVM, build and engine checks

`artifacts/weather-improvement/f6/integration-round3.log` records BUILD SUCCESSFUL for:

```
.\gradlew.bat --max-workers=2 --no-daemon --no-watch-fs
 :core-model:test :core-data:testDebugUnitTest :core-weather:testDebugUnitTest
 :core-strings:testDebugUnitTest :core-ui:testDebugUnitTest :core-ai:testDebugUnitTest
 :core-cycle:testDebugUnitTest :feature-home:testDebugUnitTest
 :feature-appwidget:testDebugUnitTest :feature-settings:testDebugUnitTest
 :feature-assistant:testDebugUnitTest :testbench:test
 :app:assembleDebug :app:assembleDebugAndroidTest
```

The current copied XML results contain 922 tests: model93, data33, weather286, strings23, ui40,
ai56, cycle32, home44, appwidget42, settings11, assistant6 and testbench256. No failures/errors/
skips. These totals include UP-TO-DATE results, not 922 new executions. The first compile failure
and second run's revision-invariant failure remain archived. Engine-doctor passes in
`engine-doctor.log`. No production dependency upgrade or engine edit was made.

## Android verification

Initial full run: 75 methods, 67 successful and eight Daily assertion failures. Diagnostic rerun:
56 methods, 48 successful and the same eight failures. The initial method-only filter matched
zero parameterized tests; its OK(0) is not counted as evidence. Logs are retained.

Diagnostics show Text semantics can reuse a paragraph laid out at maxWidth while Text measures
to its intrinsic width (e.g. paragraph640px, measured378px). The test now checks actual painted
line left/right/bottom bounds against the measured text box, permitting one pixel of rounding;
it retains height overflow, ellipsis and unsplit temperature-digit checks. No production text
width was changed to accommodate the assertion. Diagnostic messages retain text and constraints.

Final Android run: `tablet-tests-final.log` records **75 distinct successful methods**, no
failures, `OK (75 tests)`, 179.248 seconds and terminal instrumentation code -1. The 56 new
parameterized cases cover EN/IT, 360/800dp and 1x/2x production composables. The other 19 retain
summary, forecast formatter/parser, routine CRUD, rain overview, root motion and graph regressions.
They run on the Android15 tablet; simulated Compose widths are not full-app phone captures.

## Manual device evidence and limits

After the runner completed, the bound CLI refused the app relaunch with DEVICE_IN_USE, naming
session `t3-20e2e96497a0d89bda415f21`. Device_open returned the same bound command. One safe retry
with --wait10000 also refused after 10009ms, diagnostic `mw6s92eh-bb862c1c` (initial diagnostic
`mw6s8lrl-65b495cd`). No foreign session was reused or closed and no UI fallback was attempted.
The tablet was left running because it was in use by that other session; physical devices were
never used. Device CLI ownership is separate from adb's test-runner access.

The user then released the tablet and explicitly returned it to this task. Relaunch succeeded.
Actual manual evidence collected afterward:

- Initial GPS context is unavailable; selecting saved Roma shows its own current/model forecast.
  Relaunch returns to the existing GPS default without borrowing Roma data; selecting Roma again
  restores its context. This proves those traversed states, not an automated rapid-change stress run.
- `tablet-daily-200.png` and its tree show full weekdays/dates and complete temperature values in
  the production Daily tile at system fontScale2.0. The precipitation sheet opens from that tile
  area, retains the same source-qualified present, and scrolls to expanded original source/download
  clocks and future sample labels. See `tablet-precipitation-200.png`,
  `tablet-precipitation-sources.png` and its tree. Existing shared glass remains in use.
- A FluidWeather launcher widget loads cached Roma and scrolls from temperature/current through
  original provenance to hourly forecasts. `widget-small-top-tree.json` and
  `widget-small-scrolled-tree.json` show the collection bounds608x466px, changed viewport and
  distinct source/download/forecast-cache clocks. `widget-small-scrolled.png` records the view.
- Tapping the hourly row opens the real Hourly sheet. `widget-hourly-destination.png` and tree
  verify the destination. This is actual launcher routing, not a direct deep-link substitute.
- A shrink attempt leaves the widget at the same608x466 minimum bounds; `widget-resized-tree.json`
  therefore does **not** prove another responsive tier or successful resize. Header/nowcast clicks,
  other launcher/API versions and broader resize coverage remain unverified. Two add attempts were
  made during launcher QA; no unrelated widget was removed or edited.

The CLI needed the observed launcher's explicit `package/Activity` form; a package-only helper
launch failed, and a malformed fully qualified activity argument was corrected. One selector with
spaces was rejected before dispatch; JSON refs were used instead. Switching back from the launcher
after the successful hourly click initially gave insufficient foreground content; associating the
session with FluidWeather resolved it. These are automation diagnostics, not app crash evidence.

A direct implicit `fluidweather://open/settings` attempt was refused twice (including diagnostic
retry): `Activity not started, unable to resolve Intent`. The manifest does not advertise that
implicit link; actual widget intents supply an explicit activity component. No manifest expansion
was made solely to support this test route. Further UI checks stopped; no adb UI fallback was used.
Consequently actual in-app wipe/recreation, offline operation, rapid-change stress, other widget
clicks/tiers and a full glass-quality matrix remain unverified manually. Wipe/persistence, context,
cancellation and cache-only behavior are supported by the separately identified JVM regressions;
their coverage must not be misrepresented as those manual actions.

System text size was restored to large, animations restored on, and the owned emulator shut down.
Device inventory confirmed all AVDs off and no Device-panel target open; physical devices untouched.

Manual inspection found one mojibake legend separator in HomeRainOverview, replaced by an explicit
Unicode middle dot. `final-separator-build.log` passes the home JVM suite and app/test APK builds
after that one-character production change. The75-method Android run and screenshots precede
only this separator fix. `tested-apks.json` records those runner APKs; `evaluation-apks.json`
records the final rebuilt pair. No final binary Android rerun is claimed.

## User priority update

On2026-11-07 the user asked for proportionate accessibility work ("il giusto e l'onesto"). F6
therefore retains practical readability, reachable controls, existing EN/IT/font/layout regressions
and motion behavior; it does not expand into full TalkBack or quantified-contrast certification.
Those broader accessibility audits are not required for F6 advancement under the updated scope.
The separate original rendering/memory comparison and independent field-validation gates are
unchanged and remain open. Missing functional evidence is still reported explicitly.

## Boundaries

No minute-level field accuracy, full TalkBack, quantified contrast, matched p95/memory comparison,
physical sensor, complete live AssistantSession/provider/audio or multiprocess filesystem guarantee
is inferred from these tests. Global C2 and final C6 remain NV. The tests use synthetic fixtures
and fake providers. Provider/radar persistence has no multi-file transaction or fsync guarantee;
indefinitely noncooperative IO is not forcibly terminated. Current barometer diagnostic DTOs have
no originating issue timestamp, so the UI explicitly reports it unknown.

F6 review scope: C1.consumer-consistency, C7.wipe-persistence, C7.lifecycle and
C6.targeted-accessibility, as defined in weather-f6-contract.md. Missing mandatory phase evidence
remains open; broad final criteria are not silently converted to NA or passing scores.
