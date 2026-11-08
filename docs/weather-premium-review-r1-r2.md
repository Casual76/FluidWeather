**Independent R2 vote: PASS for correction1, version `Premium-R1-73c919c3ff25`.** No mandatory correction or blocker found. P5’s collective three-review gate remains **OPEN for root**; this report supplies one independent vote.

Reviewed HEAD: `7834adf2ac96387e5745c6b3f16dac6fc1f26236`  
Production inputs: `38323f9a9691db9ad5fea42831e79928c1bc5527`  
Source baseline: `8e97f750d8ae23c15b80fd9c89c199d2e3ef14b6`

| Criterion | Score | State | Evidence and boundary |
|---|---:|---|---|
| P1.sky | **8/10** | PASS | Viewed preserved tablet Home and complete day/night sheets. Soft cloud masses occupy the upper sky; no continuous Deck stripe. Production cloud bank remains bounded to four/three groups. Lifecycle/motion gates and matching off-thread painter preparation remain. Synthetic sheets establish artwork behavior, not Android GPU performance or meteorological accuracy. |
| P2.widget | **8/10** | PASS | Personally viewed all five new widget PNGs. Valid-time and download-age labels remain distinct, compact stale layouts fit, and expanded hierarchy remains clear. Production/test inspection confirms resource-locale hours and every-line essential ellipsis checks. Archived 64-case production RemoteViews execution supports fit; launcher negotiation and actual launcher clicks remain outside that proof. |
| P3.reliability | **8/10** | PASS | Separate local hydration consumer, Room signals, timer recovery and generation/cancellation checks are present. Sensor registration cleanup, bounded silence, serialized collection and restart ownership have targeted regressions. Weather refresh survives absent/failed pressure acquisition. Physical startup/Room evidence is historical; current 66-method execution does not freshly exercise this entire criterion. |
| P4.regression | **8/10** | PASS | Correction delta preserves Home cloud drawing and ordinary data clocks. Root Home nonce dismisses same-place detail and acknowledges matching requests. No changes found in the inspected model/data/schema paths against baseline. Principal Release installation/data preservation was not device-tested in this review. |
| P5.delivery | **8/10** | PASS evidence portion; collective OPEN | Frozen integrity, APK provenance, archived build/doctor/signing/identity and relevant result inventory are adequate for this scoped review. Root must collect all three independent reports and apply mandatory thresholds individually. |

**Checks executed by me, strictly read-only**

- Verified all **772 individual ZIP entries** against declared byte lengths and SHA-256; also verified all 772 corresponding filesystem files. Zero mismatches.
- Recomputed the ordered UTF-8 `path:sha256`, LF-separated, no-trailing-LF manifest hash:  
  `73c919c3ff2524b34ff757333366a8966d9634cca57cc773643141b5ad472a36`.
- Embedded `review-input.json` is byte-identical to external metadata. ZIP has 773 entries including that metadata, no duplicate names, and passes CRC inspection.
- External `candidate-apks.json` equals embedded `externalApks`. All three APK byte lengths/hashes match; all pass ZIP CRC inspection.
- Compared R1 source changes against the permitted R0 archive. Inspected targeted production/tests and factual contracts. The inspected production/test/build paths have no changes between production-input commit and reviewed HEAD.
- Viewed every new widget PNG, preserved tablet Home, and complete day/night sheets. Their filesystem bytes match the frozen archive.
- Parsed archived instrumentation and JVM XML. No Gradle, tests, devices, network/providers, secrets, delegation, prohibited reports or ledgers were used.

| External APK | Bytes | Verified SHA-256 |
|---|---:|---|
| Debug test | 1,315,396 | `64a8e1690dcb926e8ed40847b632805f4714879dba04ee6560eb403246fd46dc` |
| Debug | 73,532,432 | `38050ebb52019142b9cca542cc27655417158a6c1d6dba815f2cc5d2d3b280d2` |
| Release | 25,012,599 | `1740bc185417ce07896e70de664a801b02cd9465e4a4727c7381cfe7dd76042f` |

**Correction-target assessment**

