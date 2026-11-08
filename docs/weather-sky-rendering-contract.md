# Sky rendering prototype contract (allocation B)

## Integrated override, 2026-11-08

The allocation notes below describe the historical prototype. The candidate removes the entire optical Deck pass after the user's gray-stripe feedback. Overcast/wet scenes instead use four stratified soft Cumulus groups (three in REDUCED/STATIC), confined by the upper-sky coordinate space. No cloud atlas or experimental CloudVolume field remains. Existing multi-pass puff highlights/shadows are retained; these are procedural artwork, not physical volumetric simulation.

`WeatherScene` prepares geometry on Dispatchers.Default and only draws a painter matching the current state/quality/day request. Its astronomical minute clock is independent of the draw-frame clock. Existing visibility, lifecycle and reduced-motion gates remain. The shared evidence adapter is used by Home and widget; a fresh hourly atmospheric sample keeps full valid-time weight within half an hour, then fades to zero at 90 minutes. Original download freshness is checked independently.

Root rendered the nine-case/four-phase/three-quality matrix in `artifacts/quality-2.1.2/premium-upper-cloud-matrix`. STATIC pixel equality, no-flash and malformed-input finiteness assertions passed. These are synthetic artwork checks, not Android performance or local weather validation. The physical tablet Home capture `premium-tablet-upper-clouds.png` shows the current partly-cloudy scene and glass action. Final integrated verification is recorded separately.

Historical allocation status: separate unbuilt prototype. The remainder records the original proposal, including rejected Deck details; the override above describes the current implementation.

## Input and compatibility

Consumes A's exact `SkyAtmosphere` and trailing nullable `SkyState.atmosphere`. B does not declare either type. Values are optical art-direction controls, not measurements. Coverage is area; depth is optical thickness; darkness is underside density. Diffuse light suppresses the direct solar disc and bright edges. Haze washes out the distant horizon. Wind is a bounded speed multiplier, with a single rightward convention because the input has no direction. Precipitation strength scales an already permitted condition's particle budget; it never creates rain from a dry kind. Storminess does not cause flashes or promote precipitation.

A must supply the atmosphere only after evidence reconciliation. B retains `forRendering()` as the precipitation gate. Nonfinite controls fall back conservatively, normalized fields clamp to 0..1, wind to 0.25..2. Missing atmosphere uses existing condition/coverage defaults, including widget/preview constructors. Null defaults preserve API compatibility, not pixel identity: depth edges, deck geometry and wind coherence improve for legacy callers too. Random lightning is disabled for all callers; there is no discharge evidence in this contract. `flashing()` remains callable and returns false.

`SkyPalette.Sky` adds a trailing optional atmosphere field. Existing `copy()` in WeatherScene retains it while animating colors. No new user-facing strings, provenance claims, textures, icons, layers of glass or rendering entry points are introduced.

## Composition and cost

The shared production path remains `SkyFrame` / `SkyScenePainter`: gradient, stars, astronomical body, distant cirrus, optical deck, near cloud masses, horizon haze, fog, precipitation. Existing astronomical placement, lunar phase and snow/fog whitening remain. The deck's thickness changes its vertical extent and mass aspect ratio, while its underside and upper shelf differ in tone. Thin pale stratus can have near-total cover without a dark storm ceiling. Distant cumuli have softer edges; near masses retain firmer lobes and a flat shaded base. Solar transmission and diffusion attenuate direct light; clouds still occlude the body in painter order.

FULL optical scenes cap cumuli at four plus 22 deck blobs, eight scud fragments and one small cirrus cluster. REDUCED/STATIC cap cumuli at two, deck blobs at 12 and scud at four; REDUCED omits highlight shelves and cirrus. Geometry is seeded and created once per painter. Existing radial brush reuse is retained; two deck gradients are now cached by palette and bounds. Haze adds one cached blob, not an offscreen pass. Palette transitions and size changes rebuild brushes. Existing cache lookup keys, celestial/sky gradients and caller palette copies still allocate; no allocation-free claim is made.

STATIC clamps time to zero inside the shared painter, including stars/clouds/fog; existing STATIC precipitation has no particles. FULL/REDUCED retain the caller's time, lifecycle and reduced-motion policy. No scheduler or redraw cadence changes are made. Changing atmospheric inputs may rebuild a painter (as existing state changes do); root must check transitions and glass capture rates.

## Reproducible review inputs

New `tools/skypreview/src/main/kotlin/AtmospherePreview.kt` supplies nine deterministic synthetic cases: clear, distant broken cloud, filtered sun, pale drizzle, dry dark front, dense rain, snow, fog and legacy null. Each phase sheet compares all three quality modes, using June 15 2026 Firenze fixture times and the actual production painter. A fixed seed allows repeat comparisons. Four drizzle frames cover 0/4/8/12 seconds. The fixture includes executable pixel-equality assertions for STATIC, no-flash assertions and a malformed-input finiteness assertion. These assertions have NOT been run.

After A integration, root can run from repository root in PowerShell:

```powershell
.\gradlew.bat -p tools/skypreview installDist
java -cp 'tools/skypreview/build/install/skypreview/lib/*' AtmospherePreview tools/skypreview/out/atmosphere
.\gradlew.bat -p tools/skypreview run --args='--only=day,dawn,dusk,night,widget,moon,variants'
```

The existing staging whitelist copies SkyState.kt, SkyPalette.kt, SkyPainters.kt and Celestial.kt. If A places SkyAtmosphere in a separate source file, root must add that filename to `stageSkySources` before compiling. B deliberately leaves the build file untouched. The new main is separate, so the original preview command remains valid.

Root should inspect the PNG grids at phone and widget sizes, then record moving FULL and REDUCED with the same seed and 0/4/8/12 second times. Verify low-contrast pale drizzle vs dense front, underside shape, sun attenuation, moon visibility, fog/snow brightness, rightward precipitation/cloud agreement and absence of looping-edge jumps. Clouds are procedural masses, not simulated fluid or volumetric ray marching; local coverage is not a map of actual cloud positions. Deck activation at coverage .38 and cumulus-count boundaries can change topology when new data arrives. Review those transitions before shipping.

For bounded performance comparisons, reuse one painter, bitmap and CanvasDrawScope per case; warm up 30 frames, then measure 300 frames at 360x640 and 1080x2340, using t=index/30. Compare FULL/REDUCED with the baseline on the same renderer, report p50/p95 frame time and allocation deltas; do not benchmark `SkyFrame` construction as animation cost. On root-owned device QA, compare glass capture counts, frame timing and allocation behavior, including off-view/background/reduced-motion cases. Desktop timing cannot certify Android GPU or glass performance. Root owns selecting and running the project's appropriate unit/build checks after integration.

## Validation performed here

Read source/contracts and inspected the diff only. No builds, Gradle, devices, test runners or preview rendering were run. No standalone test suite was added; the new preview assertions remain unexecuted. Root must compile against A's API, run the fixture assertions and complete visual/performance QA plus independent review before promotion.
