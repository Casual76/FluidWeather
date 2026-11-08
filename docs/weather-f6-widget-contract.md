# F6 C: cache-only shared widget assessment

Implementation handoff, not a runtime verification report. No Gradle, build, test execution,
engine doctor, device, network, provider, sensor, secret, child agent, commit or push was used.
No C2 field accuracy, C6 accessibility/performance or global F6 completion claim.

## Parent integration: exact APIs

`AppWidgetRuntime` now requires:

```kotlin
val cachedWeatherBriefs: dev.pampa.fluidweather.core.weather.CachedWeatherBriefSource
```

Supply the existing shared service through that interface. Widget calls only
`cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief`
and reads `cachedInputUpdates: StateFlow<Long>`. No concrete-service cast or loader access.
No widget-owned evaluation, fetch, sensor, inference, alarm or extra scheduling permission.

`installAppWidgetUpdates` retains its existing arguments and adds the optional trailing argument:

```kotlin
cachedInputUpdates: Flow<*>? = null
```

Parent must pass `graph.weatherBriefService.cachedInputUpdates` and supply
`WeatherSnapshotStore.changes` to the unchanged `snapshotUpdates: Flow<Map<String, Long>>`
argument. These are content revisions, not download timestamps. Snapshot/cache listener initial
emissions are retained; provideGlance independently reads even when the cache token starts at zero.
Existing engineSettings and unitPreferences listeners remain, with conflation. Cancellation now
propagates through the local-read helper and update collector.

`AppWidgetModelBuilder.of` keeps its original arguments and adds trailing defaults:
`brief: WeatherBrief? = null, zoneFallback: Boolean = true` after the existing `zone: ZoneId`.
`AppWidgetModel` replaces `rainingNow` with shared `headline`, `brief`, `assessedAtMillis`,
`zone`, `zoneFallback`, `verdictAtMillis`; exposes `skyKind` for qualified atmosphere.
No app integration should construct this model directly.

Dedicated new core-strings helper `WidgetSharedRainFormatter` exposes:

```kotlin
fun headline(brief: WeatherBrief?, now: Long): BriefRainHeadline
fun current(brief: WeatherBrief?, now: Long): CurrentWeatherSummary?
fun headline(resources: Resources, headline: BriefRainHeadline): String
fun absoluteTime(time: Long, zone: ZoneId, locale: Locale): String
fun provenance(resources: Resources, brief: WeatherBrief?, assessedAt: Long,
  zone: ZoneId, fallback: Boolean, locale: Locale): String
```

It reuses WeatherBriefPresentation, WeatherSummaryPolicy consumption gates and home rain strings;
source-limit copy comes from WeatherBriefFormatter. It introduces no rain threshold or ETA policy.

## Behavior and identity

The cached GPS snapshot remains first choice, saved first non-GPS place is the fallback. The
actual Place identity is retained (GPS gets its snapshot coordinates); saved identity/name is never
replaced by GPS identity. Wrong-key, invalid-coordinate or >2km foreign saved snapshots are dropped.
The exact accepted snapshot point, not a rounded name/key-derived point, goes to cachedBrief.
Saved places without snapshots can still receive a cached radar assessment at their real point.
Place/snapshot/context equality and cache token are checked after suspended reads, with at most
three attempts, and again after history IO. Instability drops obsolete display state. Cancellation
is not converted to unavailable; ordinary cache IO failure keeps a stable legacy forecast with an
explicit unavailable assessment. The final synchronous composition uses the checked bundle.

Current temperature/kind require usable brief.current evidence. Fused future hours never supply
current conditions. Old caches retain dated/model-labelled forecast ranges, probabilities and hourly
strip without inventing a current value or turning missing amounts into zero/dry. Day ranges and
hour labels use the resolved place/snapshot/context zone, with explicit device fallback disclosure.

All tiers show qualified shared precipitation text. Provenance includes assessment clock, IANA zone,
exact point, original source AND download clocks, unknown-clock labels, geographic radar support
separate from unavailable download, shared limits and uncertainty. Absolute clocks include year,
seconds and numeric offset, so DST repeated wall times differ. No ETA or countdown is rendered;
the static assessment is explicitly dated rather than asserting a perpetually current result.
The forecast snapshot cache time is separately labelled, never substituted for original evidence age.

