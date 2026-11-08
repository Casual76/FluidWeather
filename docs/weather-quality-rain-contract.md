# Rain overview quality contract

The overview answers two separate questions: what recent evidence says about rain now, and what the model forecasts for the next hour. A forecast does not fill a missing present assessment. When present rain is unavailable but useful future intervals exist, the card leads with that qualified forecast and immediately states that rain now is unverified. A partial dry forecast describes only its covered periods. An entirely missing forecast has no decorative chart.

The public `HomeRainOverview(brief, locationName, modifier, nowMillis, onOpenSummary, showCurrent)` signature remains compatible. No provider, evaluator freshness threshold, model/radar classification or transition policy is relaxed for this repair.

## Chart

- Only `FORECAST` periods enter the next-hour chart. Missing, overlapping, stale and location-mismatched intervals remain gaps.
- A solid low baseline is a known dry forecast. Dots are periods without a usable forecast. There are no filled unknown rectangles or diagonal marks.
- Rain bars encode interval accumulation. The caption identifies the largest displayed amount; chance is labeled separately and never determines bar height. A wet period without an amount remains a state indicator, not a fabricated quantity.
- Clipping an accumulation removes its amount; values are never prorated. A next-hour total is available only when every interval is known and its complete amount survives clipping. The maximum probability refers to available forecast periods; partial coverage remains explicit.
- Source resolution is shown independently of chart cadence. Fifteen-minute intervals never imply fifteen-minute meteorological timing precision. Transitions still require the existing radar policy and remain absolute windows.
- At most three clock labels mark the hour. TalkBack receives each interval, its wet/dry/unknown state, provided quantity/probability, and resolution once.

## Sources

The explicit sources control reveals provider/product type, original valid times, download times and age, effective intervals, known temporal/spatial resolution and evidence limits. The place zone is stated once; device fallback remains explicit. Future forecast evidence is included even when present assessment has no usable source. Computation time is never presented as a source update. Original timestamps stay as data and are rendered in localized short date/time form, without epoch values or repeated full zone paragraphs.

## Why temperature can exist while rain now is unverified

Inspection of the current `LocalPrecipitationEvaluator` establishes separate gates. A current temperature/condition summary may be accepted up to 30 minutes old, while rain evidence uses a 20-minute gate. A current precipitation accumulation must have a known interval no longer than 15 minutes. A non-wet weather code does not prove dry conditions. Recent minutely evidence must already have ended by the original forecast download time; a future forecast never becomes a present observation merely because its endpoint has passed. Useful fresh forecast intervals can therefore coexist with unavailable present rain. No concrete defect in these gates was established, so they were preserved.

These are source-code findings, not proof of the screenshot's exact cached inputs or of meteorological accuracy. No provider requests or device data were used for this implementation. Presentation regressions cover unavailable-present/known-future separation, gaps versus zero, coverage/amount totals, clipping, overlap and stale/non-forecast exclusions. Gradle/device verification is owned by the integrating parent.
