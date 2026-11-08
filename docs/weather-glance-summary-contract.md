# Compact home summary and personal windows

Scope: `HomeSummarySheet`, `HomeRoutineWindows`, their new `HomeGlanceContent` helper,
`WeatherGlanceSummary`, new EN/IT resources, and focused tests. Base: `169c719`.
Engine 3.0.5, evaluators, trained model, provider contracts, legacy fact catalogs,
rain overview, widgets, cache and atmosphere are untouched.

## Presentation APIs

`HomeSummarySheet` and `HomeRoutineWindows` keep their existing public signatures.
`HomeGlanceSummaryContent` and `HomeMomentContent` are internal composables.
`WeatherGlanceSummary` exposes presentation helpers for policy-gated current weather,
routine validity, document revision/expiry matching, transition, source identity,
compact state, partial coverage, sampled temperatures, local day and occurrence time.
No network or provider calls are added.

The sheet shows place, valid current icon/temperature/condition, the current document's
rain answer, an optional authorized transition, and up to the existing three personal
window facts. AI-selected known routine IDs order these typed cards; other selected
catalog facts remain available under Sources & method. Provider prose is never read.
Invalid IDs fall back through the existing controller. Default AI status is omitted;
loading, completion, cancellation, failure and invalidation retain existing feedback.
All actions remain inside the existing Fluid sheet scroll body.

The summary checks the fresh document context synchronously and maps quantities only
when document revision and expiry match the supplied brief. Current and routine values
use the existing `WeatherSummaryPolicy` gates, including exact exclusive deadlines.
No stale brief is revived on a wipe. Existing lifecycle, selection, retry, cancellation
and generation handling remain in the controller/session.

Home personal windows keep first-two/expand, edit/add, read-failure behavior and use
`GlassTile`, which samples the shared weather canvas with the existing Fluid content
glass optics and white foreground. The section heading uses that weather foreground.
Without a canvas, existing themed fallback colors apply. Sheet and personal-window
disclosures reset for changed context/occurrence. No user images enter the repository.

## Quantity and disclosure rules

Probability is the supplied maximum, rounded through the unit formatter. Missing
values are omitted. Temperatures are supplied sample ranges; their sampled nature
is exposed in the metric semantics and expandable Details, without a separate
Samples/Campioni badge in the default card. Partial coverage gets a
separate short badge; partial dry wording refers only to covered periods. There is
no invented total, extrema, ETA, measured-weather assertion or dry value for unknowns.
Full diagnostic forecast wording, sample uncertainty, exact interval, offsets, IANA
zone, fallback/DST/remaining-window flags and original evidence remain in Details.
Overnight compact times contain both dates; overlap/gap-adjusted times retain offsets.

Sources & method deduplicates source identities by type plus stable source ID. The
original per-evidence clocks, represented intervals and limits remain in its detail
catalog. Complete summary deliberately exposes the complete original fact catalog,
including the original source wording, for inspection rather than as the default UI.
The existing formatter's four-evidence detail cap and omission notice remain unchanged.

## Validation handoff (not executed here)

No Gradle, build, test runner, device, provider, network, publishing, version bump or
push was performed by this implementation agent. Parent owns all execution.

- Updated `HomeSummarySheetTest`: existing null-data controller/action scenarios now
  target the structured place node and absence of optional default status.
- New `HomeGlancePopulatedTest`: 32 configurations, EN/IT x 360/800dp x fonts 1/1.5 x
  wet/dry/uncertain/unavailable, each testing sheet and moments. Uses real populated
  brief/assessment/resource formatting and a fake selector, fits virtual viewports to
  host display, checks bounded card height and text overflow, hidden source/zone prose,
  available samples/probability/day, source deduplication, original metadata disclosure,
  and request reachability after expansion. Uncertain/unavailable cases have partial
  populated windows. It is synthetic UI coverage, not proof of physical readability.
- New `WeatherGlanceSummaryTest`: exact expiry/future evaluation/missing download and
  location mismatch gates, source identity deduplication, both overnight dates, both
  DST overlap offsets, and document revision/expiry mapping.

Parent must compile and run these tests, then perform physical tablet validation and
the requested three fresh reviews. Verify actual backdrop capture/refraction, daylight
and night white-foreground contrast, accessible icon metrics, long names, font scaling,
scroll actions, selection/cancel/retry, source-expiry transitions and wipe while open.
No claim of test passage or hardware validation is made by this handoff.
