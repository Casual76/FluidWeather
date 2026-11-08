**R1 technical/concurrency review: CORRECT**

Version **Premium-R0-f1d8bb00bd6e**  
Reviewed HEAD: `c6fe9385271d619277a3205c860e7a9b6c9bb03b`  
Baseline: `8e97f750d8ae23c15b80fd9c89c199d2e3ef14b6`

One medium-priority presentation regression requires correction. No new blocking concurrency defect was established in the inspected paths.

| Criterion | Score | Gate | Assessment |
|---|---:|---|---|
| P1.sky | 8/10 | PASS | Upper cloud composition and shared evidence/rendering boundaries are supported. |
| P2.widget | 7/10 | CORRECT | Layout, cache acquisition and navigation are supported; forecast fallback loses its qualification. |
| P3.reliability | 8/10 | PASS | Independent local hydration, sensor failure recovery and ownership are supported within the recorded limits. |
| P4.regression | 7/10 | CORRECT | Core contracts remain unchanged, but widget forecast/current semantics regress. |
| P5.delivery | 8/10 | OPEN | Artifact provenance and archived checks are supported; the collective three-review gate remains unverified here. |

The mandatory threshold is **not met**. An average cannot compensate for P2/P4.

**Finding R1-01 — Medium: hourly forecast fallback appears as current weather**

Paths:

- [AppWidgetModel.kt:107](</C:/VibeCoded Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/AppWidgetModel.kt:107>)
- [WeatherWidgetContent.kt:60](</C:/VibeCoded Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/WeatherWidgetContent.kt:60>)

Reproduction derived from production code, **not executed**:

1. Supply a valid cached snapshot with a nearby fused hourly temperature/condition.
2. Have no usable typed current block, for example a legacy snapshot or expired current evidence.
3. Build and display the widget.

`AppWidgetModelBuilder` falls back to the hourly forecast and sets `usesHourlyForecast` and `forecastAtMillis`. The new content displays that temperature and condition without consuming either qualification field. The baseline displayed a localized forecast label and its valid time.

Consequence: a predicted temperature or rainy condition can occupy the current-weather hero without identifying it as forecast. Download age alone does not identify the forecast’s valid time. The separate sky precipitation gate remains conservative; that does not qualify the foreground text.

**Minimum fix:** when the hero uses the hourly fallback, retain a short localized forecast qualification, preferably with its valid time. This can replace existing secondary copy without restoring a provenance wall. Preserve it across compact, short and large-font layouts.

**Checks actually performed**

All actions were read-only. No files were modified; no Gradle, tests, devices, providers, network, secrets or delegation were used. Other reviews and prohibited ledgers were not read.

- Verified **all 769 manifest entries**, including bytes and SHA-256, against ZIP contents and local files: **zero mismatches**.
- Verified the embedded manifest equals the external manifest.
- Compared 691 tracked non-document/non-artifact files with reviewed HEAD, allowing checkout line-ending normalization: no content differences. Verified `engine/ENGINE_VERSION` separately against the engine repository.
- Confirmed current HEAD, clean working-tree output, engine commit `a055257fc4b559bee54b9ce114f265bcd237adfd`, and engine version **3.0.5**.
- Independently verified bytes/SHA-256 of all three current APKs and both preserved tablet144 APKs. `candidate-apks.json` matches the manifest’s external APK metadata.
- Checked ZIP integrity of the three current APKs.
- Confirmed no production-path differences between the recorded last production-input commit `00669fa…` and reviewed HEAD in the relevant app/modules.
- Parsed archived JVM XML: **244 cases across 35 XML files**, zero failures/errors/skips, including reused outputs.
- Visually inspected tablet Home, day and night atmosphere sheets, expanded current widget at font scale 1.5, and compact stale widget at font scale 1.5.

**Technical assessment**

**P1:** The inspected images retain soft upper clouds without the removed continuous gray Deck stripe or full-height cloud masses. Tablet foreground remains readable; day/night sheets distinguish pale drizzle and dark-front artwork. `SkyScenePainter` shares production drawing with widgets, confines cloud coordinates upward and disables lightning. Evidence adaptation checks original download/source clocks, spatial agreement and conflicts; future atmosphere can modify clouds without independently creating present precipitation. Painter preparation runs on Default, with matching-request publication and lifecycle/visibility/motion gates. These establish source and artwork behavior, not meteorological accuracy or Android p95 performance.

**P2:** Cache acquisition contains no GPS/provider call. Place/snapshot identity and input-token checks surround suspended reads. URI identity includes the place; explicit component targeting, repeated-request nonce, selection-before-detail gating and deleted-place fallback are present. Three bounded scene bitmaps serve four responsive layouts. The inspected widget PNGs support useful hierarchy and proportional readability. R1-01 prevents full acceptance.

**P3:** Pressure collectors own distinct listeners and unregister through `finally`, including rejected/throwing registrations. Silent single/burst acquisition is bounded. Empty supported-sensor passes request worker retry; cancellation propagates. Unsupported and ordinarily failing sensors still reach the weather hook. Collection is serialized through the shared engine.

`SurveillanceLoop` owns a Job, rejects duplicate active starts and runs completion cleanup after failure/cancellation. The service tracks the latest start ID and ends empty sessions.

Home local hydration is a separate RESUMED consumer, independent of GPS/weather loading. Room invalidation, calibration transitions and minute polling trigger reads. Publication checks cancellation and generation after suspended work. Saved places and unsupported hardware remain excluded. These paths are supported by source and archived helper/integration evidence; physical sensor continuity and full service lifecycle recovery are not newly demonstrated.

**P4:** No baseline changes were found in the inspected model, feedback, schema or shared weather/cycle modules. Engine remains unchanged. Source clocks are not refreshed merely by local hydration, and stale place jobs have cancellation/generation guards. R1-01 is the identified semantic regression.

**Archived evidence and limits**

- **144 tablet methods:** historical `fa6ddd3…` binary, with preserved APKs verified. Layout/painter source comparison supports reuse; the later widget-content change is navigation plumbing.
- **Two navigation methods:** archived success on current production Debug, separately from the historical 144.
- **64 phone widget cases + one startup case:** historical evidence only; no preserved phone APK. No current binary is certified phone-tested.
- **Release:** archived successful build, signing verification and package/version identity; current APK hash independently matched. Signature/manifest tool results were inspected, not rerun. Release was **not device-tested**.
- Generic implicit URL smoke failed because there is no matching VIEW filter. It contributes **no pass**; explicit widget navigation has separate evidence.

**OPEN:** collective independent-review approval, process death, multi-day sampling, controlled whole-app p95/memory, field accuracy, comprehensive launcher negotiation and OEM alarm delivery. These limits are not newly imposed acceptance requirements. Full TalkBack/contrast certification is likewise not added.

**Disposition:** correct R1-01, then re-check its foreground semantics and affected widget layouts. P1 and P3 pass this scoped review; publication approval is not established by this report.
