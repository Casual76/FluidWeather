# F7 paired rain field comparison

This offline JVM utility prepares arithmetic for review. It collects no forecasts or
observations and makes no weather accuracy claim. C2 remains OPEN / REVIEW_NEEDED.
The existing synthetic fixtures are illustrative only; no real observations were
collected by this implementation. See [the baseline protocol](weather-baseline.md).

## Commands and files

From the repository root, the parent may run:

```powershell
.\gradlew.bat --no-daemon --no-watch-fs :testbench:run --args="rain-compare targets.tsv baseline-records.tsv candidate-records.tsv"
.\gradlew.bat --no-daemon --no-watch-fs :testbench:test --tests 'dev.pampa.fluidweather.testbench.validation.RainComparisonTest'
```

Use paths relative to the testbench JavaExec working directory (normally
`testbench/`), or absolute paths; quote paths containing spaces inside `--args`.
The pre-existing `rain-validation <targets.tsv> <records.tsv>` command is unchanged.
No Gradle, compilation, test runner, device, network or provider was used by the
implementation sub-agent. Parent owns execution and evidence capture.

Public entry points are `RainComparison.evaluate(targets, baseline, candidate)` and
`compareTsv(targetText, baselineText, candidateText)`. The report exposes both strict
validation reports, classification coverage keys, common-subset classifications,
paired onset/end timing and `gate`; `render()` formats the review report.

## Exact schema reuse

All three files are UTF-8 TSV, using `RainRecordingTsv.TARGET_HEADER` and
`RECORD_HEADER` from `RainValidation.kt`, with no new columns or inferred defaults.
Headers carry `rain-targets-v1` / `rain-records-v1`; data rows omit those header markers.
Targets contain key, fixed site identity/coordinates, start and end of `[start,end)`.
Records contain role, key, id, evidence, revision, source, site/coordinates, interval,
recordedAt, independent, state, onset and endEvent. Timestamps are ISO-8601 instants;
`-` denotes an absent transition, never a missing row or a dry observation.

Each run must contain exactly one FORECAST and one OBSERVATION per target. Existing
strict validation rejects duplicates, absent/extra rows, changed sites/intervals,
overlaps, late forecasts, incomplete observations, invalid transitions, mixed
revisions/evidence and non-independent reference assertions. All records in a run
must identify the same frozen commit/source manifest with a 40-character lowercase
SHA-1. Baseline and candidate revisions must differ; their evidence class must match.
The utility validates identifier syntax, not the existence or contents of a commit.

The two OBSERVATION sets must be exactly equal as parsed records: identity, state,
source, completion clock, independence, evidence, events, site and interval. Only
the mandatory per-run revision is normalized for equality. No observation timestamps,
IDs or sources are rewritten, and no joins by nearest time/place are permitted.
Ordering of rows is immaterial. Empty/header-only files and comparisons without any
common classifiable target fail with an exception (CLI failure), not an empty success.

## Prospective collection protocol

1. Before forecasts/events, freeze the target manifest and protocol: stations and
   coordinates, dates, slot durations, issue schedule/lead times, rain threshold and
   instrument resolution, sampling cadence, transition tolerance/uncertainty, event
   mix, minimum sample/event counts, missing-data rules and acceptance thresholds.
   Include dry periods, rain, pauses/intermittency and difficult conditions. Do not
   select events retrospectively or tune the candidate on the acceptance observations.
2. Freeze BOTH source revisions and archive BOTH actual forecasts before each event
   and target start, from inputs available at the planned issue time. Keep issue
   times and comparable lead times. Archive raw inputs, outputs, source manifests,
   command/configuration, hashes and reliable timestamp evidence. Do not recreate a
   forecast from later provider history. Predictions may differ, their reference may not.
3. Collect local observations independently of either forecast, preferably blinded:
   fixed time/place, instrument or observer identity, monitored interval, original
   notes/readings, resolution, uncertainty and completion clock after the slot ends.
   Radar/provider/fusion output is not independent local ground truth. Moving to a
   different location requires a new prospectively registered site/target plan.
4. Keep every planned row. Withheld/unavailable forecasts and interrupted/unavailable
   observations use UNKNOWN with reasons in the archived log. Never erase slots or
   convert missing data into DRY. Record onset/end only for an unambiguous episode;
   omit censored, intermittent or ambiguous transitions according to the frozen rule.
   Unknown reference state has no transitions. Preserve originals and version any
   corrections with reasons; apply the identical corrected reference to both runs.
5. Attribute that same observation set to each run's revision, run the comparator,
   and archive all three TSVs, hashes, protocol, raw evidence, report, command, exit
   status, tool/source revision and parent test results. Review lost/gained keys and
   missing-data reasons alongside arithmetic. Do not accept a lower error count or
   MAE obtained by withholding forecasts or dropping transition pairs.

The C2 criterion inherited from the baseline is no worsening of false-dry/false-alarm
performance and improvement in at least one pertinent metric, on sufficient real
independent evidence. Preregister the precise metric denominators, uncertainty method,
minimum sample/event mix, acceptable missing coverage and timing tolerance before
collection; there is no sample-size or timing cutoff invented by this utility. If the
protocol lacks those thresholds, field acceptance remains unresolved. Account for
correlated slots from one event; slot count is not independent event count.

## Reading the paired report

Both full runs retain hits, correct dry, false dry, false alarms, slots, unknown
forecast/reference counts and scored/total coverage. Rates display numerator and
denominator plus a fraction (not a percentage): false dry / observed rain; false alarm /
predicted rain; false alarm / observed dry; correct / scored. A zero denominator is N/A.
Unknown forecast/reference counts can overlap and must not be summed as disjoint cases.

Common classifiable targets require both forecasts and the shared reference to be
known. Counts/rates are recomputed on precisely that shared subset. Gained/lost
coverage is candidate-only/baseline-only classifiability, and neither means neither
run can score it. Key lists make denominator changes inspectable. Full-run rate
differences alone cannot establish improvement when classifiability changes.

Onset and end are analyzed separately. An observed transition defines eligibility;
each run pairs only when it predicted that transition. Reports list common, gained,
lost and neither timing keys relative to the observed eligible set. Signed errors are
prediction minus observation, in minutes. Shared MAEs and candidate-minus-baseline
MAE use exactly identical target keys; a missing transition never adds zero error.
Raw per-run MAE/pair counts and unpaired counts across all targets remain visible for
context. No shared transition pairs means N/A, even when classification is comparable.
Lost timing pairs forbid interpreting the smaller raw MAE as improvement; shared
subset deltas must still be reviewed with those losses. No automatic improvement
or C2 approval flag is produced.

## Limits and regression coverage

SYNTHETIC and MODEL_SIMULATION can exercise arithmetic only and never establish C2.
REAL_LOCAL is a capture assertion: software cannot authenticate independence,
preregistration, immutable archival timing, sufficient sample/event diversity or field
acceptance. The returned gate is always REVIEW_NEEDED / OPEN, even with apparently
perfect arithmetic. Reviewers must inspect the archived protocol and independent
evidence; this command's successful exit means structurally comparable data only.

Focused JUnit regressions (written, not run by this sub-agent) cover hand-counted
rates and signed errors, deterministic ordering, exact observation equality, invalid
rows/revisions/issuance, differing unknown and gained/lost coverage, withholding a
false-dry forecast, dropped hard timing pairs, absent timing, evidence class mismatch,
empty/header-only TSVs, TSV round-trip input, and nonapproval for every evidence class.
All test data are invented inline; frozen existing fixtures were not modified.
