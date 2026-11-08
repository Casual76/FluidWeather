# F7 matched performance comparison (allocation B)

**Current C6: NV. No matched baseline/candidate captures exist.** The preserved F0
debug emulator runs used uncontrolled conditions and different live inputs. They
remain useful raw evidence but cannot close the final <=10% gate. Do not manufacture
metadata that retrospectively labels those runs controlled.

`tools/weather_performance_compare.py` is a small offline library/CLI. It imports
`weather_metrics.summarize`, `frames` and `percentile95` unchanged. No Android
instrumentation, collection, build, network or device access is added.

## Commands and API

Parent commands from the repository root, **after real capture and integration**:

```powershell
python -B -m unittest discover -s tools -p test_weather_performance_compare.py
python -B -m unittest discover -s tools -p test_weather_metrics.py
python -B tools/weather_performance_compare.py evidence/baseline/metadata.json evidence/candidate/metadata.json
# Optional JSON archive: use UTF-8 output handling appropriate to your PowerShell version.
python -B tools/weather_performance_compare.py evidence/baseline/metadata.json evidence/candidate/metadata.json > comparison.json
```

`compare(baseline_metadata, candidate_metadata)` returns a JSON-compatible dict;
`load_capture_set(metadata_path)` validates one set. Paths may be strings or Paths.
Import as `from tools.weather_performance_compare import compare` from the repo
root, or import directly when `tools` is on `sys.path`. Invalid inputs raise
`ValueError`, `OSError` or `csv.Error`; no partial comparison is returned.

CLI exit codes: **2** invalid/incomparable (stderr reason, no stdout JSON), **1**
valid arithmetic requiring threshold review, **0** all arithmetic screens within
10%. Exit 0 is never C6 acceptance. Every valid report says `c6_acceptance: NV`.
The screening names are `REVIEW_REQUIRED` and `WITHIN_10_PERCENT_SCREEN_ONLY`.

The implementer wrote stdlib synthetic regressions; **tests and CLI were not run
by the implementer**. Parent owns execution and evidence archiving. Syntax/source
and whitespace inspection are distinct from test execution.

## Metadata schema v1

UTF-8 JSON object; every listed key is required, unknown keys and duplicate JSON
keys are rejected. One manifest represents **one device, scenario and orientation**,
one revision/APK, three or more measured repeats, and at least three warmups.
Compare phone/tablet, scenarios, start policies and accessibility settings separately.

| Top-level key | Required value |
|---|---|
| `schema_version` | Integer `1` |
| `evidence` | `CONTROLLED_CAPTURE` or `SYNTHETIC`; same on both sides |
| `revision` | Full 40-hex commit or 64-hex frozen source-manifest SHA-256; must differ across sides |
| `apk` | File descriptor for actual evaluated APK bytes; digests must differ |
| `inputs` | File descriptor for frozen scenario/input bundle; digest must match |
| `gestures` | File descriptor for complete gesture script/sequence; digest must match |
| `conditions` | Exact object described below; all values must match |
| `warmups` | Integer >=3, identical across sides |
| `repeats` | Integer >=3, identical across sides and equal to length of `runs` |
| `runs` | Array of exact objects `{ "id": ..., "gfxinfo": ..., "meminfo": ... }` |

File descriptor schema is exactly `{ "path": "relative/path", "sha256": "64 hex digits" }`.
The tool reads and hashes actual file bytes for APK, input bundle, gesture sequence
and every raw file, rather than trusting labels. Paths resolve beneath the manifest
directory, including symlink resolution. Absolute paths, traversal outside this
directory, missing files and digest mismatches fail. File digests are case insensitive.
Keep capture sets immutable during validation; concurrent file replacement is outside
this offline tool's guarantees. Archive manifests and hashes with the report.

