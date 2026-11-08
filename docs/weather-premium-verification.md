# Premium extension verification, 2.1.2/code23

This extension follows the original quality recovery at `8e97f750d8ae23c15b80fd9c89c199d2e3ef14b6`. Original Q-R3 reviews cover that baseline only. The extension has its own independent review gate; publication is already authorized but follows that gate. Engine remains the clean pinned 3.0.5. Trained model, feedback interpretation, Room migration and normal selected-place persistence are outside this change.

## Acceptance

| ID | Required outcome |
|---|---|
| P1.sky | Upper cloud composition, no continuous gray Deck stripe, coherent clear/overcast/drizzle/front/night lighting, readable foreground, no forecast-triggered present rain or lightning, bounded shared rendering and motion lifecycle. |
| P2.widget | Real production RemoteViews fit supported layouts; useful foreground data and empty/stale/missing states; bounded bitmap ownership; cache-only acquisition; correct destination and place on taps. |
| P3.reliability | Persisted local history does not wait for GPS/weather; Room updates without manual refresh; rejected/silent/empty sensor attempts, cancellation and restart ownership covered; absent sensor does not disable weather refresh. |
| P4.regression | Preserve Home/details/Fluid glass, original source clocks, missing/conflict semantics, model/feedback/schema and principal-app data. |
| P5.delivery | Pertinent JVM/device checks and exact APK provenance, clean engine-doctor, release signing/identity, three independent scoped Sol-high reviews with all mandatory scores >=8 and no blocker. |

## Evidence boundaries

Source changes and existing artifact logs are independently inspectable; test success is not proof of weather accuracy. Three reviewers receive the same frozen input and do not read each other's verdicts. Averages cannot compensate for a failed mandatory criterion. Initial review plus at most three correction rounds.

The cloud atlas and contour/noise experiments were rejected and removed. `SkyPainters.kt` uses the previous soft multi-pass Cumulus language, upper-sky coordinates and stratified groups. `WeatherScene` prepares matching painter geometry off the UI thread and retains visibility/lifecycle/motion gates. `SkyAtmosphereEvidence` is a shared cache-only adapter, with original clocks, matching coordinates and separate present-precipitation guards. The actual tablet partly-cloudy capture is `premium-tablet-upper-clouds.png`.

Synthetic artwork: `premium-cloud-preview-build.log` succeeds (25s, seven executed tasks). The nine-case x four-phase x three-quality `premium-upper-cloud-matrix` rendered successfully. Executable STATIC pixel equality, no-flash and malformed-input assertions passed. Root inspected day/night sheets, including pale drizzle and dark-front cases. These are desktop artwork checks, not Android GPU measurements or observed meteorology.

## Phone evidence before resource handoff

Only `dev.pampa.fluidweather.debug` was used on Samsung SM-S931B. Its principal application/data and global settings were left intact. At the user's request the phone was released after the last completed runner and the designated priority thread notified; subsequent checks use the physical SM-X710 tablet only.

- `premium-phone-widget-host-test-4.log`: 64/64 distinct production Glance -> RemoteViews -> actual framework layout cases; 54.627s, terminal -1. Four exact sizes, EN/IT, font1/1.5, empty/stale/current/missing. Earlier attempts failed compact clipping and long-condition fit; the final layout retains painted-glyph/ancestor/overlap checks and allows two-line condition text. The host does not prove launcher negotiation or clicks.
- `premium-phone-startup-test-5.log`: 1/1, 3.363s. Real MainActivity/graph/Room, GPS denied with no GPS weather cache, seeded owned SYNTHETIC pressure/history, then Room-driven updates without refresh. No network setting was changed. Back uses the production key path because the close header can be offscreen. Earlier ownership/navigation failures are not counted as passes.
- These runs preceded later visual refinements. The hydration/sensor implementation remains unchanged. The exact phone binary was not preserved; no later binary is labelled phone-tested. This is cold Activity evidence, not process-death or multi-day collection evidence.

## Current integrated evidence

