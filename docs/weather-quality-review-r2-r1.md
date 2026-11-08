Independent technical, surfaces and integration review — FluidWeather 2.1.2, **correction2/3**. Model: **GPT-6.1 Sol**, effort: **high**. Frozen version: **Q-R2-0aad796e4a07**. Base HEAD: `88655da79e208819da16c251b8b2051f969f4cda`.

**Verdict: OPEN-GATE.** The correction-2 implementation satisfies the targeted pressure and chart requirements. I found no reproducible product defect requiring another correction. Q4 remains partially verified because current full-app phone evidence is unavailable. The narrow production-sheet tests provide useful substitute coverage, but do not establish the complete phone integration required by Q4.

| Criterion | Score /10 | Verification state | Evidence and reason | Severity / remaining gap |
|---|---:|---|---|---|
| Q1 — Details own their backdrop | **8** | Satisfied within scope | Independent backdrop/palette trace; regular/lazy modal pixel assertions in archived tests; inspected Pressure, precipitation and Moon controls show no Home sky inside them. Home glass remains visible. | No blocking defect found. |
| Q2 — Readable, bounded precipitation | **8** | Satisfied within scope | Six-hour default, day/window selection, optional hourly table and sources; inspected rendered percentages and zero marker. Assertions distinguish missing, zero and small positive values. | No blocking defect found. |
| Q3 — Useful data first | **8** | Satisfied within scope | Inspected Details, Pressure, Moon, Sun, air quality, daily and archived Nowcast captures. Production disclosures start collapsed; repeated rain overview is absent from Details/Pressure/Nowcast. | No blocking defect found. |
| Q4 — Real-page interaction on phone/tablet | **7** | Partially verified; current full-app phone **NV** | Current tablet Pressure/Details captures and trees; archived interaction suite; real Material 360dp hourly sheet with scrolling and closure. Earlier phone captures belong to round0. | Validation gate, not a demonstrated product failure. |
| Q5 — Honest qualification and preserved presentation/widget | **8** | Satisfied for reviewed presentation scope | Exact-endpoint pressure logic, timestamp-preserving curves, qualified forecast anchor, explicit rain gaps/source clocks; Home imagery preserved. Widget production files unchanged relative to base; 45 archived unit results provide limited support. | Fresh launcher/widget execution **NV**; no broad runtime claim. |

Eight means satisfied with evidence; scores are not averaged to override Q4.

**Identity and independence**

Before and after inspection:

- HEAD matched the supplied base.
- `review-input.json` matched SHA256 `393ba23a526744264aa2ddbcca42e5847acfed553b8611efb902475042487ad0`.
- `review-input.zip` matched SHA256 `0fcb8078231662187ab01f7aefed98bd3c1ec883b4f1a5ba1494ba9e4c2f2438`.
- All **766 manifest-listed checkout files** matched their hashes.
- All 766 corresponding ZIP entries matched size/hash. ZIP contained 767 entries including its manifest and **zero APK entries**.
- The supplied installed-debug copy hashed to `7bc2a4ad0c27374eddc3c81950bd689fa7a1065881adad2c46971adf5dd36476`, matching candidate metadata and execution provenance.

I read the applicable Fluid Engine and material3-expressive skills, clean acceptance rubric, factual verification document and release notes. I did not consult prohibited review documents, ledgers, handoffs, memories or rollout histories.

**Correction-2 falsification results**

1. **A +17-hour pressure sample cannot substitute for +3 hours.**
   `feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/PressureForecast.kt:10` requires one finite endpoint exactly at anchor +3h, and a unique finite anchor.

   Concrete input: `(0h,1020)` and `(17h,1016)` returns unavailable by source inspection. In `PressurePage.kt:225`, unavailable outlook yields “—” for both future pressure and trend; interpretation is conditional on a non-null change. The +17h sample therefore cannot trigger the three-hour storm/change/normal interpretation.

   Supported control input: `(0h,1020)`, `(3h,1018.5)`, `(17h,1005)` uses 1018.5 and −0.5 hPa/h. Near-but-not-exact, duplicate and nonfinite endpoints are covered by meaningful helper assertions.

