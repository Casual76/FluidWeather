# F2 implementation allocation

F1 approved on F1-2b67df7d3ec0, both Sol high critics C1.contracts=8 and C7.compatibility=8. Preserve its archive. F2 mandatory phase criteria: C1.temporal-evidence (current and forecast intervals, freshness, conflict/unknown, geographic coverage, absolute ETA), C7.integration (compatible cache, cancellation, deduplication and pertinent tests/build). Real field C2 and final performance/accessibility C6 remain open.

## A: provider and current evidence

Owns Forecast.kt (core-model), OpenMeteoClient/WeatherSnapshot and codec, restricted OpenWeatherMap/Meteosource metadata, RainObservations.kt if needed, corresponding tests, docs/weather-current-contract.md. Adds nullable compatible current/interval/precision metadata; fixes missing/duplicate/incomplete accumulation and fallback context. Does not write radar/RainViewer/AppGraph/core-ai/core-data/UI/status/engine.

## B: radar safety and absolute transitions

Owns radar source/tests, RainViewer.kt and its tests, AppGraph radar-only wiring, PrecipTools radar-only safety adaptation, docs/weather-radar-contract.md. Separates geographic coverage from pixel/download completeness, unknown from dry, anchors ETA to frame and applies hard 20-minute age limit. Keeps barometric model and singleton identity. Does not write A's area or core-model/UI/core-data/engine/status.

## C: shared evaluator (after A and B contracts stabilize)

A and B complete; parent verified their contracts and ran model/weather/AI tests successfully. C now owns central cached/deduplicated assessment service, WeatherBrief timeline/provenance additions if needed, and graph integration after B finishes. Combine valid evidence with explicit conflicts; no sensor dependency. Cache keyed by actual data revision/location, re-evaluate temporal validity at request time; cancellation and context-switch safety required. Home/AI/widget full adoption remains in their later phases. C may add a sourceTimestampMillis to RadarEvaluation evidence only, with compatible core-model field, to preserve composite frame age without inventing its physical effective interval.

Parent integration addition after A/B: absoluteEta additionally requires agreeing nonstationary motion, not at search edge, mean PSR>=3 and finite heuristic quality>=0.4. This does not hide usable current frame data. RadarSafetyTest verifies rejection for each cause. Core-model83 tests passed, weather/AI full suites passed before this addition, targeted RadarSafety10 passed after it. Full integration/build remains after C. Logs f2-core-model-check.log, f2-ab-check.log, f2-radar-integration-check.log.

No child agents, Gradle/device/build ownership or secrets delegated to A/B. Parent integrates and runs serialized checks with --max-workers=2; emulators stay off until needed. Maximum two implementers at once. Baseline and version archives are immutable. Exact task briefs and returned task metadata are saved under artifacts/weather-improvement.
