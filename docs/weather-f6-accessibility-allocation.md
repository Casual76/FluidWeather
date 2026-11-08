# F6 D: accessible home detail presentation

A/B data contracts are handed off; C widget writer remains active. D occupies the freed slot,
max2 implementers. No runtime verification has run for F6. Parent owns graph/runtime/HomeData,
raw radar persistence integration, builds/device and global status/reviews. Read weather-f6-contract.md,
relevant F3/F5 presentation contracts and actual sources before editing.

## Ownership

D owns feature-home UI/presentation files (WidgetTiles.kt, HomeRainOverview.kt, HomeRainPresentation.kt,
relevant pages/DetailsPage.kt and PrecipitationPage.kt, new pure presentation helpers/tests; HomeScreen
only if necessary). Do not edit HomeData.kt, HomeBriefController.kt or dependencies, app graph/nav/
FluidWeatherApp, core-weather/model/data, feature-appwidget, engine or historical artifacts. C owns
appwidget and dedicated widget_shared_rain resources. D may add dedicated NEW EN/IT
weather_accessibility.xml resources/core-strings helper/test, and NEW app Android regression test
files only; parent-owned existing AppGraphWiringTest/build files remain untouched. No dependency
upgrades. Read fluid-engine and material3-expressive skills/relevant components, motion/glass/QA refs
before UI work. No child agents, Gradle/build/tests execution, devices, live providers/secret use,
commit/push/publication. Parent runs every test and QA after writers finish.

## Required changes

1. Daily tile/readable2x: remove fixed narrow day/min/max widths that split labels and numbers
   vertically, including default font Today wrapping. Adapt row/column/FlowRow structure to available
   width and font scale with natural content height; preserve full dates/day labels, weather icons,
   units, min/max interpretation, row ordering, existing tile click/detail and IDs/order. No hidden
   clipping/ellipsis as a workaround. Actual Fluid/shared chrome and existing theme remain.
2. Timeline speech: brief real residuals below a minute must not become an apparent zero-length
   HH:mm-HH:mm interval. Use concise EN/IT wording qualified for a short/approximate interval, retain
   actual interval and source precision/unknown state/amount omission. If two equal wall-clock labels
   are caused by DST overlap across a longer real duration, include disambiguating dates/offsets;
   do not claim it is a sub-minute fragment. Shared absolute ETA10min policy is unchanged.
3. Details and precipitation consumers: use existing state.weatherBrief for present/imminent rain,
   current conditions, source clocks/age/coverage/uncertainty, before early returns for missing fused
   hourly details. Reuse shared WeatherBriefPresentation/formatters/UI, no second evaluator or IO.
   All nearest-fused-hour values shown as current in DetailsPage must be explicitly qualified as
   hourly MODEL samples with valid time/zone; brief.current is the common current summary. Do not
   turn forecast into observation. Existing charts/details remain accessible.
4. Audit legacy present-rain claims in WidgetTiles/PrecipitationPage/NowcastTile. Shared result must
   control current-rain narrative; barometer verdict/history remain separately labelled future
   model/diagnostic at original issue time, no model retraining/numeric change. Do not present a
   raw radar/model observation banner as joint certainty when common evidence conflicts.
5. PrecipitationPage currently maps missing probability/amount to0 in next6/hourly/day/overall sums.
   Correct this: missing/invalid/duplicate/incomplete samples cannot establish dry or a zero/complete
   accumulation. Preserve temporal positions/gaps in graphs; distinguish unknown slots from true0,
   do not drop missing rows and shift labels. Sum only genuinely available compatible intervals or
   explicitly label available-sample sums with counts/limits; no invented duration, prorata, complete
   6h/day total or extrema when coverage is partial. This extends the already-approved F2/F5
   conservative data semantics to these old presentation consumers, not a new forecast algorithm.

Current shared brief updates continue while selectedWidget detail sheets are open (HomeScreen
active depends on coveredByPage, not selectedWidget); do not add independent refreshers. If a real
lifecycle/input API change is necessary, report it to parent rather than editing reserved state files.

## Tests and handoff

Write meaningful pure tests for missing versus0, gaps/duplicates/incomplete sums/time labels/DST and
shared unknown/conflict presentation as changed. Write actual Compose regressions for real Daily
content at normal/2x font with long EN/IT labels and phone/tablet widths, and common detail/rain
state even without hourly data. Tests should inspect text layouts/semantics/reachable actions, not
mirror line counts. A production-reused stateless Daily rows composable may be extracted publicly
for app instrumentation; do not add an unrelated test-only product flow. Parent uses existing pinned
app runner/Compose versions, no new engine versions. Preserve prior HomeRainOverview tests.

Ensure headings, full semantic labels and nested scroll remain usable, reduced-motion/shared glass
unchanged. Full TalkBack/contrast/performance evidence belongs to parent; do not claim it from static
checks. Document exact files/APIs, checks actually performed, written-unrun tests, limits and parent
integration/QA needs in docs/weather-f6-accessibility-contract.md. No C2/C6 global claims.
