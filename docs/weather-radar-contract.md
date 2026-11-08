# Radar source contract - F2 B

Integration handoff for F2 C. Tests are written but **not executed**; parent owns integration,
compilation and verification. C1/C7 are decided after integration; C2 accuracy and C6 comparison
remain open. No field accuracy claim, new source, training, engine upgrade or UI producer.

## Official source and provenance

Verified 2026-11-07 against primary documentation:

- https://www.rainviewer.com/api/weather-maps-api.html : past two hours, ten-minute cadence;
  frame time is composite generation time, not a known physical measurement time. Coverage URL:
  `{host}/v2/coverage/0/{size}/{z}/{x}/{y}/0/0_0.png`. Transparent pixels mean supported;
  opaque black means outside. Unrecognized mask colours are unknown.
- https://www.rainviewer.com/api/transition-faq.html : future nowcast discontinued January 1,
  2026, max zoom 7. This supersedes earlier notes about future frames moving to a paid plan.

All source signals retain `WeatherEvidenceType.RADAR_ESTIMATE`, stable ID `rainviewer-radar`.
No echo is a source-local signal, not proof of dry ground. The legacy nowcast adapter still
uses `RainObservations.SOURCE_RADAR`; its dBZ-to-rate conversion remains an estimate.

## Exact API (package dev.pampa.fluidweather.core.weather.radar)

```kotlin
enum class GeographicCoverage { SUPPORTED, OUTSIDE, UNKNOWN }
data class CoverageReading(val state: GeographicCoverage, val downloadedAtMillis: Long? = null)
object RadarSafety {
  const val MAX_FRAME_AGE_MILLIS = 1_200_000L
  const val FUTURE_TOLERANCE_MILLIS = 60_000L
  fun absoluteEta(frameTimeMillis: Long, eta: Eta, evaluatedAtMillis: Long): AbsoluteRadarEta?
}
data class AbsoluteRadarEta(
  val arrivalAtMillis: Long?, val endAtMillis: Long?,
  val horizonStartMillis: Long, val horizonEndMillis: Long,
  val transitionAlreadyPassed: Boolean,
  val temporalResolutionMillis: Long = 600_000L,
)
data class RadarEvaluation(
  val evaluatedAtMillis: Long, val usable: Boolean,
  val geographicCoverage: GeographicCoverage,
  val frameTimeMillis: Long? = null, val atPointDbz: Int? = null,
  val evidence: WeatherEvidence? = null, val eta: AbsoluteRadarEta? = null,
)
fun RadarSample.evaluateAt(evaluatedAtMillis: Long): RadarEvaluation
```

`RadarSampler.sample(latitude: Double, longitude: Double): RadarSample` remains suspend.
`RadarSample.Ok(reading)` and `RadarSample.Unavailable(reason, geographicCoverage = UNKNOWN)`
remain the two outcomes. Unavailable has no current-point value/ETA. The explicit OUTSIDE
state survives the evaluation adapter. Mixed support across the window is conservatively UNKNOWN.

New trailing `RadarReading` constructor properties (defaults preserve existing call sites):
`geographicCoverage: GeographicCoverage = UNKNOWN`, `sampledAtMillis: Long? = null`,
`manifestDownloadedAtMillis: Long? = null`, `tilesDownloadedAtMillis: Long? = null`,
`coverageDownloadedAtMillis: Long? = null`, `spatialResolutionMeters: Double? = null`,
`temporalResolutionMillis: Long = 600_000L`, `complete: Boolean = false`.
Methods `isUsableAt(nowMillis: Long): Boolean`, `absoluteEta(nowMillis: Long): AbsoluteRadarEta?`
recheck age at consumption time. Legacy manually constructed readings default unusable.

F2 C usage:

```kotlin
val sample = sampler.sample(latitude, longitude)
val radar = sample.evaluateAt(clock()) // recheck after network time has elapsed
// usable=false => no source-local wet/dry or imminent transition assertion.
// usable=true => atPointDbz is known; >=10 is the legacy radar wet threshold.
// Merge this evidence with independent model signals in the central assessment policy.
// Carry radar.evidence, absolute ETA/horizon and limits; do not expose raw frame-relative Eta.
```

`WeatherEvidence.effectiveInterval` is deliberately null: a physical effective interval is
unknown for this composite. `frameTimeMillis` separately identifies generation time. Evidence
uses original tile download (minimum of constituent tiles, or null if any origin is unknown),
600000 ms temporal resolution, local Mercator metres/pixel, COARSE_TIME_RESOLUTION and
COARSE_SPATIAL_RESOLUTION. Unusable readings retain evidence with STALE/PARTIAL_COVERAGE where
applicable; unavailable samples have no fabricated evidence. F2 C must not invent an effective
measurement interval from the cadence or assessment clock. Manifest and mask download metadata
are available on the raw reading and are not interchangeable with radar image download time.

## Safety rules

