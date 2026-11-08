# Weather glance widget handoff

Base: `169c719` (main). Assigned widget unit only; no engine, Home, core model/weather/UI, version, acquisition or release changes.

## Presentation and APIs

`WeatherWidgetContent(model, units, nowMillis, target, sky)` remains the sole production content entry point used by both `FluidWeatherAppWidget` and debug `WeatherWidgetHostRenderer`. `SkyScene.of(...)` remains the shared bitmap renderer; its existing SkyFrame/atmosphere/solar inputs are retained under a calm weather-tonal veil. HOME painter is untouched. Engine 3.0.5 shape and text APIs are retained. RemoteViews cannot host the live Compose Fluid backdrop effect: this widget uses a rasterized sky material, not a claim of live backdrop glass.

Responsive sizes now include 350x180 and 350x350, in addition to 110x110, 250x110, 250x200 and 250x340. Large place and dominant left temperature balance a right condition/icon/high-low group. Four aligned hours remain at 250 dp; six appear from 320 dp. Tall layouts have five aligned weekday/icon/min/range/max rows, separated from hours. Large font retains the data hierarchy with extra vertical budget at the short reference size. Empty state is one short EN/IT action label. Full method/source prose remains in app destinations; no source boilerplate was added to the glance surface.

`AppWidgetModelBuilder.STRIP_HOURS` is now 6. `widgetDays` bounds future days at 5 and retains the existing complete-day/DST/duplicate/missing safeguards. New internal helpers `widgetRangeDomain(days)` and `widgetRangeFractions(day, domain)` use one Celsius domain for displayed complete days. Unknown or inverted ranges have an empty track; finite zero is real data. Constant ranges receive a two-dp visible marker. Display-unit conversion affects labels only. No acquisition, clocks, source time, cache/wipe behavior or navigation resolution changed. Cache age remains separate from qualified forecast valid time; stale footer age is visually stronger. Existing location-preserving destination links remain.

## Allocation budget

Six responsive layouts share the same three sky bitmap tiers: 220x220, 220x96, 220x176 ARGB8888 = **432,960 bytes total**. Adding responsive sizes does not add sky allocations. Daily tracks and bars are Glance Box/Row geometry: **zero bar bitmap bytes**. The debug host renders a single tier for its chosen case. No user image is copied into source/resources.

## Verification and integration

Performed only static inspection and `git diff --check`. No Gradle, builds, test runner, device, network, provider, secret, publishing, push or PR activity. Tests are authored/updated but **unrun**, and compilation is unverified.

Updated JVM coverage: six-hour/five-day budgets, complete five-day ordering, shared range normalization, missing/inverted/nonfinite/zero/constant extrema, while retaining partial-day and both DST day-length tests. Existing builder test now expects six future hours.

Host matrix grows from 64 to 96 cases (six sizes x EN/IT x 1.0/1.5 font x empty/stale/current/missing). Synthetic cache now covers seven days to populate all five rows. Existing all-line essential ellipsis, painted glyph, ancestor clipping and pairwise overlap checks remain. Hierarchy counts reflect intended data, plus aligned real hourly ImageViews and painted range-band assertions. Host fixtures still use the exact production content and sky renderer.

Parent owns compilation, all test execution, physical tablet inspection and three fresh independent reviews. Pay particular attention to 350x180 at 1.5 font, long Italian conditions, track widths/marker alignment and platform-dependent RemoteViews text metrics. Synthetic host output is not proof of launcher resize, launcher clipping, click dispatch or live source/device behavior. Those remain unverified until parent validation.