The input bundle must contain the actual frozen provider payloads, radar tiles,
remote flags, settings, location and clock inputs needed to reproduce the scenario,
plus their inventory. Use one deterministic archive file if multiple files are
needed. The tool hashes the bundle but cannot prove inventory completeness or that
the app consumed it. A digest of URLs or a narrative label cannot substitute for
the preserved replay inputs. Likewise the gesture file must specify every action,
coordinate, duration, pause, reset and sampling boundary. It is hashed, not executed.

Every run ID is a unique nonempty string, e.g. `repeat-1`; the two sets must have
exactly the same ID set. Array order does not select or pair runs. Each raw pair
uses the original naming convention, e.g. `raw/phone-gfxinfo-run1.txt` and
`raw/phone-meminfo-run1.txt`. All raw pairs in one set share one dedicated directory.
The manifest must list **every** matching gfxinfo/meminfo file in that directory;
orphan or extra files fail. Reusing one file path across repeats fails. Distinct
files with identical content are allowed: valid PSS readings can coincide; hashes
alone cannot prove that a collector performed independent repetitions.

`conditions` has exactly the following text fields, each a nonempty explicit known
string. Empty, `unknown`, `TODO`, `TBD`, `unspecified`, `placeholder`, `n/a`, `null`,
`none`, `?` and angle-bracket placeholders are rejected. Validation checks structure
and equality; it cannot establish the truth or adequacy of free-text declarations.

| Field | Required declaration |
|---|---|
| `device_identity` | Stable serial/AVD ID plus model and emulator/physical identity |
| `os_fingerprint` | Exact OS build fingerprint/version |
| `build_variant` | Exact variant/build mode (never debug vs release) |
| `scenario_id` | Preregistered scenario and initial UI/state identity |
| `locale`, `timezone` | Full locale and zone, e.g. `en-US`, `Europe/Rome` |
| `orientation` | Exactly `portrait` or `landscape` |
| `animation_policy` | Animator/window/transition scales and reduced-motion setting |
| `quality_policy` | Rendering quality, effects and relevant feature flags |
| `cache_policy` | Exact reset/restore procedure, starting cache identity |
| `clock_policy` | Frozen/replayed application time and timestamp/reset procedure |
| `start_policy` | Cold/warm start definition and process reset procedure |
| `network_policy` | Replay/offline procedure and network restrictions |
| `power_policy` | Battery range, charging state and power/performance mode |
| `thermal_policy` | Temperature bounds, cooldown and rejection/recapture policy |
| `background_load_policy` | Background apps, emulator host load and competing workload limits |
| `theme` | Appearance/contrast configuration |
| `capture_tool` | gfxinfo framestats/meminfo commands, tool version and timing boundaries |
| `memory_sampling_point` | Fixed UI state and idle point for PSS sampling |

Numeric condition fields: `font_scale`, `refresh_hz`, `idle_seconds` must be finite
positive numbers; `viewport_width_px`, `viewport_height_px`, `density_dpi` must be
positive integers. Booleans are rejected. `idle_seconds` is the idle duration before
memory sampling (e.g. 5). These also match exactly; no fuzzy matching or inferred
defaults. Include location/sensor conditions in the frozen inputs and scenario.

Layout illustration only (no captures or accepted metadata supplied here):

```text
evidence/baseline/metadata.json
evidence/baseline/baseline.apk
evidence/baseline/inputs.zip
evidence/baseline/gestures.txt
evidence/baseline/raw/phone-gfxinfo-run1.txt
evidence/baseline/raw/phone-meminfo-run1.txt
... complete pairs for run2, run3 (and every additional repeat)
evidence/candidate/... equivalent layout with candidate APK and raw files
```

Use `Get-FileHash -Algorithm SHA256 -LiteralPath <actual-file>` to populate descriptors.
Do not paste example hashes or label old uncontrolled captures as controlled.
The synthetic tests create explicitly named temporary `SYNTHETIC` bytes; their
APK-shaped fixture file is deliberately not an installed application.

## Controlled capture procedure (parent)

Follow [the original C6 protocol](weather-baseline.md) and retain
[the F0 collection limitations](weather-baseline-capture.md).