- Raw `Eta.arrivesInMin/endsInMin/horizonMin` remain relative to `RadarWindow.timeMillis`.
  Absolute ETA = frame generation + raw minutes, never evaluation + raw minutes.
  A 12:00 frame with +20 at 12:12 yields 12:20 (8 remaining). At 12:21 it is unavailable due
  to the hard age cutoff. Expired transitions within freshness are null with
  `transitionAlreadyPassed=true`, never clamped to now. After the trajectory horizon ends,
  a previously passed transition is still retained with null arrival/end until the frame's
  20-minute cutoff. An expired horizon without any passed transition returns null. This keeps
  uncertainty monotone instead of restoring the pre-transition frame signal.
- Age <=20 minutes is accepted; one millisecond older is rejected before tile requests and
  rechecked after sampling and by consumers. Future frame tolerance is explicitly 60 seconds.
  Duplicate/non-increasing past times and overlaps with nowcast identity are rejected.
  Future lists are never sampled or promoted to past observations when clocks advance.
  `nowcastAtPoint` is retained for constructor compatibility and always empty.
- Parent integration guard: absolute ETA additionally requires nonstationary, agreeing motion,
  no search-edge truncation, mean PSR >=3 and finite heuristic quality >=0.4. A valid current
  frame may remain usable when the transition is not estimable. These gates are conservative
  heuristics, not calibrated accuracy or probability claims.
- Source cadence is ten minutes; trajectory step five minutes does not create five-minute
  source precision. Chat labels it as an estimate and gives cadence/limits. Heuristic confidence
  is qualitative, never a calibrated probability percentage.
- Geographic mask is separate from `RadarWindow.coverage` (download/palette completeness).
  Initial policy requires the entire latest window supported AND all radar pixels known.
  This deliberately loses availability near boundaries. 404 is unavailable raster, not dry.
  A partial neighbourhood containing any unknown pixel has unknown `maxAround`, even with
  known neighbours. Unknown palette remains unknown; no conversion to zero.
  Failures after a supported mask (missing/invalid raster or frame expiring during sampling)
  retain SUPPORTED geography; consumers expose unavailable radar data rather than unknown support.
- Motion math is unchanged. Only complete windows enter it; gaps clear accumulated motion
  pairs, so only consecutive ten-minute pairs in the latest segment can support ETA.
  Unknown trajectory neighbourhood truncates the usable horizon before the gap, never
  deriving cessation or later arrivals across missing data.
- `RadarObservations.of` requires a usable sample and existing confidence gate. Missing,
  outside, unknown, partial or stale samples yield null, never `RainObservation.dry`.
  Coroutine cancellation propagates through manifest, sampler, tile download and adapter.
- Legacy `RadarAroundTool` reads its injected consumption clock after place resolution and
  radar sampling, independently of the chat-start context clock. Age, safe ETA and remaining
  minutes use that one consumption time. A passed transition returns an explicit unavailable
  current-state explanation requiring a new frame, with no future arrival/cessation claim.

## Cache and wiring

`RadarFrames` (weather package) adds trailing `downloadedAtMillis: Long? = null` and
`coverageUrl(zoom: Int, x: Int, y: Int, size: Int = 256): String`.
`RainViewerClient.frames()` uses `ProviderHttp.readJsonTimed`, preserving URL-cache origin.
`TileResult.Ok(raster: Raster, downloadedAtMillis: Long? = null)` and
`RadarWindow(size, dbz, timeMillis, downloadedAtMillis: Long? = null)` add optional origins.

`RadarTileStore` stores new successful-download timestamps in `<hash>.downloaded` sidecars.
Old PNGs without sidecars remain null and their mtimes are not rewritten. New files receive
the download mtime. Coverage uses the same source/store/directory: one to four extra tile
reads per sample, independent of past frame count; successful masks refresh daily, failures
are negatively cached for five minutes. No per-pixel requests. Existing age/count pruning
bounds all files (including sidecars); it may evict masks earlier than the daily refresh.
Metadata independently evicted by pruning becomes unknown, never renewed. No memory cache.

AppGraph is unchanged: existing singleton `radarTiles`/`radarSampler`, `radar-tiles` directory,
prune/wipe hooks, shared AI/nowcast injection, and `NowcastEngine.trained(AnalogPolicy.OFF)` remain.
No changes to WeatherBrief, HomeData, core-model/core-data, provider current, snapshot or UI.

## Static evidence and pending verification

Changed tests: `radar/RadarSafetyTest`, `radar/RadarTileStoreTest`, existing
`radar/RadarSamplerTest`, `radar/RadarObservationsTest`, and `RadarClientsTest` radar cases.
Cover controlled UTC instants, relative-vs-absolute ETA, expiry, cutoff boundary, future tolerance,
ordering/duplicates, mask support/outside/failure, 404 vs clear raster, unknown centre, no stale
observation, metadata/cache reuse, cancellation and a trajectory gap before clear pixels.
Tests, Gradle, builds and device work were not executed. Parent must compile and run these
and the existing F1 wiring checks. No field observations establish C2/C6.