Barometer history remains on medium/large with original issue clock, percent and original horizon,
explicitly labelled historical forecast/diagnostic. Its record lacks original location, which is
explicitly disclosed; it is shown only for the GPS context and never overrides shared present rain.
Saved-place widgets do not borrow this unlocated diagnostic. No model runs or observations are made.
Wet atmosphere requires known shared wet; uncertain/conflicting/unavailable assessments suppress
assertive rain/snow. Current condition icons remain distinct from the shared assessment.

## Layout and limits needing parent verification

Three responsive tiers, thresholds, declared sizes, solar/lunar scene, bitmap dimensions/budget,
unit rendering and activity/nowcast/hourly URI deep links remain. A Glance LazyColumn collection
contains the content, so mandatory source disclosures and forecast details can be reached by scrolling
in short cells instead of being irretrievably clipped. The current/header click still opens the app;
assessment/history and hourly actions retain their respective deep links. The outer sky raster is
shared per tier as before. Theme/unit invalidation listeners remain.

Parent must compile and test Glance collection support, scrolling of a tall item, nested click
routing, resize behavior and source-copy reachability on actual launchers, API26+ and large fonts,
including EN/IT and source lists long enough to scroll. No launcher, TalkBack, contrast, timing,
RemoteViews parcel-size or performance result is claimed. The static launcher still depends on the
existing update schedule/signals for reevaluation; this implementation adds no timer. B/A were also
uncompiled at allocation time, so parent compilation remains the integration gate. There is no known
API blocker from source inspection.

## Exact files owned/changed by C

Production under `feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/`:
- AppWidgetBriefRead.kt (new; local identity/read guard)
- AppWidgetModel.kt
- AppWidgetRuntime.kt
- AppWidgetUpdates.kt
- FluidWeatherAppWidget.kt

Tests under `feature-appwidget/src/test/kotlin/dev/pampa/fluidweather/feature/appwidget/`:
- AppWidgetModelTest.kt (existing regressions retained; three nearest/current assumptions updated
  because F6 forbids promoting legacy forecast into actual current)
- AppWidgetSharedBriefTest.kt (new)
- AppWidgetSharedCopyTest.kt (new)

`feature-appwidget/build.gradle`: only existing pinned engine coroutines-test/Robolectric test
dependencies and Android resource unit-test support. No production dependency/version upgrade.

Dedicated new core-strings files only:
- src/main/kotlin/dev/pampa/fluidweather/strings/WidgetSharedRainFormatter.kt
- src/main/res/values/widget_shared_rain.xml
- src/main/res/values-it/widget_shared_rain.xml

This document: docs/weather-f6-widget-contract.md. No graph, Application, nav, home, core-weather,
core-model/data, engine, manifest or historical artifact files were edited by C.

## Written regressions and static checks

Written/unrun tests cover shared wet/dry/model/radar/conflict/uncertain/unavailable headlines,
missing current versus future fused temperature, source/current expiration, passed transitions,
uncertain sky, wrong location key/point, zero-token initial read, cache-token race, changed place
and identical-fetchedAt snapshot content across a suspended read, clear/cancellation, cache-read
failure preserving forecast, EN/IT original absolute clocks/point/zone/support/unknowns/limits,
absence of future ETA in widget copy, unit conversion and DST offset distinction. Existing model,
range/probability/tier/icon and sky-budget tests remain parent regression gates.

Actually performed: source/API and applicable Fluid/Material3/Glance/QA reference inspection,
scoped git diff --check (clean), static XML parse and EN/IT resource-name parity (pass), static
production scan confirming no nearestHour/rainNowMm/RainObservation/concrete WeatherBriefService
or radarLoader in widget sources (pass). These checks are not compilation or executed tests.
Parent should run feature-appwidget unit tests including Robolectric alongside B/A suites, wire the
runtime/listener arguments above, then perform launcher/device and independent review validation.
