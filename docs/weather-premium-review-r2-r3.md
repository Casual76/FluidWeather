**CORRECT — one high-severity integration defect blocks approval.**

Reviewer: **R3, GPT-6.1 Sol, high reasoning**  
Version: **Premium-R2-f5d86638ae35**  
Reviewed HEAD: `3cc7d6c32f07cc01471e28d00d60bbdb1b50d801`

| Criterion | Score | Decision | Evidence and limits |
|---|---:|---|---|
| P1.sky | 8/10 | PASS | Inspected current tablet PNG and historical day/night matrices. Upper soft clouds, no continuous Deck stripe, readable foreground. Shared temporal adapter and lifecycle/motion gates inspected in source. GPU performance remains NV. |
| P2.widget | 8/10 | PASS | Widget/painter sources unchanged from frozen R1. Inspected compact English stale PNG; valid time and download age fit. Archived R1 framework-layout coverage and current navigation evidence support the scoped result. Launcher negotiation remains NV. |
| P3.reliability | 6/10 | CORRECT | Independent hydration preserves failure versus empty correctly. Ordinary Home evaluation now propagates failures without catching them at its consumer boundary. |
| P4.regression | 7/10 | CORRECT | Legacy default consumers and numerical/model/schema sources remain unchanged. The new unhandled Home failure is a regression in the principal Home flow. |
| P5.delivery | 8/10 | PASS, own portion | Frozen files/APKs verified; archived build, signing, doctor and test accounting inspected. Collective three-report gate remains **OPEN** for root. |

**F1 — High: strict evaluation escapes Home’s coroutine instead of preserving displayed state.**

Location: [HomeData.kt:348](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/HomeData.kt:348>).

Home calls `evaluate(..., failOnLocalReadError = true)` directly inside `withContext`, within its `LaunchedEffect`/`coroutineScope`. There is no surrounding failure handler. A local acquisition exception now escapes this effect instead of reaching a state-preserving merge boundary. This exposes Home to an unhandled coroutine exception and application termination; the remainder of loading, including clearing refresh flags at line 408, is bypassed.

This is a **source-established failure path**, not a device-reproduced crash.

Minimal reproduction for root:

1. Open/resume GPS Home with barometer capability and usable coordinates.
2. Make `PressureRepository.samplesSince` throw an ordinary `IOException` during the ordinary weather-load evaluation.
3. The strict API propagates it through Home’s unguarded call.

The new Android test already establishes that this API throws the original exception, but catches it inside the test using `runCatching`. It never exercises the Home consumer. `HomeLocalHydration` correctly catches ordinary failures separately, so its passing tests do not cover this path.

Minimal fix: catch ordinary evaluation failures at this Home call, preserve the current local fields, continue the remaining load, and clear loading/refreshing normally. Continue propagating cancellation. Keep successful empty results as replacements. Add a regression through the ordinary consumer boundary.

**Executed read-only verification**

- Read both required skills, verification document and pertinent hydration/widget contracts. No reviewer reports were read.
- Verified all **833 files** against both disk and ZIP: zero size/hash mismatches.
- Verified manifest digest `f5d86638ae35b5f9e782b04dbbb7712ba5e64ed5fa85ac50377d9072db937894`.
- Verified ZIP SHA256 `147740fa59656a8c3c72bb48e6a8025195cf4b69be7e3cab7e8967bb0eef187a`.
- Verified external APK sizes, SHA256 and ZIP integrity:

| APK | Bytes | SHA256 |
|---|---:|---|
| Debug | 73,532,432 | `79a8b65cd1c0c7ee6842a86d76d8af419a86d41e8c70d417cbd4f3b85eb9a1aa` |
| Android test | 1,323,501 | `b23d2066499b33583307472a257438c4c80ccb0cdf3e7a4c59938d1753fd6827` |
| Release | 25,012,594 | `07c73c425eaa99fe27a8cffecb55c22493fcad781ddfe65d57238a0699f19b50` |

Production/test content matches the declared production and integration commits after checkout line-ending normalization; the nested engine identity was verified separately. Engine HEAD matches `a055257fc4b559bee54b9ce114f265bcd237adfd`, with clean status.

Release DEX inspection found no `WeatherWidgetHostActivity` name; its manifest declaration exists only in debug source and is absent from archived Release manifest output.

Final verification again found zero frozen-file mismatches, unchanged reviewed HEAD and clean main status. No files were created or modified; no builds, test runners, devices, network, providers, secrets or subagents were used.

**Archived evidence inspected**

- Current Android: **3 API methods / 0.349s**, plus **2 navigation/URI methods / 7.331s**, terminal `-1`.
- Historical R1: **64 host-layout + 2 navigation methods / 100.645s**. These are not current executions.
- XML inventory independently totaled **587**, with zero failures/errors/skips: weather286, AI56, UI60, sensor57, Home77, widget51.
- Release build: successful, **1m59s**. Weather/AI/Home JVM tasks were cached; UI/sensor were up-to-date; widget task executed.
- Archived doctor passes pinned engine3.0.5.
- Archived signing verification reports valid **v2**, package `dev.pampa.fluidweather`, version2.1.2/code23, ARM64/ARMv7, certificate SHA256 `825738500ce9605429ffba3c4019228f992f2c37d1b70a2230a7ce2c25637e43`. Its certificate matches archived R1 evidence. Previous-stable equality is metadata-reported, not independently rechecked against a stable APK here.

**Remaining NV / evidence boundaries**

Current tablet screenshot shows unavailable forecast placeholders; it does not prove cached-weather startup recovery. Phone64+1 evidence remains historical, with its exact binary absent. The generic implicit URL smoke remains a failed unsupported operation; explicit-component navigation passes.

Release-device execution, physical sensor continuity/accuracy, process death, multi-day collection, OEM alarm delivery, live AI and whole-app GPU/performance comparison remain NV. No additional accessibility certification prerequisite is imposed.

**Root disposition: correct F1 before approval; collective delivery gate remains OPEN.**