2. **Anchor and horizon are qualified and bounded.**
   `PressureForecast.kt:25` filters `[current-hour start, start +48h)`, then sorts and rejects ambiguous/nonfinite samples. Missing values cannot extend the horizon to obtain 48 available points. `PressurePage.kt:217` dates the first retained forecast sample, with “3 hours later” explicitly relative to it.

   The inspected current tablet screenshot and matching tree show `PREVISIONE · VALIDA 8 NOV, 13:00`, followed by the forecast value and `3 ORE DOPO`. This is forecast qualification, not proof of a local measurement.

3. **Details retain value/time pairing and genuine gaps.**
   `DetailsPage.kt:50` bounds the selection in elapsed time. `PageKit.kt:249` derives curve values/labels from paired samples; `PageKit.kt:255` sorts timestamps and rejects duplicates/nonfinite values. Details passes matching timestamps and a 90-minute gap threshold to each affected curve.

   `core-ui/src/main/kotlin/dev/pampa/fluidweather/core/ui/TimedLineGeometry.kt:4` computes elapsed-time fractions and splits unsupported intervals. `Charts.kt:28` draws those segments separately, including isolated samples as dots. For timestamps 0h/17h/19h, the middle position is 17/19 of chart width; it is not the middle index position. The accompanying gap disclosure is wired through production rendering.

**Surfaces, lifecycle and regressions**

`core-ui/src/main/kotlin/dev/pampa/fluidweather/core/ui/BlackSheet.kt:230` creates a modal-local backdrop and supplies it through both engine channels. Its recorded opaque paint is a sibling below content, so controls do not record themselves or inherit Home’s sky. The modal also supplies its dark surface palette.

Production wiring reaches this shell through `WidgetSheets.kt:39`; hourly uses the lazy variant, while Pressure and Details use the regular variant. Home dismisses the selected sheet through `HomeScreen.kt:373`. Archived surface tests exercise actual Material modal windows, distinguish both backdrop identities from Home, check light glyphs, change the parent sky red→green and compare control pixels.

I inspected the six original defect images and supplied evidence using `view_image`, including all three current correction-2 captures, native hourly/Sun proofs, full-app Sun, Home, precipitation, Moon, air quality, daily and the earlier phone Nowcast capture. The observed controls and content support the stated presentation fixes. Static captures do not establish transition smoothness.

The native hourly image is a **real 360dp Material window on the tablet**, not a full phone. The Sun image is a **360dp/font2 test viewport**; its external white area belongs to the host. The full-app tablet Sun image separately supports production insets and scrollable content.

**Execution evidence and limits**

My own executed checks were read-only hashes, archive inspection, source/test/log/XML/tree inspection and image viewing. I ran no build, test runner, device, AVD, provider or network operation and modified nothing.

Archived parent evidence:

- Final correction-2 Android log: **79/79**, 112.127 seconds.
- First 79-case run: one selector failure matching two “—” siblings under a flattened semantic parent. The final selector retains label-specific value assertions and absence-of-storm/change assertions.
- Preserved pressure red XML/log: **2 tests, 1 failure**, reproducing the false −4 hPa/three-hour result. Final helper XML: **7 tests, zero failures**.
- Canonical JVM XML totals independently counted: **43 UI +23 strings +58 Home +45 widget =169**, zero failures/errors/skips. These include reused results; they are not 169 fresh executions.
- Release metadata reports 2.1.2/code23, release SHA256 `18b59088af6f8984a24c959b16e23b8e355fd4ddf2f99423e9fda18b0ab21509`, v2 signature and matching previous certificate. I did not independently rerun signature verification.

**Remaining gate:** current full-app phone verification after the documented Device-managed AVD boot failure. Fresh launcher/widget execution also remains **NV**. Existing narrow-window tests and earlier phone captures reduce uncertainty but do not constitute a current full-app phone run.

Physical barometer behavior, live IA/audio and field accuracy remain **NV**. Controlled performance and a broad TalkBack/contrast audit are **NA to this presentation acceptance scope**. None is inferred from the 79 UI tests.
