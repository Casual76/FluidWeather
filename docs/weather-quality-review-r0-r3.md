**Verdict: CORRECT; keep acceptance open.** The candidate substantially fixes the rejected presentation, but remaining clock, missing-data and layout defects prevent advancement.

Reviewer: **GPT-6.1 Sol, high reasoning effort**. Reviewed **FluidWeather 2.1.2/code23**, candidate **Q-R0-7d54f7ad111d**.

Identity checks passed before and after review: all **721 manifest entries** matched the checkout and ZIP; the manifest digest and both APK hashes matched. HEAD remained `88655da79e208819da16c251b8b2051f969f4cda`. Engine **3.0.5** and `engine.properties` were unchanged.

| Criterion | Score | Evidence and judgement |
|---|---:|---|
| Q1 — Owned detail surfaces | **8** | Viewed selected Pressure/Moon controls on phone and tablet against the original screenshots. Sky leakage is visibly removed. `BlackSheetSurface` supplies a separate opaque recording and dark palette. Archived surface tests also check both backdrop channels and changing parent-sky pixels. |
| Q2 — Useful precipitation page | **8** | Current images show a bounded six-hour forecast, a readable summary, explicit coverage counts and optional six-row tables. The original technical wall is gone. Source inspection preserves missing positions, rejects duplicates/invalid values and distinguishes zero from unknown. Amounts are qualified as sample sums. |
| Q3 — Metric first, diagnostics disclosed | **7** | Pressure, Moon, Details and Nowcast open with their useful metric; repeated giant rain panels are removed and methods require expansion. **The Sun page still contains an unacceptable narrow-column layout**, described below. |
| Q4 — Real interactions and device coverage | **7** | Selected controls, expanded tables and sources have archived screenshots/trees and parent execution evidence. Physical Hourly is visibly real. **Phone Hourly remains NV**, so the requested phone/tablet flow coverage is incomplete. |
| Q5 — Truthful weather and preservation | **6** | Present/model/radar wording and ETA restrictions are conservative; source clocks remain distinct. However, **Hourly conflates unknown with zero, detail clocks use inconsistent zones, and Pressure compresses temporal gaps**. App-widget sources are unchanged; actual launcher-widget rendering remains **NV**. |

The concrete findings are:

1. **Medium — Hourly makes unknown precipitation indistinguishable from dry/low values.**
   [HourlyPage.kt:97](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/HourlyPage.kt:97>) renders both `null` probability and probabilities below 5% as `""`; amounts likewise render `null` and values below 0.05 mm as `""`.

   **Reproduction:** supply adjacent hours containing `(null, null)` and `(0%, 0 mm)`, then open Hourly. Both rain columns are blank. `final-physical-hourly.png` visibly demonstrates the blank-column presentation. Users cannot distinguish unavailable data from an available dry forecast. Preserve explicit zero and an unknown marker; qualify any intentional suppression of trace amounts. **Affects Q5.**

2. **Medium — Locality clocks disagree across pages.**
   [PageKit.kt:63](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/PageKit.kt:63>) uses `ZoneId.systemDefault()` for detail time/date helpers. [MoonPage.kt:99](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/MoonPage.kt:99>) uses those helpers, while Home’s rain timeline resolves the place zone.

   **Reproduction:** view Sesto Fiorentino with a device zone different from Europe/Rome. The archived phone Moon shows the next new moon at **9:57 PM**; the tablet shows **22:57** for the same event. Neither Moon page labels that zone difference. Home’s place-zone clock therefore cannot be read consistently alongside these detail times. Resolve a common place zone, or explicitly label device-zone times. **Affects Q5.**

3. **Medium — Pressure’s graph hides elapsed-time gaps.**
   [PressurePage.kt:127](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/PressurePage.kt:127>) passes values to `CurveWithLabels`; [Charts.kt:49](</C:/VibeCoded Projects/FluidWeather/core-ui/src/main/kotlin/dev/pampa/fluidweather/core/ui/Charts.kt:49>) spaces points by index.

   **Reproduction:** select 24 hours with a long acquisition gap followed by densely sampled readings. `final-phone-pressure-selected.png` puts **3:37 PM → 8:46 AM → 10:44 AM** at approximately equally spaced labels and joins the series continuously. Roughly seventeen hours and two hours receive comparable horizontal space, distorting apparent duration and slope. Plot timestamp positions and expose unsupported gaps. **Affects Q5.**

4. **Medium — Sun’s twelve-hour-day row becomes unreadable on the evidenced phone.**
   [SunPage.kt:120](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/SunPage.kt:120>) puts two long dates in the value column of [StatRow](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/PageKit.kt:118>), leaving the label only the remaining width.

   **Reproduction:** open Sun in the archived English phone configuration. In `final-phone-sun.png`, “About 12 hours of daylight” wraps into a tall sliver ending in a standalone **“t”**. Use stacked label/date rows or separate the dates. This is visible product breakage, not an inferred accessibility requirement. **Affects Q3.**

My checks were read-only source/archive/hash inspection and actual image viewing, including every mandatory image and all three original user screenshots. I ran **no builds, tests, device actions or provider calls**.

Parent evidence is separate: physical **71/71 in 98.227 s**; Android15 **68/68 after an earlier eight-failure run**; archived JVM XML totals **155**, including reused outputs. These do not disprove the findings above. The current-headline ageing concern was **not confirmed**: `HomeBriefController` reconsumes inputs periodically.

**Open gates:** correct the findings and review their resulting images; demonstrate phone Hourly; retain release/signature verification as a delivery gate. Sensor accuracy is **NV**. C2 field validation and C6 controlled performance are **NA for this acceptance scope**.

Independence limitation: an initial memory-registry search exposed historical review snippets. I did not open those reports or use their conclusions, but this limits a strict claim of complete reviewer blindness.
