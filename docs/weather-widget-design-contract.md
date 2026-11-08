# Launcher widget design contract

## Hierarchy and reference

Reference inspected: user attachment `7e9fb81a-949f-4103-8324-ac09a6b372e0-a360e139-2e07-4cb2-948e-f2aebfc965f6.png`.
The reference establishes place, large temperature, compact condition and high/low, then evenly spaced forecast columns and day rows. Atmospheric sky serves the foreground. This implementation uses actual Glance elements, EngineWidgetShape.Container and engineWidgetTextStyle; it adds no glass simulation, photo sky, AI copy or actions.

## Size comparison for launcher QA

| Responsive size (dp) | Composition |
| --- | --- |
| 110 x 110 | 10 dp padding. Place 13 sp, temperature 32 sp, condition 11 sp, high/low 11 sp. Four single-line blocks. No hour strip, day list, verdict or diagnostic labels. At stale/unknown age, the short age replaces the condition. |
| 250 x 110 | 12 dp padding. Two balanced columns: place 15 sp and temperature 44 sp on the left; 22 dp weather vector, condition 12 sp and high/low 11 sp aligned right. Tiny relative age at the bottom. |
| 250 x 200 | 14 dp padding. Same hero, then four equal hour columns: local time, 20 dp weather vector, temperature 13 sp. Tiny age footer. |
| 250 x 340 and taller | Same hero and hour strip, then at most three future local dates, with vector and high/low in 30 dp rows. Footer stays at the bottom. No scroll container. |

Glance Responsive deliberately selects supported compositions instead of adding content during resize. The model remains three-tiered; the expanded layout reuses the large model and the very same sky bitmap. Intermediate cells use the largest responsive layout that fits. Narrow tall cells remain compact. No unbounded text or list can push forecasts off the widget. Long place and condition text are single-line and may truncate; all locale/font-scale fit remains subject to launcher QA.

## Data and interaction

- Runtime, cache-only shared brief reads, assessment, location identity guards and update wiring are unchanged. No new fetch, sensor, provider, preference or AI dependency.
- Display values use UnitFormatter. Missing and nonfinite numbers render an em dash. A missing condition stays unknown, never clear/dry.
- Existing todayRange stays authoritative for current local-day extrema, now available in the large hero too through additive presentation fields. Legacy minC/maxC fields preserve existing behavior.
- Future day extrema require continuous hourly coverage across the local day and a finite temperature in every slot. Partial days show unknown extrema. Duplicate timestamps are discarded, never arbitrarily chosen. DST days use actual local boundaries. The day icon represents the forecast nearest local noon, not observed conditions or a worst-weather guarantee.
- The existing shared assessment still controls skyKind; precipitation atmosphere is not inferred from missing values. No live start/end ETA is displayed.
- Relative age is frozen at composition time. Small widgets show it only when unknown or at least 90 minutes old. Wide widgets show a tiny minute/hour/day age footer. No full timestamp, provenance wall, coordinates or disclaimers.
- Root tap opens the existing explicit app activity. Condition/compact status opens the existing NOWCAST deep link; the hour strip opens HOURLY and day rows open DAILY. No external or AI action.

## Rendering and handoff limits

SkyScene remains unchanged and calls the shared SkyFrame.draw boundary with static semantics, the existing scrim and the same moon input. Three distinct 220 px long-edge bitmaps remain the memory budget; the fourth layout reuses the large bitmap. Expanded cells crop that scene without stretching celestial discs. Core sky rendering work can integrate independently.

WidgetPresentationTest adds pure checks for density limits, timestamp age, sparse/missing/nonfinite/duplicate day slots, local date ordering and 23/25-hour DST days. No build, Gradle, tests, device, AVD, network or provider commands were run by the implementation agent. Only source inspection and git diff --check were performed. No claim of compiled or visual verification is made.

Parent validation commands (run serially, not executed here):

```powershell
.\gradlew.bat :feature-appwidget:testDebugUnitTest :app:assembleDebug --no-daemon --no-watch-fs
```

On the real launcher compare the four sizes above with the attachment, including long Italian names/conditions, negative three-digit Fahrenheit, unknown current temperature, incomplete days, stale age, night/fog/snow/rain and large system font. Confirm all interactive destinations and total RemoteViews bitmap allocation after shared sky integration. Renderer contrast and perceived realism are shared-renderer/launcher gates, not established by these pure tests.
