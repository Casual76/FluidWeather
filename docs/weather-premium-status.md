# Premium sky, launcher widget and local reliability

This is a separate, user-authorized extension after the original Q-R3 presentation reviews. Those reviews passed their original scope; they do not approve this extension. Version remains 2.1.2/code23, unpublished. Engine 3.0.5 and the trained model/report semantics remain unchanged.

## Scope and user decisions

- Local history must load independently of GPS and provider work. Supported sensor registration rejection and empty/silent attempts must recover; absent hardware must not disable background weather acquisition.
- Foreground copy stays concise. Sources/method remain available through disclosure; no repeated obvious forecast disclaimers.
- Launcher widgets put place, temperature, conditions, range and bounded hourly/daily rows first, inspired by the user's iOS reference. Existing cache-only acquisition remains.
- The user rejected the generated cloud atlas on 2026-11-08: pasted-looking forms, excessive full-height coverage and insufficient volume. It has been removed from production resources. Restore the procedural visual language and keep cloud forms in the upper sky, with dynamic lighting/density.
- Keep the existing separation between rain and hourly blocks. The apparent blank gap was an invisible “Add a moment” action: give it a real Fluid glass button rather than removing/moving it. The temporary menu alternative was reverted before any build was delivered.
- Only FluidWeather Debug is installed/tested on the authorized physical Samsung SM-S931B. The principal app and global phone settings remain untouched. All emulators remain stopped.

## Implemented, awaiting integrated acceptance

- `HomeLocalHydration` reads cleaning/raw pressure/history/readiness as an independent RESUMED consumer, with Room signals, periodic rechecks, cancellation and wipe-generation checks. Successful empty reads clear expired data; failures preserve the last local state.
- Barometer registration failures are explicit; listener cleanup is owned by each flow. Silent bursts have deadlines; zero samples on supported hardware request existing worker backoff. Surveillance ownership can restart after completion/failure. Weather refresh still runs on absent/failing sensors, but cancellation cancels both paths.
- `SkyAtmosphereEvidence` is the shared local-input adapter for home and widget. It retains original download clocks, rejects mismatched/stale/duplicate context, permits valid radar WET without a model bundle, and never starts precipitation from a future front. Missing optical context preserves legacy condition rendering.
- Production `WeatherWidgetContent` is reused by a Debug-only host that composes and inflates real Glance RemoteViews. Four widget sizes, EN/IT, two font scales and four data variants have 64 written device cases. No acceptance is inferred from compilation.

## Executed evidence so far

Evidence lives under the parent workspace `artifacts/quality-2.1.2/`, outside Git.

- `premium-integrated-build-1.log`: successful initial integrated Debug/test build, 1m24s, 579 tasks (234 executed, 159 cache, 186 up-to-date).
- `premium-integrated-tests-2.log`: successful first shared-evidence JVM run, 48s, 232 tasks (24 executed, 1 cache, 207 up-to-date).
- Current canonical XML inventory after subsequent focused runs: core-ui57, core-sensor57, home76, widget51; zero failures/errors/skips. These 241 results include reused outputs, not 241 fresh executions in each log.
- The initial cloud correction build failed compilation for an incorrect `inset` overload. The next failed a new test's erroneous zero-cloud default expectation; the established CLEAR fallback is5%, not0%. Both corrections are explicit in the logs.
- The first host build caught an invalid zero-argument `UnitPreferences` fixture constructor. Replaced with the established METRIC preset in both Debug host and test. `premium-soft-clouds-host-build-2.log` then succeeded in36s, 555 tasks (15 executed, 540 up-to-date).
- `premium-phone-gps-denied-local-history.png/.json`: actual Debug cold Home, no location/weather, local readiness already showing1h3m. This is short real Debug history, not a13-hour collection claim.
- `premium-phone-soft-clouds-home.png/.json`: actual updated procedural Home, upper cloud bank and visible Fluid glass “Add a moment” action. Cloud realism remains under active user evaluation.
- `premium-phone-startup-test-1.log`: instrumentation aborted with UiAutomationService “already registered” before functional assertions completed. Not a pass or proof of a production crash. Device panel closed without shutting down the phone before exclusive instrumentation.