`premium-main-integration.log`: Debug/test APK and four relevant JVM modules succeed, 36s; 584 tasks, 38 executed, 38 cached, 508 up-to-date. XML inventories include reused results and will be recorded with the final candidate. `premium-engine-doctor.log` captures all output streams and reports clean pinned engine 3.0.5 with all configured modules.

`premium-tablet-regressions.log`: 144/144 distinct methods passed on physical SM-X710, 208.193s, terminal -1. This is 80 original quality/layout cases plus 64 real RemoteViews host cases, not 144 full-application journeys. Tested app/test bytes are preserved under `premium-tablet-tested/` with source `fa6ddd3bc174ac152ff562b441667f2893899fbc` and SHA256 metadata. The later navigation plumbing preserves these sky/layout sources; its own integrated runtime check follows separately.

Canonical JVM inventory after the first main integration: core-ui58, sensor57, home76, widget51; 242 results, zero failures/errors/skips, including reused outputs. Widget place-target tests add two later core-ui cases. A release incremental Kotlin compile initially failed to resolve an existing `sensorRuntime` extension. Root's first nonincremental retry had a PowerShell argument quoting error and never compiled. The correctly quoted full module compilation then succeeded (35s, 30 executed tasks), without changing sensor source to hide the build-state issue. Final release integration and navigation outcomes are appended below before freezing. No new review score is assigned here.

## Limits kept open

## Final candidate execution

- `premium-final-build.log`: Debug, Android test and signed Release succeeded in 4m15s; 1,495 tasks, 142 executed, four cached, 1,349 up-to-date. Release optimization accounts for the longer build. The Debug-only RemoteViews host is absent from the release manifest. Package `dev.pampa.fluidweather`, version2.1.2/code23, ARM64+ARMv7; ZIP integrity passes and the signing certificate matches stable2.1.1.
- `premium-navigation-back-build.log`: test-only rebuild, 34s, 537 tasks/four executed. `premium-tablet-navigation-passed.log`: two distinct methods passed in7.442s, terminal -1, on the final production Debug APK. URI identity/legacy compatibility plus real MainActivity/graph/cache delivery, consecutive identical Hourly links, root Home and deletion fallback are checked. These are separate from the earlier144-method binary; counts are not described as146 methods rerun on the final binary.
- The initial navigation run failed its tutorial precondition (no fixtures created). The subsequent Compose-based attempt failed Activity closure and masked the original assertion; it is not a runtime pass or a diagnosed product crash. Native visible-window observation independently confirmed the saved Hourly data, then its ACTION_CLICK close selector failed. The final run uses the production Back key, preserving place/data/repeat/deletion assertions. A known failed-run fixture was recovered through an explicit ID after validating SYNTHETIC UUID ownership and exact coordinates. Cleanup now runs even if closure fails, restores Debug tutorials/selection and preserves the primary failure.
- The tablet Wi-Fi was temporarily disabled for the offline navigation fixture and restored to its previously enabled state immediately afterward. Principal application data, permissions and appearance were not changed. No phone commands occurred after handoff.
- Current canonical inventory: core-ui60, sensor57, home76, widget51 =244 XML results, zero failures/errors/skips, including reused outputs. These are not244 new tests in each build. The two new place-target JVM cases are included in core-ui60.
- Final APK sizes, SHA256, certificate and source identities are frozen separately in `candidate-apks.json`. Production release inputs did not change during the later Android-test-only refinements. The reviewed commit includes those test/docs refinements; no Gradle source-commit stamp is embedded in the APK.

## Remaining limits

Independent field accuracy, a matched whole-app p95/memory comparison, process death, multi-day sensor continuity, OS exact-alarm delivery on other OEMs, live IA/audio and complete launcher/resize coverage are not established. PendingIntent existence cannot prove a future alarm is armed; errors are now reported and existing recovery paths remain. No new physical-sensor continuity claim follows from short tests. Full TalkBack/contrast certification is not a prerequisite for this proportionate quality correction.