1. **Fallback validity and concise UI — PASS.**  
   [WeatherWidgetContent.kt](</C:/VibeCoded Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/WeatherWidgetContent.kt:69>) uses `forecastAtMillis` for localized validity and retains separate download age. Viewed EN/IT compact captures visibly show `At 2:00 PM` / `Alle 14:00` alongside `9 h ago` / `9 h fa`. Current expanded data instead retains condition/range and its age footer. This meets the requested concise distinction; repetitive estimate/model disclaimers are unnecessary. Compact hero/padding changes preserve usable hierarchy.

2. **Same-place Home delivery — PASS.**  
   [FluidWeatherNavHost.kt](</C:/VibeCoded Projects/FluidWeather/app/src/main/kotlin/dev/pampa/fluidweather/navigation/FluidWeatherNavHost.kt:134>) carries the Home nonce, clears superseded state and checks matching acknowledgement. [HomeScreen.kt](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/HomeScreen.kt:297>) dismisses the selected detail. The native regression at [WeatherWidgetNavigationTest.kt](</C:/VibeCoded Projects/FluidWeather/app/src/androidTest/kotlin/dev/pampa/fluidweather/WeatherWidgetNavigationTest.kt:104>) reopens identical Hourly, leaves it open, then delivers Home for the same place without intervening Back or selection change. Cold saved-place data, different-place Home and deletion fallback remain covered.

3. **Ellipsis and locale — PASS.**  
   [WeatherWidgetRemoteViewsHostTest.kt](</C:/VibeCoded Projects/FluidWeather/app/src/androidTest/kotlin/dev/pampa/fluidweather/WeatherWidgetRemoteViewsHostTest.kt:65>) checks every layout line, including the second condition line. Glyph, ancestor-clipping and overlap assertions remain. HourStrip explicitly supplies the widget resource locale to `TimeFormats.hour`; viewed Italian expanded hours are compact `15`, `16`, `17`, `18`.

4. **Widget scrim and retained Home clouds — PASS, with optional polish below.**  
   [SkyPainters.kt](</C:/VibeCoded Projects/FluidWeather/core-ui/src/main/kotlin/dev/pampa/fluidweather/core/ui/SkyPainters.kt:1267>) adds header attenuation through the existing opt-in scrim. Home’s drawing path remains unchanged by this correction. Images and source agree on retained soft upper puffs and removed Deck/atlas/CloudVolume experiments.

**Nonblocking observations**

| Priority / state | Reproduction and path | Consequence | Minimum fix |
|---|---|---|---|
| Low / OPEN polish | View `evidence/widget-host/250x200_en_1.0_empty.png`: sun halo crosses the place title near “Val di”. | Title remains readable, but local brightness still competes with header ink. Scrim mitigates glare without eliminating it. | Optional modest attenuation around header height, confined to widget scrim. No Home artwork change needed. |
| Low / OPEN documentation | `docs/weather-widget-host-qa-contract.md` retains historical claims that compilation/device fit are unverified and HourStrip uses process-default time formatting. | Readers can misunderstand present evidence despite the newer verification document. | Mark those paragraphs explicitly historical or update them to current source/evidence boundaries. |

**Archived execution, separately assessed**

- Current tablet log contains **66 distinct methods**, 66 start statuses and 66 success statuses, `OK (66 tests)`, **100.645 seconds**, terminal `-1`: 64 RemoteViews cases plus two navigation/URI methods.
- Release log succeeds in **2m24s**, with **107 executed, 23 cached, 1,018 up-to-date** tasks.
- JVM inventory is **244 results**, zero failures/errors/skips, including reused outputs.
- Doctor reports clean pinned engine **3.0.5**. Archived signing verification passes v2; manifest identifies `dev.pampa.fluidweather`, **2.1.2/code23**. Release contains ARM64/ARMv7.
- Prior tablet 144/navigation two and phone 64+1 remain historical. Exact phone binary is absent; no current phone-tested claim follows.
- Generic implicit URL smoke remains **FAILED** because no VIEW filter exists. Successful explicit-component integration is separate evidence.
- Release remains signed/build-verified, **not Release-device-tested**.

The visual conclusions are judgments from preserved captures plus matching production source. They do not establish field accuracy, full accessibility certification, process-death recovery, controlled p95 performance or long-duration sensor continuity; none is introduced as a new prerequisite here.

**Final boundaries: PASS this independent scoped review; CORRECT none mandatory; OPEN root’s collective P5 gate and the two optional observations above.**