## Acceptance criteria for the new independent review

| ID | Required outcome |
|---|---|
| P1.sky | Upper-sky composition; coherent clear/overcast/drizzle/front/night states; actual foreground readability; no forecast-triggered present rain/lightning; bounded shared rendering and motion lifecycle. |
| P2.widget | Real production RemoteViews fit at supported sizes, useful data hierarchy, empty/stale/missing states, bitmap budget, cache-only ownership and meaningful click routes. |
| P3.reliability | Persisted local history shown without waiting for GPS/weather; Room updates consumed without refresh; failed/silent/rejected sensor attempts, cancellation and restart ownership covered; no sensorless-weather regression. |
| P4.regression | Home/details/glass remain coherent; no model, feedback, migration or principal-app changes; source clocks/unknown/conflicts preserved. |
| P5.delivery | Pertinent JVM/device checks, exact tested APK/source identities, doctor and signed release checks; three independent Sol-high reviews with each mandatory criterion >=8 and no blocker. |

Three fresh independent Sol-high reviewers completed the initial round and correction1. Initial review plus at most three correction rounds remain the workflow; correction2 is underway, with one further round available afterward if needed.

## Independent review round0

Version `Premium-R0-f1d8bb00bd6e`, HEAD `c6fe9385271d619277a3205c860e7a9b6c9bb03b`. Manifest769 files; ZIP SHA256 `045c0757062875e7d04afbd83d91cc40589ede210c07244620f295d7305b08e3`. All three critics checked sources, artifact integrity and actual images independently in read-only mode. Their full reports are `weather-premium-review-r0-r1.md`, `-r2.md`, `-r3.md`.

| Criterion | R1 technical | R2 visual | R3 widget/evidence | Mean | Minimum |
|---|---:|---:|---:|---:|---:|
| P1.sky | 8 | 8.5 | 8 | 8.17 | 8 |
| P2.widget | 7 | 7 | 6 | 6.67 | 6 |
| P3.reliability | 8 | 8 | 8 | 8 | 8 |
| P4.regression | 7 | 7 | 7 | 7 | 7 |
| P5.delivery | 8 | 8 | 7 | 7.67 | 7 |

Decision: CORRECT; no publication. P2/P4 fail the mandatory threshold and P5 collective acceptance is open. The mean cannot compensate.

Required: (1) the hourly widget fallback lost its visible validity distinction; (2) root Home for the same place did not dismiss an already open detail. R3 additionally identified an essential-text ellipsis assertion checking only line0. R2 noted low-priority sun glow behind widget header text. Correction1 retains the user's concise-copy requirement: show the fallback's valid time rather than repeating Model/estimate prose, preserve stale download age separately, deliver a consumable Home nonce, inspect every text line and apply a widget-only header scrim. Home cloud artwork remains unchanged from the accepted upper soft-cloud correction.

## Independent review correction1

Version `Premium-R1-73c919c3ff25`, reviewed HEAD `7834adf2ac96387e5745c6b3f16dac6fc1f26236`, production inputs `38323f9a9691db9ad5fea42831e79928c1bc5527`. All three independent Sol-high reports are preserved in `weather-premium-review-r1-r1.md`, `-r2.md`, `-r3.md`. They verified the same772-file manifest, external APKs and actual images without consulting one another. ZIP SHA256 `d953ec8f413c8ea6d446d39d845f12795fa4ab926645a21b187672f1c13f406c`.

| Criterion | R1 technical | R2 visual | R3 widget/evidence | Mean | Minimum |
|---|---:|---:|---:|---:|---:|
| P1.sky | 8 | 8 | 8 | 8 | 8 |
| P2.widget | 8 | 8 | 8 | 8 | 8 |
| P3.reliability | 7 | 8 | 8 | 7.67 | 7 |
| P4.regression | 8 | 8 | 8 | 8 | 8 |
| P5.delivery | 8 | 8 | 8 | 8 | 8 |

