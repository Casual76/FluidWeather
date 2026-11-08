# F6 C: widget allocation after read-only cache API handoff

B completed its allocated sources/tests and documented exact APIs in weather-f6-cache-contract.md;
parent inspected that document and CachedWeatherBriefSource/WeatherBriefService signatures. No
build/test run yet. A wipe/snapshot writer remains active independently. C occupies the freed slot;
max2 implementers. Parent owns graph/FluidWeatherApp/nav/HomeData integration and every build/device.

## Write boundaries

C owns feature-appwidget production/tests and dedicated NEW core-strings EN/IT widget_shared_rain.xml
resources/helpers only if needed, plus docs/weather-f6-widget-contract.md. Do not edit AppGraph,
FluidWeatherApp, feature-home, existing shared summary/home resource files, core-weather/model/data,
engine or historical artifacts/manifests/archives. Existing module build test dependencies may be
adjusted only if required, using already pinned engine versions; no production version upgrades.
Read fluid-engine and material3-expressive skills and relevant Glance/QA references before UI code.
No Gradle/build/test execution, devices, live network/sensors, secrets, child agents, commit/push.

## Stable parent/runtime contract

Add EXACT read-only property to AppWidgetRuntime:
`val cachedWeatherBriefs: dev.pampa.fluidweather.core.weather.CachedWeatherBriefSource`
Parent supplies graph.weatherBriefService through this narrow interface. It exposes:

```
val cachedInputUpdates: StateFlow<Long>
suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief
```

Call only cachedBrief; no casting to concrete WeatherBriefService, no new client/GPS/radar/provider/
nowcast inference. It reevaluates original local inputs using the same LocalPrecipitationEvaluator
at the current consumption clock. B's cache internals are not widget-owned. Add optional trailing
`cachedInputUpdates: Flow<*>? = null` to installAppWidgetUpdates; parent supplies the source token.
The existing snapshotUpdates argument stays Flow<Map<String,Long>>; parent supplies new store.changes
content tokens instead of fetchedAt-only updates. Keep theme/unit listeners. Initial provideGlance
must read regardless of token value (zero after recreation), then react to input/snapshot changes.

Keep existing cached GPS-first/saved fallback location policy, but retain actual Place identity,
exact snapshot coordinates/key and a clear displayed place label. No relabelling foreign snapshots.
Use snapshot/place matching and resolved place/snapshot zone, with explicit fallback where needed.
Reject/drop obsolete asynchronous context if place/snapshot changes during reads; no mixed data.

## Behavior

Replace the independent quarter threshold and null=false rainingNow rule with the common brief's
state/headline and mandatory model/radar/uncertain/unavailable qualifications. Use shared
WeatherBriefPresentation and existing qualified home strings/formatters rather than a second wet/
dry/ETA policy. Current temperature/kind should be actual brief.current when known, not nearest
fused hour presented as current. Hourly strip/today ranges remain qualified MODEL forecast details;
old/legacy cache remains usable without inventing missing current values or dry weather.

Static RemoteViews do not reevaluate continuously between updates: label the displayed assessment
and ORIGINAL source/update times explicitly (absolute local clock and zone). Do not show ticking
countdowns or an ETA that will be falsely future when the static widget remains on the launcher;
omitting widget ETA is acceptable. The common brief is reevaluated on each existing update, without
new widget-owned requests or alert/notification/scheduling permissions. Preserve source age even
when the download/bundle was carried forward. Missing/expired radar/frame/model and missing amounts
remain unavailable/uncertain, never dry. Distinguish geographic support from download availability.

Barometric history can remain a separately labelled forecast/diagnostic at its original issue time,
not an override of present joint assessment. Do not retrain/run its model or fabricate new local
observations. Derive rain atmosphere from shared known state; uncertainty must not paint assertive
rain/snow. Keep current deep links, responsive tiers, range/hour details, units/theme/sky budget and
stable click behavior. Use actual engine-compatible Glance/material patterns, no custom unrelated UI.

## Written regressions/handoff

Meaningful pure/widget tests: same common result for wet/dry/conflict/unknown, original timestamp/
zone/age and location, legacy cache with missing current, fused future temperature not current,
expiry/passed ETA, no null-to-zero/dry, unit/theme/model range preservation, read-only source no
loader capability and context changed during a suspended cache read. Preserve earlier nonobsolete
appwidget tests; update changed-behavior expectations with the contract reason. No mirror-only tests.
Write unrun tests and static checks; parent will compile and use Device for actual widget QA. Record
exact modified files, APIs and limitations in weather-f6-widget-contract.md. No C2/C6 global claims.
