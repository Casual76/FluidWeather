**R2 independent review — `Premium-R0-f1d8d8bb00bd6e`: CORRECT.**

Correction: the exact reviewed version is **`Premium-R0-f1d8bb00bd6e`**, HEAD `c6fe9385271d619277a3205c860e7a9b6c9bb03b`, baseline `8e97f750d8ae23c15b80fd9c89c199d2e3ef14b6`.

The visual direction satisfies P1. One widget regression prevents P2/P4 acceptance: hourly forecast fallback has lost its visible forecast qualification.

Review remained strictly read-only. No files, Gradle, test runners, devices, providers, network, secrets, delegation, other reviews or status ledgers were used.

| Criterion | Score | Gate | Assessment |
|---|---:|---|---|
| P1 — Sky | **8.5/10** | **PASS** | Upper composition, soft cloud volume, lighting and readable foreground supported by opened images and production code. |
| P2 — Widget | **7/10** | **CORRECT** | Supported layouts fit and prioritize useful data, but forecast fallback appears without qualification. |
| P3 — Reliability | **8/10** | **PASS** | Authorized hydration and failed-attempt recovery scope supported by code and bounded archived evidence. |
| P4 — Regression | **7/10** | **CORRECT** | Core protected paths remain unchanged; widget loses an existing distinction between current data and forecast. |
| P5 — Delivery | **8/10** evidence portion | **OPEN** aggregate | Artifact identity and archived checks are supported. Final delivery still requires corrected mandatory criteria and all three independent reviews. |

No average overrides either failed mandatory criterion.

**Required correction — medium priority: forecast temperature presented without its qualification**

- **Reproduction:** provide a cached snapshot with a nearby fused hourly temperature while the current summary is unavailable or has no temperature. `AppWidgetModelBuilder` selects that hourly value and sets `usesHourlyForecast = true`.
- **Production paths:** [AppWidgetModel.kt:121](</C:/VibeCoded Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/AppWidgetModel.kt:121>) supplies the fallback; [WeatherWidgetContent.kt:87](</C:/VibeCoded Projects/FluidWeather/feature-appwidget/src/main/kotlin/dev/pampa/fluidweather/feature/appwidget/WeatherWidgetContent.kt:87>) and its wide-layout equivalent render it as the dominant temperature. The new content never reads `usesHourlyForecast` or `forecastAtMillis`.
- **Evidence:** the baseline explicitly displayed localized “Previsione” and the forecast time for this case. The opened `110x110_it_1.5_stale.png` now shows place, **21°**, an icon and **9 h fa**, without identifying the temperature as forecast. Age communicates freshness, not evidence type.
- **Consequence:** a model value can be read as current weather. This violates preservation of missing/source semantics without requiring any new reporting design.
- **Minimum fix:** restore a short localized forecast qualification when fallback is used, within the existing text budget. Preserve stale age where applicable. Extend the existing host assertion to check the **visible qualification**: it currently checks that the stale model uses fallback, but does not require the UI to disclose it.

**Visual and material findings**

I actually opened the frozen tablet Home, day/night synthetic sheets, all four widget PNGs and historical phone startup.

The tablet Home gives place, temperature and condition clear priority. Clouds occupy the upper area; the rejected continuous gray Deck stripe and full-height cloud mass are absent. The soft highlights and undersides provide adequate volume within the previously accepted cloud language. The synthetic day/night sheets distinguish clear, filtered sun, pale drizzle, dry dark front, rain, snow and fog without turning every overcast sky uniformly dark.

Production code supports this assessment: [SkyPainters.kt:98](</C:/VibeCoded Projects/FluidWeather/core-ui/src/main/kotlin/dev/pampa/fluidweather/core/ui/SkyPainters.kt:98>) changes the cloud coordinate space to the upper sky, and stratified Cumulus groups replace Deck. Lightning is disabled. Atmospheric policy separates future optical context from present precipitation. Geometry preparation uses `Dispatchers.Default`; visibility, lifecycle and motion gates remain. These are bounded implementation findings, **not measured Android performance**.

Home actions are concise. Summary/source labels use foreground ink over glass, and “Aggiungi un momento” uses a real Fluid glass button. The historical phone capture shows the older cloud treatment and blue action labels; it supports historical startup context, not current visual approval.

The widgets have an effective data-first hierarchy: dominant temperature, compact condition/range, four hourly columns and bounded future-day rows where space permits. The opened large-font samples show no obvious clipping. Production uses real Glance content and shared sky painting, with three shared bitmap canvases totaling **432,960 pixel bytes**.

A **low-priority visual polish issue** remains: the sun overlaps part of the place text in `250x200_en_1.0_empty.png`. The scrim begins below the header and responds to the bottom gradient rather than local bright artwork. A modest header scrim or restrained glow beneath header text would improve consistency. This is a specific composition observation, not a demand for full contrast certification.

**Identity and evidence accounting**

Checks executed in this review:

- All **769 files** matched manifest byte lengths and SHA256 values in both ZIP and local files. The embedded manifest exactly matched the external manifest.
- All **703 manifested files tracked at HEAD** matched Git content: 88 byte-exact and 615 after Windows CRLF normalization.
- Current and historical APK hashes/lengths matched metadata; `candidate-apks.json` matched the manifest’s external APK records.
- Engine HEAD is `a055257fc4b559bee54b9ce114f265bcd237adfd`, clean. Engine, `core-data`, `core-model` and `nowcast` showed no baseline-to-reviewed-HEAD changes.
- Archived XML contains **244 cases**, zero failures/errors/skips: UI60, sensor57, Home76, widget51. This inventory includes reused outputs.

| External APK | Bytes | SHA256 |
|---|---:|---|
| Debug test | 1,213,294 | `5eb7571b300a13e7df3b572466b420e2c973fe3648f9717efe27584c50ca813e` |
| Debug | 73,353,983 | `82418cf4d852fd86d9802029c79fac928222a1786617ced82178a9065a9eb24b` |
| Release | 25,012,367 | `3c0e35a98ea39262ad8a26b57cc47088802389cd81a8530506db0c61a84dc07a` |

Archived evidence inspected supports successful final builds, clean engine-doctor, Release package/version **`dev.pampa.fluidweather` 2.1.2/code23**, and v2 signing verification. Those executions were performed by root, not rerun here.

The **144 tablet cases** belong to preserved `fa6ddd3…` binaries. Painter sources remain identical; the later content change preserves place identity in widget intents. The **two current Debug navigation methods** are separate evidence, not 146 cases rerun on the final binary. The historical **64+1 phone** results have no preserved phone APK and do not establish current-binary phone testing.

For P3, code inspection confirms independent local hydration, Room-triggered updates plus polling, cancellation/generation guards, registration cleanup, bounded silent attempts, zero-sample failure handling and restartable surveillance ownership. Short synthetic startup evidence supports the narrow behavior only.

**Remaining OPEN limits:** aggregate independent-review approval, actual launcher negotiation/PendingIntent delivery coverage, process death, multi-day sensor continuity, weather accuracy and controlled whole-app p95/memory comparison. Release was signed and identity-checked but was not installed over the principal app; it is **not Release device-tested**. The failed generic implicit URL smoke remains failed; explicit widget-intent navigation evidence does not convert it into a pass.
