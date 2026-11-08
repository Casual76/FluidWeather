**PASS — independent R3 review of Premium-R1-73c919c3ff25.** No mandatory correction found. All five individual votes meet the required 8/10 threshold. P5’s collective three-review gate remains **OPEN for root**.

Reviewed HEAD: `7834adf2ac96387e5745c6b3f16dac6fc1f26236`  
Production inputs: `38323f9a9691db9ad5fea42831e79928c1bc5527`  
Baseline: `8e97f750d8ae23c15b80fd9c89c199d2e3ef14b6`

| Criterion | Vote | State | Evidence and boundary |
|---|---:|---|---|
| P1.sky | **8/10** | PASS | Viewed tablet Home and day/night sheets. Upper soft clouds remain; no continuous Deck stripe. R0 comparison confirms the only correction1 painter change is the widget scrim. Shared evidence gates preserve source clocks and prevent future forecasts from initiating present precipitation or lightning. Painter preparation and lifecycle/motion gates remain. |
| P2.widget | **8/10** | PASS | Production content, cache acquisition, bitmap ownership, routing and host assertions inspected. Viewed all five supplied widget captures. Archived current execution contains 64 distinct production Glance→RemoteViews cases. Validity time and stale age remain visible together; essential ellipsis checks cover every line. Launcher negotiation and actual PendingIntent clicks remain NV. |
| P3.reliability | **8/10** | PASS | Independent local hydration consumer, Room signals, timer recovery, generation/cancellation guards, bounded sensor attempts and surveillance ownership inspected. Pertinent archived JVM results pass. Sensor and hydration implementation are unchanged from R0; historical native startup evidence is supporting evidence, not a current-binary rerun. |
| P4.regression | **8/10** | PASS | R0 source comparison confines correction1 changes to widget presentation, request handling, assertions, localized strings and widget scrim. Accepted Home drawing remains unchanged. No sensor implementation change in this round. Cache-only widget reads, place identity and original clocks remain intact. |
| P5.delivery | **8/10** | PASS individually | Frozen package integrity and external APK identities verified. Archived current 66-method run, release build, doctor, signing and package evidence support this scoped delivery. Release-device behavior is NV. Root must evaluate the collective review gate. |

No arithmetic averaging was used.

**Checks I executed, read-only**

- Verified **772/772 individual files**, both filesystem and ZIP content, against declared byte lengths and SHA-256 hashes: **zero mismatches**.
- Recomputed the specified ordered UTF-8 `path:sha256` serialization with LF separators and no trailing LF. Result:  
  `73c919c3ff2524b34ff757333366a8966d9634cca57cc773643141b5ad472a36`
- Verified the ZIP contains 773 entries: the 772 inputs plus a byte-identical embedded `review-input.json`.
- Verified `candidate-apks.json` equals the metadata’s external-APK object. All three APK byte lengths and hashes match; APK ZIP integrity checks pass.
- Confirmed the preserved test APK contains both new same-place-transition and all-line-ellipsis assertion messages.
- Parsed archived XML: **244 tests, zero failures/errors/skips**—sensor 57, core-ui 60, home 76, widget 51.
- Parsed current instrumentation output: **66 distinct successful methods**, comprising 64 host cases and two navigation methods.
- Inspected the permitted R0 archive for source deltas and viewed the five widget PNGs, tablet Home, and both day/night sheets.

| External APK | Bytes | Verified SHA-256 |
|---|---:|---|
| Debug | 73,532,432 | `38050ebb52019142b9cca542cc27655417158a6c1d6dba815f2cc5d2d3b280d2` |
| Android test | 1,315,396 | `64a8e1690dcb926e8ed40847b632805f4714879dba04ee6560eb403246fd46dc` |
| Release | 25,012,599 | `1740bc185417ce07896e70de664a801b02cd9465e4a4727c7381cfe7dd76042f` |

**Adversarial correction assessment**

1. **Fallback validity and age — PASS.**  
   [WeatherWidgetContent.kt:69](/C:/VibeCoded%20Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/WeatherWidgetContent.kt:69) formats the original forecast timestamp using the widget resource locale and resolved zone. Compact fallback uses an existing secondary slot; short wide fallback retains its icon and a separate stale-age row. The captured English and Italian compact widgets visibly show `At 2:00 PM` / `Alle 14:00` and nine-hour download age without clipping.

   The model selects fallback within one hour of composition time, limiting ambiguity from omitting a date. Current versus fallback is expressed through the validity label. This satisfies the concise UI requirement; repeated model/estimate disclaimers are unnecessary. Padding/hero adjustments retain the assertions.

