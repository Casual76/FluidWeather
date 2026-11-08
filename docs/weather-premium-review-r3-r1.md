**PASS for this independent R1 technical/concurrency review. Collective delivery gate: OPEN for root to reconcile all three reports.** No blocking correction found.

Reviewed **Premium-R3-f8af8d8afa54**, FluidWeather **2.1.2/code23**, using **GPT-6.1 Sol, high reasoning**.

| Mandatory criterion | Score | Evidence and limits |
|---|---:|---|
| P1.sky | **8/10** | Inspected actual retained tablet Home, day/night sheets and painter/evidence sources. Upper soft clouds remain; continuous Deck stripe is absent; glass action and foreground are coherent. Shared evidence gates and motion/lifecycle controls remain. Animated device quality and whole-app GPU cost remain NV. |
| P2.widget | **8/10** | Inspected compact stale, wide stale, tall current and empty PNGs. Essential data/time labels fit; place-name truncation is deliberate. Production acquisition is cache-only; four responsive layouts share three bounded bitmaps. Historical framework-host execution and current native navigation support the implementation; complete launcher/OEM coverage remains NV. |
| P3.reliability | **8.5/10** | Actual ordinary caller now handles strict acquisition failures. Independent hydration retains per-field failure handling, empty replacement, polling and generation/cancellation guards. Actual Compose caller/API regression and archived execution support retention, completion, recovery and cancellation. Physical-sensor continuity remains NV. |
| P4.regression | **8.5/10** | R2 comparison limits changes to Home caller/dependency injection, baseline constructor, two tests and factual documentation. Model, schema, artwork, widget content, feedback and selected-place persistence remain unchanged. Test mutable stores are isolated. |
| P5.delivery — own evidence portion | **8.5/10** | Independently verified frozen source/ZIP/APK hashes and sizes, source correspondence and XML accounting. Archived build, doctor and signature evidence is consistent. Release-device execution remains NV; three-report acceptance belongs to root. |

Every scored mandatory criterion independently meets **≥8**; no averaging applied.

The correction addresses the actual failure boundary in [HomeData.kt](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/HomeData.kt:350). `homeCatching` surrounds both dispatcher switching and `evaluate`, rethrows cancellation, and publishes local-result fields only after success. Ordinary failure preserves the current local fields and continues air/name work. Loading and refreshing finish before those auxiliary calls. Successful empty acquisition still replaces previous local values.

Concurrency protections remain substantive: ordinary state writes check coroutine activity and generation; independent reads check after acquisition and after returning to the publishing coroutine. Local hydration starts independently of GPS/weather, preserves failed fields individually, and retries through observation signals and polling. Strict API reads propagate acquisition errors; existing consumers retain the default optional-read behavior.

The new caller test composes **actual `rememberHomeState`**, using the real use case, repository and cleaner. Its pressure DAO rejects writes; snapshots/cache and baseline DataStore are private; history is in memory. Shared calibration/learning/settings dependencies are read only along this journey. The Context baseline constructor retains the same preferences store and keys. The location-name callback defaults to the original lookup. This establishes caller/API coverage, without establishing rendered Home or physical-sensor behavior.

One **low-priority, nonblocking test improvement** remains:

- **Reproduction:** auxiliary HTTP throws immediately and name resolution returns immediately, so assertions observe completion after auxiliary work.
- **Gap:** the test does not directly prove flag completion while auxiliary work remains suspended.
- **Minimum improvement:** gate an auxiliary callback, assert `loading == false` and `refreshing == false` before releasing it, then confirm continuation. Current production ordering is correct by inspection.

My executed checks were strictly read-only:

- Before/after HEAD: `e118fc81030b3cdd009706e87c562a7ca2246959`; working tree clean throughout.
- All **852** manifest files match current bytes and ZIP entries. Aggregate: `f8af8d8afa54b9c6feaf5903c8fec81158b58e32ca13b151d5e2116b0a1c506a`.
- ZIP SHA256: `affc6756b60b962c9047e936386a8f80989bdf7f80b3039452dc6b551cfea4ad`. Its additional entry is `review-input.json`.
- All three external APK sizes/hashes match before and after.
- Integration `958ba33` and production `905f84e` have matching production/test content after line-ending normalization; remaining tracked differences are documentation.
- Archived JVM XML totals independently recount to **620**, with zero failures/errors/skips.

Archived runtime evidence shows **4/4 Home/API methods in 2.403s**, **2/2 navigation methods in 6.437s**, signed Release build success in **3m46s**, and clean doctor **3.0.5**. The initial **3/4** harness failure remains disclosed; the final bounded Compose wait preserves entry/cancellation assertions. The **620 XML results include reused outputs**, and historical host **64+2** execution is not fresh R3 execution.

Signature logs agree on the current/prior stable certificate. I did not rerun signature tooling, builds, tests or devices. Release-device behavior, process death, multi-day sampling, field accuracy, complete launcher/OEM behavior and live AI remain **NV**. No files were written, and no prohibited repository verdict files were read.
