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

Three fresh independent Sol-high reviewers completed the initial round. Initial review plus at most three correction rounds remain the workflow; correction1 is underway, with two further rounds available afterward if needed.

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

## Explicit limits

Field forecast accuracy, controlled whole-app p95/memory comparison, process death, multi-day sensor continuity, exact-alarm delivery on other OEMs and live AI/audio are not established. PendingIntent existence still cannot prove an armed future exact alarm; rearm failure is reported and existing health recovery remains. No new accuracy, continuous-collection or broad hardware claim follows from short device tests.

## Subsequent execution and handoff

- Production RemoteViews measurement first found clipped compact/short cells and large-font footers. Layout now prioritizes icon/temperature/range, uses stale age in the condition slot of short wide cells, budgets large-font typography and allows a two-line long condition. The small square has8dp padding; larger-font wide cells use reduced vertical padding. Assertions still check every painted glyph, clipping ancestor, overlap and essential ellipsis. Only the long place name may intentionally ellipsize.
- The first64-case phone host run had multiple failures. The second and third each passed63/64, exposing respectively the compact stale-age clipping and a long Italian condition's footer margin. `premium-phone-widget-host-test-4.log` finally records **64/64**,54.627s, terminal-1. These are distinct cases, not additive to prior attempts.
- `premium-phone-startup-test-5.log` records **1/1**,3.363s: real MainActivity/graph/Room, denied GPS/no GPS cache, persisted raw pressure and history, then Room-driven history/pressure update without refresh. No network setting was changed. Earlier attempts included UiAutomation ownership collision and native navigation deadline failures; only the final method is a pass. Back uses the production key/dispatcher path rather than a scrolled-away close header. It is cold Activity proof, not process-death proof.
- Phone tests preceded the final purely visual cloud-layout refinements. Reliability/Home hydration/sensor code is unchanged afterward. No claim is made that the exact later APK was rerun on the phone.
- At the user's request, SM-S931B was released immediately after the completed runner, and the designated priority thread was explicitly notified. No further phone commands are authorized for this task until coordinated again. Remaining QA uses the physical SM-X710 only.
- The user then accepted the individual soft clouds as the base and requested removal of the gray stripe. The Deck pass is removed for overcast/wet cloud scenes and replaced with stratified volumetric-looking groups; final build/visual checks follow this feedback. See `weather-cloud-experiments.md` for rejected prototypes.
