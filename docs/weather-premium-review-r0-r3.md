**R3 verdict: CORRECT — Premium-R0-f1d8bb00bd6e**

Reviewed HEAD: `c6fe9385271d619277a3205c860e7a9b6c9bb03b`  
Baseline: `8e97f750d8ae23c15b80fd9c89c199d2e3ef14b6`

Two source-level defects prevent acceptance. Neither was reproduced on a device during this review.

| Criterion | Score | Gate | Assessment |
|---|---:|---|---|
| P1.sky | 8 | PASS | Upper Cumulus composition, shared rendering, precipitation guards and lifecycle bounds supported by source and archived images. |
| P2.widget | 6 | CORRECT | Good layout evidence and bounded acquisition/rendering; fallback qualification and existing-detail Home navigation need correction. |
| P3.reliability | 8 | PASS | Independent local hydration and sensor-attempt recovery supported within the authorized scope. |
| P4.regression | 7 | CORRECT | Protected model/schema/feedback sources unchanged; widget loses an existing forecast qualification. |
| P5.delivery | 7 | OPEN | Artifact accounting is substantially sound. Final acceptance remains blocked by the defects and the independent-review gate. |

Mean **7.2/10**; it does not override the failed mandatory criteria.

**Findings**

1. **Priority 2 — Hourly fallback loses its visible forecast qualification.**

   [AppWidgetModel.kt:154](/C:/VibeCoded%20Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/AppWidgetModel.kt:154) retains `forecastAtMillis` and `usesHourlyForecast`. [WeatherWidgetContent.kt:60](/C:/VibeCoded%20Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/WeatherWidgetContent.kt:60) presents the resulting temperature/condition without consulting either field. The baseline explicitly displayed `widget_shared_forecast` and the forecast time.

   **Reproduction from code:** provide a snapshot containing usable nearby fused hourly temperature, with no usable current summary. The hero displays that forecast temperature; larger layouts display its condition. An age label identifies download age, not forecast validity or evidence type.

   **Consequence:** forecast fallback can look like current weather. This is an existing semantic distinction lost during the redesign.

   **Minimum fix:** restore a concise localized forecast qualifier when `usesHourlyForecast` is true, fitting it within each layout’s existing budget. Preserve download age separately. Extend host assertions to require the qualification.

2. **Priority 2 — Root Home requests leave an existing same-place detail open.**

   [FluidWeatherNavHost.kt:135](/C:/VibeCoded%20Projects/FluidWeather/app/src/main/kotlin/dev/pampa/fluidweather/navigation/FluidWeatherNavHost.kt:135) selects the requested place, returns to the Home route and consumes the request. It sends no dismissal signal to Home. Meanwhile, [HomeScreen.kt:293](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/HomeScreen.kt:293) retains `selectedWidget` while the place ID stays unchanged.

   **Reproduction from code:** open Hourly for place A; leave its detail open; deliver the widget’s root `Home(A)` explicit intent. The selected place and route already match, so the Hourly detail remains.

   **Consequence:** root Home and detail targets do not consistently produce distinct outcomes.

   **Minimum fix:** deliver a consumable Home request/nonce to the Home owner that dismisses its active detail before completing the request.

   The archived navigation test does not cover this transition: [WeatherWidgetNavigationTest.kt:109](/C:/VibeCoded%20Projects/FluidWeather/app/src/androidTest/kotlin/dev/pampa/fluidweather/WeatherWidgetNavigationTest.kt:109) closes Hourly before testing root Home.

3. **Priority 3 — Essential-text ellipsis assertion checks only the first line.**

   The RemoteViews host test checks `getEllipsisCount(0)`, while conditions may occupy two lines. Last-line ellipsis could therefore escape that assertion. The viewed condition PNG is readable; this is a test-strength limitation, not evidence of an observed clipped condition.

   **Minimum fix:** check ellipsis counts across every layout line for essential text.

**Checks actually performed**

Only read-only file/hash, archive, Git, code and image inspection was performed. No build, test runner, device, provider, network or delegation was used.

- All **769 manifest entries** match ZIP contents and workspace files by bytes and SHA-256.
- ZIP contains those files plus the embedded manifest; embedded and external manifests are byte-identical.
- **703 entries** correspond to tracked reviewed-HEAD files. Ninety differ from Git archive bytes solely through CRLF/LF conversion; none differ after newline normalization.
- Current repository HEAD matches the requested HEAD; workspace and engine status are clean.
- Engine HEAD is `a055257fc4b559bee54b9ce114f265bcd237adfd`; archived doctor output reports clean pinned **3.0.5**.
- Protected model/data/weather/AI sources and engine inputs show no changes against the stated baseline.
- Production release inputs show no changes between `00669fa53c342e2b7a643f00f57a5bdf79de37c7` and reviewed HEAD.
- Debug and Release APK ZIP CRC checks pass.
- ZIP SHA-256: `045c0757062875e7d04afbd83d91cc40589ede210c07244620f295d7305b08e3`.

