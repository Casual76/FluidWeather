# F2 correction round 1

Previous examined version: F2-346bd0b88616 (round 0). This correction addresses three distinct temporal/availability findings; no UI, provider, graph, engine, model training or migration change.

- RadarSafety.absoluteEta retains a passed transition after a short trajectory horizon ends, until the radar frame cutoff. Arrival/end remain null. The shared evaluator therefore retains UNCERTAIN/TRANSITION_EXPIRED instead of restoring the frame's earlier DRY/WET signal.
- RadarSampler passes the already-known mask state through missing raster, incomplete/unknown-colour raster and expiry-during-sampling returns. Supported geography with unavailable data is exposed as RadarAvailability.UNAVAILABLE, without RADAR_SUPPORT_UNKNOWN.
- RadarAroundTool takes a consumption clock after resolving/sampling. That clock controls freshness, safe absolute ETA, remaining minutes and frame age, independently from the older chat context. Passed transitions require a new frame and do not offer a future arrival. Constructor injection makes the real consumer path testable without Android resources; the existing public resolver constructor remains compatible.

New regression checks: LocalPrecipitationEvaluatorTest derives an actual 16-minute horizon/+5 arrival from a 128px diagonal trajectory, checks +9/+11/+16/+17/+20 and cutoff+1ms, and checks passed cessation too. RadarSafetyTest verifies failed/empty/invalid rasters and expiry during download retain known support through the evaluator. Three RadarAroundToolTest cases run the actual tool answer with IO advancing the clock, old chat context, passed ETA, frame cutoff and a newer frame.

Verification: artifacts/weather-improvement/f2-round1/f2-round1-integration-checks.log builds debug APK and runs full pertinent unit suites. unit-test-summary.json and copied XML record core-model88, core-weather243, core-ai27, feature-home5, feature-appwidget28:391 total, no failures/errors/skips. engine-doctor.log passes on unchanged engine3.0.5. git diff --check clean.

No device test repeated in this round: AppGraph/wiring source is unchanged from round0's successful single connected test, archived under artifacts/weather-improvement/f2. This earlier device result is not described as an execution of the corrected radar/tool logic. No field C2 accuracy or final C6 performance evidence is added. Both independent reviewers must assess the new frozen version; no round0 score is reused.