Decision: CORRECT; no publication. The four correction targets pass, but R1 found that the production `NowcastUseCase` catches local read failures before the hydration helper receives them, allowing successful-null/zero replacements to erase previous cleaning/readiness. P3 remains below8 despite the mean. Other reviewers' passes do not compensate. R2/R3 also identified stale historical wording in the host contract; it is updated in correction2. Optional residual celestial glow in one empty widget is nonblocking and the sky artwork remains unchanged.

Correction2 uses an optional strict acquisition mode at the actual production API boundary, enabled by Home's polling and ordinary load. Failures preserve displayed data; confirmed empty reads and wipe still clear it. A new three-method real API Android suite passed; native navigation repeated2/2. No numerical model, cleaner, readiness formula, schema, cloud painter or widget content changed. Signed build and fresh independent review follow before acceptance.

## Independent review correction2

Version `Premium-R2-f5d86638ae35`, reviewed HEAD `3cc7d6c32f07cc01471e28d00d60bbdb1b50d801`, production inputs `2d058c92ad92be38faa9470ccc45a1ca230f047a`. The three fresh Sol-high reports (`weather-premium-review-r2-r1.md`, `-r2.md`, `-r3.md`) independently checked833 frozen files, actual images and external APKs. ZIP SHA256 `147740fa59656a8c3c72bb48e6a8025195cf4b69be7e3cab7e8967bb0eef187a`.

| Criterion | R1 technical | R2 visual | R3 integration | Mean | Minimum |
|---|---:|---:|---:|---:|---:|
| P1.sky | 8 | 8.5 | 8 | 8.17 | 8 |
| P2.widget | 8 | 8.5 | 8 | 8.17 | 8 |
| P3.reliability | 6 | 6 | 6 | 6 | 6 |
| P4.regression | 8 | 8 | 7 | 7.67 | 7 |
| P5.delivery | 8 | 8 | 8 | 8 | 8 |

Decision: CORRECT; no publication. All critics identified the same high-severity missing boundary: strict ordinary evaluate could escape the Home LaunchedEffect. API tests deliberately caught its propagated exception and therefore did not verify the actual Home caller. Independent polling had its own correct boundary, so those helper passes did not compensate.

Correction3, the last permitted correction round, handles ordinary evaluation through cancellation-preserving `homeCatching`, merges local fields only on success, completes loading/refresh flags and continues auxiliary work after ordinary failure. Successful empty still clears. A real `rememberHomeState` Compose regression uses the production use case/cleaner and isolated acquisition, baseline and history stores; it exercises success, IO failure, retained state, remaining work, recovery, cancellation and confirmed empty. Its execution and signed build remain to be recorded before review. Default baseline persistence and geocoding are retained through narrow injectable constructor/callback seams, allowing this test to avoid changing real settings or making GPS/network requests. No new cloud/widget artwork or model/schema change is included.

## Independent review correction3 and scoped acceptance

Version `Premium-R3-f8af8d8afa54`, reviewed HEAD `e118fc81030b3cdd009706e87c562a7ca2246959`, production inputs `905f84edba6dfbf952373369c546219dbd247627`. All three fresh independent Sol-high reports are preserved as `weather-premium-review-r3-r1.md`, `-r2.md`, `-r3.md`. All852 manifest files, actual images and three external APKs were verified independently. ZIP SHA256 `affc6756b60b962c9047e936386a8f80989bdf7f80b3039452dc6b551cfea4ad`.

| Criterion | R1 technical | R2 visual | R3 integration | Mean | Minimum |
|---|---:|---:|---:|---:|---:|
| P1.sky | 8 | 8 | 8 | 8 | 8 |
| P2.widget | 8 | 8 | 8 | 8 | 8 |
| P3.reliability | 8.5 | 8 | 8 | 8.17 | 8 |
| P4.regression | 8.5 | 8 | 8 | 8.17 | 8 |
| P5.delivery | 8.5 | 8 | 8 | 8.17 | 8 |