1. Preregister each device/scenario/setting cell, complete matched conditions, repeat
   IDs, capture order, gesture file and input bundle before measuring. Preserve the
   old APK and candidate APK, full source identities (including dirty patch/untracked
   manifest, engine SHA and toolchain), actual APK SHA-256 and installation records.
2. Fix the same device/OS, viewport/density, font scale (F0 devices declared large),
   locale/zone/orientation, refresh rate, theme/quality/animations, battery/charging,
   thermals, network and background/host load. Use frozen replay inputs, not two live
   weather downloads. Verify settings/inputs on both installations and preserve logs.
3. Apply identical cache/process/clock reset and start policy. Perform **3 warmup
   passes**, then **>=3 complete matched measured repeats per version and scenario**.
   More warmups are allowed only when equally declared. Use a preregistered order
   and retain timing/thermal/load observations. Do not choose the fastest repeats.
4. For each measured repeat, reset gfxinfo, execute the exact declared gestures,
   save gfxinfo framestats, then sample meminfo at the fixed state after the declared
   idle interval (e.g. 5 seconds). The F0 rendering sequence was six vertical
   ping-pong swipes with 500 ms pauses; phone coordinates (540,1800)-(540,650), tablet
   (1280,1200)-(1280,450). Freeze actual gesture duration and all timings too. A
   scenario with no scrollable overflow must retain that fact. Broader home/detail/
   radar/location/refresh scenarios from the original protocol need separate cells.
5. Preserve all raw output, outliers, timestamps, rejected/failed attempts and reasons.
   An invalid repeat invalidates that comparison set; archive it, then recollect a
   complete preregistered matched set rather than removing inconvenient runs. Hash
   all files and write metadata only for the actual collected evidence.
6. Parent runs both Python suites and the CLI, archives command/stdout/stderr/exit
   code, reviews every repeat and each scenario/device, and separately evaluates
   evidence quality and C6 acceptance. A tool report cannot promote project status.

## Arithmetic, screening and limits

Frame durations are `FrameCompleted - IntendedVsync` in milliseconds; existing raw
parser rules apply: only Flags=0, valid non-sentinel timestamps, strictly formed
closed PROFILEDATA blocks and complete rows. No slow frame is filtered. Meminfo
uses positive `TOTAL PSS` KiB per raw run. Existing parser rejection/pairing rules
are preserved; the comparator additionally rejects zero frame p95 on either side.
The parser cannot detect truncation after an otherwise valid final block/PSS value,
or missing frames that never reached the capture. Controlled logs must cover this.

Per run the report retains frame sample count, min/max, nearest-rank p95, PSS,
raw paths/hashes, and separate frame and memory percentage changes:
`100 * (candidate - baseline) / baseline`. Missing/zero baselines are invalid,
never zero regression. Negative changes indicate improvement.

The summary reports nearest-rank p95 (`ceil(0.95*n)`) of **run frame-p95 values**
and of **run PSS samples**, their ranges, repeat counts, total frame counts and
separate percentage changes. With three repeats these p95 values are the maxima.
Pooled frame p95 is also shown separately with its change; it weights repeats by
their number of frames and must not conceal an individual bad repeat. No averages,
fastest-run selection or inferred statistical repeatability are used. The report
lists worst-repeat changes and marks review if **any** repeat or summary metric
exceeds 10%; exactly 10% is within the arithmetic screen. A bad repeat remains
visible even when the summary percentile would not include it.

`SYNTHETIC` results test arithmetic and rejection behavior only. A
`CONTROLLED_CAPTURE` label is a collector assertion: file hashes authenticate bytes
against the supplied manifest, not collector identity, execution, installed APK,
source-to-APK linkage, truthful settings, thermal/load control or scenario coverage.
Even matched credible measurements support only the tested device/scenario/configuration,
not broad real-world performance or rain accuracy. Threshold crossings require
review, not an unsupported claim that a regression is statistically repeatable.
Passing arithmetic alone never closes C6. Until matched evidence exists and is
reviewed, the final C6 comparison remains **NV**.
