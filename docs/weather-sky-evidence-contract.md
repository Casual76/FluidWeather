# Sky atmosphere prototype: allocation A

This is visual calibration, not validated outdoor accuracy. Rendering, device cost and independent
review remain root gates. No provider requests, keys, engine/dependency/version changes, builds,
test runners or device work were performed by this implementer.

## Integration API

Package `dev.pampa.fluidweather.core.ui`: `SkyAtmosphere(cloudDepth: Float = 0f,
cloudCoverage: Float = 0f, cloudDarkness: Float = 0f, diffuseLight: Float = 0f,
haze: Float = 0f, windSpeedFactor: Float = 1f, precipitationStrength: Float = 0f,
storminess: Float = 0f)`. All values are normalized 0..1 except windSpeedFactor 0.25..2.
`SkyState.atmosphere: SkyAtmosphere? = null` is an optional trailing parameter.
Null preserves legacy widget/preview behaviour. Existing `forRendering()` copies retain this field.

`SkyAtmospherePolicy.derive(SkyAtmosphereInput)` is Android-free. Its explicit inputs are
`SkyAtmosphereSample` (valid/download UTC epoch milliseconds, cloud/humidity %, visibility m,
wind km/h, supported mean precipitation mm/h), plus `SkyPresentPrecipitation` (shared state,
original source/download UTC times, optional supported rate, conflict flag). No implicit clock,
probability, sensor, pressure tendency or inferred lightning enters this policy.

## Inspected source and scope

The checkout's `Forecast.kt` has model `ProviderCurrent` (instantaneous kind/temperature plus
preceding precipitation accumulation), `MinutePoint` quarter amounts and `HourlyPoint` cloud,
humidity, visibility, wind and precipitation. `ForecastBundle` carries original download and
optional duration/anchor metadata. Current has no cloud/humidity/wind/visibility fields.
`Fusion.kt` has `FusedHour.values` with contributions, kind and wind direction, but no individual
download/precipitation-interval metadata. `WeatherSnapshot` distinguishes round time, context
download and context carry bookkeeping. `LocalPrecipitationEvaluator` and `WeatherBrief` already
distinguish WET/DRY/UNCERTAIN/UNAVAILABLE, original evidence clocks and conflicts.

The home adapter reads the existing local snapshot store, never the provider. Only its context
bundle within 2 km of the displayed coordinates supplies atmosphere. Round/dataAt time merely
invalidates the local read; it never rejuvenates evidence. Fused hourly precipitation cannot
establish current intensity. No broad provider or HomeUiState changes are necessary.

## Direct inputs and visual heuristics

- Cloud coverage, humidity, visibility and wind are direct **model fields**. They are not new
  observations. The nearest hourly context is accepted within 90 minutes. Valid-time distance
  continuously reduces its visual weight; source download weight is full through 60 minutes,
  fades until 90 minutes and then becomes zero. Missing/future download cannot establish freshness.
- A cloud model percentage cannot establish optical thickness, cloud type, altitude or actual
  sun occlusion. Depth/darkness/diffuse light are explicitly bounded art heuristics, using coverage
  and only duration/anchor-supported hourly mean precipitation. Unknown cloud falls back to a
  modest neutral coverage, not a precise clear-sky claim. Stale detail fades toward the same fallback.
- The nearest future hourly point in +30..120 minutes may add a small front contribution to cloud
  depth/coverage/underside density and filtered light. Its maximum weight is 0.28 and depends on
  lead time and original download age. This is anticipation, not observed arrival; no ETA is emitted.
- Present precipitation requires the shared WET state and an original wet evidence source timestamp
  no older than 20 minutes (fading during the final five minutes), with usable download time.
  Conflicting signals, location mismatch and expired transitions immediately suppress it.
  A numeric rate is accepted only for the very same recent model evidence interval/download:
  current accumulation <=15 minutes or explicit 15-minute/PRECEDING metadata. Endpoint must have
  existed by download time. Passing forecast time does not convert it into current evidence.
- Known mean rate maps continuously as rate/(rate+3). Without rate, WET uses strength 0.12.
  Hourly probability, heavy weather codes, forecast rain and radar ETA never increase present rain.
  Storminess is always zero: available contracts cannot establish active local lightning.
- Visibility maps a bounded horizon veil over 0..20 km; humidity alone contributes at most 0.16.
  Neither establishes fog or pollution. Wind speed uses 0.25+1.75*speed/(speed+30), missing wind
  returns neutral 1. Surface wind is a motion cue, not a measured cloud-layer velocity/direction.
- Day phase/sun/moon remain existing renderer responsibilities. Atmosphere does not invent a
  daylight source at night; diffuseLight denotes filtering and must modulate available illumination.

All numerical curves above are provisional calibration knobs, not physical meteorological
thresholds. No public accuracy claim or explanatory user-facing copy is added.

## Stability, bounds and renderer handoff

Policy output uses continuous curves with finite/range guards. Hard rain gates deliberately take
priority over continuity. Home recomputes on relevant data/brief changes and a 60-second consumption
clock, never per frame; sensor/readiness-only changes do not reset it. Its local clock/read loop is
cancelled outside RESUMED or when covered/off-view, retaining the last atmosphere while paused.
Changing place/coordinates resets the remembered output. Renderer interpolation, if used, must not
carry wet strength after a hard conflict/unavailable gate. Do not add an independent frame clock.

Allocation B must consume atmosphere while preserving shared widget painters, the existing glass
source registration, FULL/REDUCED/STATIC, reduced motion and lifecycle/off-view gates. Bound cloud
layers, particles, masks and draw allocations independently of input values. Atmosphere must take
precedence over coarse heavy-rain/thunder kinds for precipitation strength/lightning; otherwise a
legacy kind can defeat the safety policy. Null is the legacy rendering path.

The desktop preview stages an explicit file list: root/B must add `**/SkyAtmosphere.kt` to
`tools/skypreview/build.gradle.kts` before compiling it (and policy file if a policy-driven fixture
is added). That file is outside A's allocation and was not edited here.

## Validation status and root commands

Written, **not executed**: `SkyAtmospherePolicyTest` covers thin dry cloud, drizzle versus dense
rain, front anticipation without rain/lightning, cloud without present rain, stale/conflicting/
future/missing clocks, haze/night responsibility, missing/calm/strong wind, continuous changes,
unknown rates and invalid numeric inputs. `HomeSkyAtmosphereTest` covers original context age,
unknown hourly accumulation duration, exact recent intensity matching and future accumulation.
Read-only diff/whitespace review is not compilation or runtime evidence.

Root may run, after integration and the preview staging fix, serially:

```powershell
.\gradlew.bat :core-ui:testDebugUnitTest :feature-home:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat -p tools/skypreview run
```

Root should calibrate shared renderer frames for pale drizzle, filtered sun, incoming dark front,
cloud-only, low visibility, sunrise/night, missing data and stale/conflict; verify quality tiers,
pause/resume, place switching, reduced motion and widget defaults. Device performance evidence and
independent review remain required before integration/release acceptance. No test asserts real-world
accuracy; no simultaneous parent 2.1.2 uncommitted work is included in this prototype.