The supplied `sourceManifestSha256` remains an identifier whose serialization algorithm was not established here; individual content hashes were independently verified.

All five external APKs match their declared bytes and SHA-256:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Current Debug | 73,353,983 | `82418cf4d852fd86d9802029c79fac928222a1786617ced82178a9065a9eb24b` |
| Current AndroidTest | 1,213,294 | `5eb7571b300a13e7df3b572466b420e2c973fe3648f9717efe27584c50ca813e` |
| Current Release | 25,012,367 | `3c0e35a98ea39262ad8a26b57cc47088802389cd81a8530506db0c61a84dc07a` |
| Historical tablet Debug | 72,728,725 | `952f3b97e7e11c2d0d3e98648f3d8a9d733c9307e975da82453a104cbf078e34` |
| Historical tablet AndroidTest | 1,213,294 | `462cb22cba244049a4fa3bcf9a162e80e2dd90d892313a9ce0cef3e0537d0f5f` |

**Widget and navigation evidence**

The host genuinely composes production Glance content, inflates framework RemoteViews and measures actual painted glyphs, ancestor clipping and pairwise overlaps. Its 64-case matrix covers four exact sizes, EN/IT, font scales 1/1.5 and four data variants.

I viewed **all four preserved widget PNGs**, the tablet Home capture and `atmosphere-day.png`. The widgets show readable hierarchy, useful forecast rows and explicit empty/stale states. Intentional place-name truncation is visible. These four captures do not establish every matrix case visually.

The host bypasses production cache acquisition and responsive launcher selection. It does not exercise launcher clicks, combined responsive serialization, refresh scheduling or arbitrary locale/unit extremes. Its synthetic “current” is typed model evidence, not observed weather.

Production acquisition remains cache-only with place/snapshot and input-generation guards. Three owned sky bitmaps total **432,960 pixel bytes**; Expanded reuses Large. This supports a bounded pixel budget, not a measured complete Binder payload.

Explicit component targeting and URI place identity are coherent. Condition → NOWCAST, hours → HOURLY and days → DAILY preserve the base place ID. Deleted numeric IDs fall back to GPS; absent legacy IDs preserve session selection. MainActivity’s request nonce supports repeated identical links.

Archived native navigation verifies cold Activity delivery, saved-place Hourly content, repeated identical Hourly requests, root Home after dismissal, and deleted-place fallback. It does not constitute actual launcher PendingIntent click coverage or runtime coverage of every detail target.

**Reliability and archived execution boundaries**

Local hydration runs independently of GPS/weather loading, observes pressure invalidations and calibration changes, and retains a polling fallback. Generation/cancellation guards protect ownership. Sensor source handles rejected registrations, silent attempts, zero-sample retries, listener cleanup and surveillance restart. Unsupported pressure hardware still permits weather refresh.

The archived startup test uses real MainActivity/graph/Room with synthetic owned records and denied GPS. Its update deadline is below the polling interval, supporting Room-driven refresh. It proves cold Activity behavior, not process death or physical sensor continuity.

Verified archived accounting:

- **244 JVM results:** core-ui 60, sensor 57, home 76, widget 51; zero failures/errors/skips. Includes reused outputs.
- **144 tablet methods:** historical `fa6ddd3…` binary, comprising 80 regression/layout methods and 64 host cases.
- **Two navigation methods:** final production Debug, separate from the historical 144.
- **Phone:** historical 64 host cases plus one startup case; exact phone APK was not preserved.
- Final build log reports success, **142 executed / 4 cached / 1,349 up-to-date** tasks.

Navigation cleanup uses unique fixture ownership, restores selection/tutorial settings and runs fixture cleanup even when Activity closure fails. This is strong source-level cleanup design; I did not independently inspect post-run device storage.

Archived Release manifest/signature evidence supports package `dev.pampa.fluidweather`, version **2.1.2/code23**, ARM64/ARMv7 and v2 signing. The Release was **not installed over the principal app and is not device-tested**.

The generic implicit URL smoke failed and supplies no passing evidence. Its missing VIEW filter is consistent with the manifest; production widget intents target MainActivity explicitly.

**Remaining OPEN limits**

Weather accuracy, controlled whole-app p95/memory, process death, multi-day continuity, complete launcher/resize behavior and broader OEM alarm behavior remain unestablished. They are not newly imposed acceptance requirements.

Independence note: an initial memory-index search surfaced historical review metadata. It was excluded from this assessment; no historical review report, current reviewer verdict or forbidden ledger was opened.