Root decision: **ACCEPT for this scoped premium delivery**. Every mandatory individual score reaches8; all three reports are present and no blocker remains. Root closes the collective P5 gate on these inputs. No average compensates for failure. The last permitted correction round succeeds; no fourth round is started.

Actual Home caller/API4 methods pass in2.403s, native navigation2 in6.437s on the preserved current Debug/test APKs. Release and seven pertinent JVM tasks pass;620 XML results include reused outputs. Signing matches the actual downloaded prior stable APK; engine3.0.5 is clean. The widget64-layout run and unchanged sky captures remain historical and are not presented as fresh correction3 execution. Release is build/signature verified, not principal-device tested.

Low nonblocking observations remain explicit: the caller test does not gate a suspended auxiliary lookup to directly observe early refresh-flag completion, and its shared acquisition latch does not uniquely attribute cancellation to ordinary evaluation versus independent hydration. Source ordering and the separate actual API identity tests support the accepted behavior. These are future test-strength improvements, not established production defects, and no extra correction is made after freezing.

The existing explicit stable-publication instruction authorizes proceeding after this acceptance. Publication, remote asset/hash verification and catalog outcome are recorded separately after execution. Global field accuracy, matched whole-app performance and multi-day/other-OEM hardware continuity remain NV, outside this scoped acceptance.

## Publication outcome

Stable2.1.2/code23 published successfully on2026-11-08 under `stable-fluidweather-v2.1.2`, source `79a74070da25dd8b5e533af4e9d28d16947b1b80`. Non-draft, non-prerelease and latest verified. Downloaded APK bytes/SHA256 match the accepted signed candidate; stable app manifest and Pampa index/changelog verified. See [the publication record](releases/2.1.2-publication.md). This records completion of the separately authorized stable release, without changing the scoped review/NV boundaries.

## Explicit limits after delivery

Field forecast accuracy, controlled whole-app p95/memory comparison, process death, multi-day sensor continuity, exact-alarm delivery on other OEMs and live AI/audio are not established. PendingIntent existence still cannot prove an armed future exact alarm; rearm failure is reported and existing health recovery remains. No new accuracy, continuous-collection or broad hardware claim follows from short device tests.

## Subsequent execution and handoff

- Production RemoteViews measurement first found clipped compact/short cells and large-font footers. Layout now prioritizes icon/temperature/range, uses stale age in the condition slot of short wide cells, budgets large-font typography and allows a two-line long condition. The small square has8dp padding; larger-font wide cells use reduced vertical padding. Assertions still check every painted glyph, clipping ancestor, overlap and essential ellipsis. Only the long place name may intentionally ellipsize.
- The first64-case phone host run had multiple failures. The second and third each passed63/64, exposing respectively the compact stale-age clipping and a long Italian condition's footer margin. `premium-phone-widget-host-test-4.log` finally records **64/64**,54.627s, terminal-1. These are distinct cases, not additive to prior attempts.
- `premium-phone-startup-test-5.log` records **1/1**,3.363s: real MainActivity/graph/Room, denied GPS/no GPS cache, persisted raw pressure and history, then Room-driven history/pressure update without refresh. No network setting was changed. Earlier attempts included UiAutomation ownership collision and native navigation deadline failures; only the final method is a pass. Back uses the production key/dispatcher path rather than a scrolled-away close header. It is cold Activity proof, not process-death proof.
- Phone tests preceded the final purely visual cloud-layout refinements. Reliability/Home hydration/sensor code is unchanged afterward. No claim is made that the exact later APK was rerun on the phone.
- At the user's request, SM-S931B was released immediately after the completed runner, and the designated priority thread was explicitly notified. No further phone commands are authorized for this task until coordinated again. Remaining QA uses the physical SM-X710 only.
- The user then accepted the individual soft clouds as the base and requested removal of the gray stripe. The Deck pass is removed for overcast/wet cloud scenes and replaced with stratified volumetric-looking groups; final build/visual checks follow this feedback. See `weather-cloud-experiments.md` for rejected prototypes.
