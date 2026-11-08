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

Three reviewers will inspect the final frozen version independently. Initial review plus at most three correction rounds; scores, evidence, mean/minimum and open problems will be recorded here. No score assigned yet.

## Explicit limits

Field forecast accuracy, controlled whole-app p95/memory comparison, process death, multi-day sensor continuity, exact-alarm delivery on other OEMs and live AI/audio are not established. PendingIntent existence still cannot prove an armed future exact alarm; rearm failure is reported and existing health recovery remains. No new accuracy, continuous-collection or broad hardware claim follows from short device tests.
