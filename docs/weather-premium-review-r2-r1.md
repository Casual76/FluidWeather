**CORRECT — one high-severity Home failure boundary remains.**

Version: **Premium-R2-f5d86638ae35**  
Reviewed HEAD: `3cc7d6c32f07cc01471e28d00d60bbdb1b50d801`  
Reviewer: **GPT-6.1 Sol, high reasoning**, independent technical/concurrency scope.

| Criterion | Score | Decision | Evidence |
|---|---:|---|---|
| P1.sky | 8/10 | PASS | Current tablet PNG and historical day/night matrices inspected. Upper soft clouds, removed Deck stripe and glass action retained. Shared temporal adapter and lifecycle/motion gates inspected in source. |
| P2.widget | 8/10 | PASS | Historical compact/wide PNGs inspected; bounded bitmap dimensions and cache-only acquisition inspected. Archived correction1 host coverage and current explicit-component navigation remain distinct. |
| P3.reliability | **6/10** | **CORRECT** | Independent hydration preserves failed reads and rejects stale generations/cancellation, but ordinary Home evaluation propagates acquisition failures without handling them. |
| P4.regression | 8/10 | PASS, scoped | Default acquisition fallbacks remain compatible by source comparison; cleaner/readiness formulas, model, schema, artwork and widget implementation unchanged. Home failure regression is assigned to P3. |
| P5.delivery | 8/10 | PASS, own evidence portion | Snapshot/APK integrity verified; archived build, signing, doctor and test evidence inspected. Collective three-report gate remains **OPEN** for root. |

**F1 — HIGH: strict ordinary evaluation escapes Home’s coroutine**

Location: [HomeData.kt:348](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/HomeData.kt:348).

The ordinary GPS/barometer path invokes `evaluate(..., failOnLocalReadError = true)` directly inside `withContext`, without `homeCatching`, `try/catch` or a failure-result merge. A baseline, pressure, calibration, cleaning or sampling-coverage failure can therefore escape through `coroutineScope` and `LaunchedEffect`. This defeats graceful retention and can terminate the UI through an uncaught coroutine exception. **This is a source-established failure path; I did not execute a crash reproduction.**

Minimal reproduction for root: enter GPS Home with barometer capability enabled and resolvable location/cached weather; make `PressureDao.samplesSince` throw an `IOException` when ordinary evaluation runs. The strict API propagates that exception, as the existing Android boundary test already verifies, but Home does not consume it.

Minimal fix: handle ordinary evaluation with the existing cancellation-preserving failure boundary. Merge nowcast fields only on success; retain previous local fields on acquisition failure and complete loading/refresh bookkeeping. Keep successful empty results clearing old values. Continue remaining weather work after an ordinary local failure.

Add a regression through the actual Home caller covering failure, retained fields, completed loading, subsequent recovery and confirmed-empty clearing. Preserve cancellation propagation.

**Test strength and limitations**

The three Android tests exercise **real `NowcastUseCase` and `PressureRepository`**, with a synthetic DAO and capability lambda. They meaningfully verify legacy swallowed failure versus strict exception identity, empty acquisition, cleaner/readiness parity and recovery.

They do **not** execute `rememberHomeState`; the evaluation assertion deliberately catches the exception in the test. Thus they miss F1. Cancellation identity is asserted for `clean` and `readiness`, not `evaluate`. JVM hydration tests exercise merge/generation/cancellation behavior, but use injected acquisition lambdas rather than the ordinary Home evaluation caller.

**Executed read-only checks**

- Verified all **833** working files and ZIP entries against manifest sizes/SHA256; aggregate manifest hash matches.
- ZIP SHA256 matches `147740fa59656a8c3c72bb48e6a8025195cf4b69be7e3cab7e8967bb0eef187a`.
- All three external APK sizes/hashes match `candidate-apks.json`; their ZIP CRC checks pass.
- Compared permitted r1 snapshot sources; inspected current acquisition callers and production repository boundary.
- Integration/main source comparison showed no differences across the inspected production/debug/test module paths.
- Rechecked all 833 files, ZIP hash and HEAD afterward: unchanged; tracked working tree clean.
- No files created/modified; no build, test runner, device, network, provider, secret or subagent calls.

**Archived evidence, not executions by this reviewer**

Current API **3/3**, navigation **2/2**, signed Release build and engine-doctor pass. XML inventory totals **587**, with zero failures/errors/skips and reused outputs. Correction1 **64 host + 2 navigation** and unchanged artwork evidence remain historical. Signing/identity evidence supports Release delivery; it does not establish Release device testing. The unsupported implicit generic URL smoke remains failed.

**NV:** physical barometer accuracy/continuity, process death, multi-day operation, OEM alarm delivery, live AI, global field accuracy and matched whole-app performance. Historical phone execution lacks its exact preserved binary.

**Required disposition:** correct F1 before approval; no additional visual redesign prerequisite identified.