2. **Same-place OPEN-detail Home transition — PASS.**  
   [WeatherWidgetNavigationTest.kt:108](/C:/VibeCoded%20Projects/FluidWeather/app/src/androidTest/kotlin/dev/pampa/fluidweather/WeatherWidgetNavigationTest.kt:108) first confirms the second identical Hourly URI has reopened the owned cached `37%` fixture. It then delivers Home for the **same place while Hourly remains open**, without Back or a place change. The assertion combines selected identity, visible saved-place name and absence of the detail close control.

   [FluidWeatherNavHost.kt:134](/C:/VibeCoded%20Projects/FluidWeather/app/src/main/kotlin/dev/pampa/fluidweather/navigation/FluidWeatherNavHost.kt:134) clears obsolete Home state for superseding non-Home requests, cancels superseded resolution through the effect, checks cancellation before selection, and acknowledges Home only for a matching nonce. Home delivery also clears pending widget targeting. [HomeScreen.kt:300](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/HomeScreen.kt:300) dismisses the selected detail independently of place changes.

   Cold GPS→saved Hourly, repeated identical URI, different-place Home and deletion fallback remain covered. Rapid overlapping delivery is source-reviewed rather than separately runtime-proven.

3. **Host assertion strength and locale — PASS.**  
   [WeatherWidgetRemoteViewsHostTest.kt:66](/C:/VibeCoded%20Projects/FluidWeather/app/src/androidTest/kotlin/dev/pampa/fluidweather/WeatherWidgetRemoteViewsHostTest.kt:66) checks ellipsis on **every layout line**. The test retains painted-glyph bounds, clipping-ancestor containment, pairwise text-overlap checks and hierarchy expectations. Fallback validity must occur exactly once; stale age must remain present.

   Production hour labels explicitly use resource locale and compact `TimeFormats.hour`. Daily presentation uses local dates, rejects incomplete extrema and has archived 23/25-hour DST coverage. These checks establish supported host layouts, not complete launcher behavior.

4. **Widget scrim and rendering constraints — PASS.**  
   The normalized R0 painter diff adds only the header gradient inside `ScrimPainter`; Home’s painter path remains unchanged. The empty-header capture still contains celestial glow, but the text is readable and no blocking visual defect is evident.

   Production creates three bounded sky bitmaps outside responsive composition; Large and Expanded share one. Their declared pixel allocation totals **432,960 bytes**. Acquisition remains cache-only with place/input stability checks. Total launcher binder allocation was not measured by this review.

**Archived execution versus inference**

The current log records **66/66 in 100.645 seconds**, terminal `-1`. The release log records success in **2m24s**, with **107 executed, 23 cached and 1,018 up-to-date** tasks. Doctor reports clean pinned Engine 3.0.5. Archived package/signature evidence supports `dev.pampa.fluidweather`, version 2.1.2/code23 and v2 signing.

I did not execute these builds or tests. The 244 XML inventory includes reused outputs. Earlier tablet 144/2 and phone 64+1 executions remain historical. Exact source-to-build/run association relies on the frozen provenance record; the APKs contain no source-commit stamp.

The generic implicit URL smoke remains **FAILED**, consistent with the absent VIEW filter. Explicit-component integration passes do not turn that failure into a pass. Release was not installed over the principal app and is **not release-device-tested**.

**Remaining finding**

| Priority | State | Repro / path | Consequence | Minimum fix |
|---|---|---|---|---|
| Low | OPEN, nonblocking | Read the final locale paragraph and historical validation wording in `docs/weather-widget-host-qa-contract.md`; compare with current `HourStrip` and verification document. | The contract still says production hours use the process-default locale and contains obsolete unrun wording, which can confuse future evidence interpretation. | Mark those passages historical or update them to the current locale implementation and archived execution. |

**Boundary:** no mandatory **CORRECT** item remains. Launcher/OEM breadth, rapid-delivery stress and release-device behavior remain NV, without becoming new prerequisites. The collective P5 decision belongs to root.

No writes, builds/test runners, devices, network/providers/secrets, delegation, forbidden reports or other reviewers’ verdicts were used.
