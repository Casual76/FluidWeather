**Verdict: CORRECT.** The five correction targets are materially improved, but one residual pressure-summary defect prevents Q5 approval. This defect predates correction1; I confirmed it in the base HEAD.

Reviewer: **GPT-6.1 Sol, high effort**. Candidate: **Q-R1-fae0169b347e**, correction1/3.

| Criterion | Score | Assessment |
|---|---:|---|
| Q1 — Modal optical ownership | **8** | Both backdrop channels belong to the opaque modal scene. Controls no longer sample Home’s sky; viewed captures retain clear text and icons. Home glass remains visually intact. |
| Q2 — Precipitation readability | **8** | Individual `1%`, `<1%`, `0%` and `2%` values are readable. Six-hour default, explicit navigation and collapsed tables replace the original technical wall. Missing samples and partial coverage remain distinct. |
| Q3 — Useful initial content | **8** | Pressure, Moon, Details and Nowcast lead with their own information. Diagnostics remain expandable. Sun annual dates now occupy readable full-width rows, including the native EN/font2 viewport. |
| Q4 — Functional integration | **8** | Sufficient for this bounded correction: archived real-app tablet flows, round0 phone coverage, and current native 360dp Hourly scrolling/closing evidence cover the affected paths. Current complete phone execution remains **NV**, explicitly accepted as a coverage limitation rather than relabelled as passed. |
| Q5 — Qualified weather and preservation | **7** | Rain qualifications, missing/zero/trace handling and clock disclosures improved. Pressure still presents a potentially distant sample as a three-hour forecast and derives a misleading rate. |

No averaging: Q5 remains below acceptance.

**P2 — Pressure summaries still conceal elapsed-time gaps**

[PressurePage.kt:231](</C:/VibeCoded Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/PressurePage.kt:231>) selects the **first available sample at or after +3 hours**, without an upper tolerance. Line236 divides its change by exactly three; lines240–249 apply three-hour interpretations.

Concrete source-derived reproduction, **not executed**: provider pressure exists at `t0 = 1020hPa` and `t0+17h = 1016hPa`, with intervening pressure samples missing. The page displays:

- “In 3 hours”: `1016hPa`
- Trend: approximately `−1.33hPa/h`
- A three-hour storm interpretation

Those values describe a seventeen-hour difference. The corrected chart would show the gap faithfully while the headline still misstates it.

Correct by requiring a sample sufficiently close to the intended +3-hour target; otherwise show unavailable and omit the three-hour interpretation. A separately labelled rate must use actual elapsed time. This is presentation logic, requiring no provider/model change.

The reviewed technical changes otherwise hold up:

- [BlackSheet.kt:242](</C:/VibeCoded Projects/FluidWeather/core-ui/src/main/kotlin/dev/pampa/fluidweather/core/ui/BlackSheet.kt:242>) supplies both optical channels from a local recording of opaque background paint, outside the content subtree. This avoids Home leakage and self-sampling.
- The new width parameter reaches Material’s actual `sheetMaxWidth`; production retains its existing default. Hourly tests invoke the same production sheet used by `WidgetSheetHost`.
- Hourly metric/imperial tests genuinely assert null, zero and trace amounts, scroll to hour70, return and dismiss. They do **not** exercise Home’s launcher path or phone hardware.
- Timestamp geometry uses elapsed-time positions and explicit segment breaks. Pressure labels use the temporal midpoint. Geometry tests support that logic; the viewed tablet pressure image does not itself demonstrate a sensor acquisition gap.
- Device-zone disclosure matches detail formatting; Details’ sample qualification uses that same zone.

I actually viewed all seven mandatory R1 images, the five requested round0 phone images and all three original attachments. The recovered presentation is substantially clearer than the originals. Round0 captures were used only for unchanged presentation context. Blank Compose exports were excluded.

My checks were read-only source inspection, image viewing and identity verification. The **76/76 instrumentation results**, **160 JVM XML results including reused outputs**, release signing and ABI claims remain parent evidence. I independently confirmed APK hashes against metadata, including release SHA256 `aa294459ff0ab2cc4b764b545a4c244a5e4d3efc88226ae31ef682c9f607ea99`; I did not independently execute signature verification or tests.

**Remaining boundaries:** current complete phone flow NV; fresh launcher-widget execution NV, with unchanged widget production code and preserved model-test evidence; physical barometer accuracy NV; C2 field accuracy and C6 controlled performance outside this acceptance scope. No observed failure is inferred from those limitations.

Before/after identity checks matched:

- HEAD: `88655da79e208819da16c251b8b2051f969f4cda`
- Engine: **3.0.5, clean**
- Manifest SHA256: `17d4802d39ff02a2042160aa46a22b02ce39a53858dcaeb0857965b7fb1ed2cc`
- ZIP SHA256: `0e5ee1409e95a9d0c64f75f44829d0a0cb06f7c6290b7796da24626ef831e117`
- All **745 listed files** matched checkout and ZIP; external APK hashes matched.

Advance after correcting the pressure-summary gap handling and providing focused evidence. No files were written.
