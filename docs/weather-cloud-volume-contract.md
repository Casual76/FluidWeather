# Procedural cloud volume

Scoped implementation after fast-forwarding local `85ee7cf`. The soft, shaded procedural
language in `8e97f75` is the reference; the rejected atlas is not used. This is synthetic
illustration, not inferred or observed cloud geometry. User visual judgment is the acceptance
gate; these probes cannot establish realism.

`SkyScenePainter` replaces its cloud pass with `CloudVolumeLayer`; the existing gradient,
stars, celestial body, fog and precipitation order remains. The same painter serves SkyFrame,
desktop and widget callers, with no new public API or external wiring required. The previous
CloudLayer implementation remains available in source as the reference, but is not drawn.

The bounded 64x40 field integrates four decorrelated depth slices of warped, multiscale value
noise. Beer-Lambert attenuation supplies opacity. Overlying mass, local density differences,
height and darkness supply internal shade; diffusion softens contrast. Sixteen mass and eight
shade contours are extracted once with shared triangle edges and even-odd holes. This avoids
large circular primitives and a continuous gray texture overlay. It approximates scattering
and occlusion; it is not a physical ray marcher. The celestial light direction controls a
cached illumination gradient. Coverage, depth, darkness and diffusion remain continuous;
wind controls slow bounded lateral advection. There is no cloud boiling or clock-driven
density regeneration. FULL and REDUCED share cloud geometry; existing precipitation/star
quality differences remain. STATIC fixes cloud displacement to zero.

The contour coordinate system is inset into the upper 52% of the scene. Density has an uneven,
soft lower termination before that boundary. Only the cloud pass clips there; existing optical
background tint may affect the entire sky. Dense CLOUDY should occlude the upper sky while
retaining mottled illuminated shoulders and shaded bases. Sparse coverage should have holes.
Legacy null-atmosphere callers get continuous optical defaults, including a dense CLOUDY
ceiling. An explicit atmosphere takes precedence over the condition code. No lightning is
introduced; the existing evidence policy is untouched.

Geometry is cached in a synchronized four-entry LRU by optical inputs and daily seed. Wind,
haze, precipitation, storminess, canvas size, light position and minute clock are excluded.
Each painter owns its mutable brushes; cached paths are read-only after construction.
Drawing submits 24 paths, with no density integration, tessellation or brush allocation in a
steady frame. Palette changes and celestial movement rebuild two small brushes. The transform
scales normalized contours to phone and widget without size-triggered regeneration.

## Parent verification (not executed by this implementation agent)

From the repository root, serialized with other rendering work:

```powershell
.\gradlew.bat --offline -p tools/skypreview run --args="--cloud-volume"
.\gradlew.bat --offline :core-ui:testDebugUnitTest --tests "dev.pampa.fluidweather.core.ui.CloudVolumeTest"
```

The first command stages the actual helper alongside SkyPainters, runs deterministic raster
probes, and writes `tools/skypreview/out/cloud-volume/`: six sheets (360x640 and 220x97,
day/dusk/night), all three quality modes, plus individual FULL t=0/t=12 images. Cases include
broken, filtered, overcast, dark dry, pale and legacy CLOUDY. It asserts STATIC pixel equality,
moving-frame inequality, no lightning, and transparent cloud-only pixels below 53% height.
The unit probes check finite deterministic density/shade, transparent bottom, overcast
occlusion plus nonuniformity, monotonic coverage/depth and small optical perturbations.
They are deliberately synthetic regressions, not visual approval.

Inspect isolated images at phone size and widget sheets for contour banding, diagonal facets,
clouds resembling smoke, flat overcast and an abrupt boundary. Compare t=0/t=12 and physical
FULL/REDUCED/STATIC. Check that sun/moon remains behind the cloud ceiling. The desktop driver
does not replace physical QA, frame timing or memory profiling.

## Cost and limits

This chooses bounded Canvas geometry rather than a pixel bitmap kernel or asynchronous image
delivery. A cold cache entry performs field integration and 24 contour extractions in the
painter constructor, synchronously, outside `draw`. It reuses roughly 132 KB of temporary edge
workspace across contour levels, plus two 2665-float fields, and retains only native contour
storage per cache entry. Exact path/native memory and cold latency are
unmeasured. At worst each contour can contain thousands of vertices; four entries bound cache
retention, while live painters can retain evicted entries. A new optical input can therefore
cause a UI construction hitch, and 24 complex path submissions may be costly on low-end GPUs.
No claim of a measured frame budget is made. There are no worker threads, bitmap uploads,
per-frame field arrays, per-minute field keys, external assets, or Android-only effects.

If parent profiling finds cold construction too heavy, the exact follow-up boundary is to
prepare the immutable geometry on the existing rendering worker before creating the painter,
with a completion invalidation for STATIC and a widget prewarm before rasterization. That
would need lifecycle wiring outside this narrow patch; do not disguise it as verified here.
Build, tests, desktop output, devices and performance were intentionally unrun in this task.
