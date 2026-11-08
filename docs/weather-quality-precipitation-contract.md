# Precipitation presentation contract

The precipitation detail page separates the shared assessment of rain now from model forecast samples. `PrecipitationPage(HomeUiState)` uses `HomeRainOverview(showCurrent = false)` and keeps `PrecipitationForecastSamples(state, compact = false)` compatible with the home tile caller.

The default forecast shows the next six clock hours, a maximum available probability, a qualified sum of available amount samples, and one width-bounded probability chart. It never prints accessibility sample descriptions as visible paragraphs. The chart has a fixed 0–100% scale; zero draws a baseline mark and missing probability draws a cross. Its semantic description retains precise timestamp, offset, zone and model qualification for every displayed position.

An explicit Hours and amounts control exposes exact probability and amount rows. Day chips retain access to the original model horizon, using localized short dates (Today where applicable), with complete dates in semantics and the selected-period title. Selecting a day shows its available maximum and qualified sample sum; earlier/later controls page its samples in groups of six. A table therefore follows the currently displayed window instead of appending every hour of the forecast. Daylight-saving clock ambiguities remain distinguishable in semantic timestamp descriptions. The summary amount uses a short label/value pair and coverage count; interval-duration and non-total caveats stay in semantics and the explicit source disclosure.

Sources and method is collapsed initially. It explains contributing provider IDs, the snapshot cache clock (not a download or issue time of every contributing model), missing and duplicate/off-grid positions, and unverified accumulation interval. The phone barometer diagnostic remains consultable there and is explicitly independent from provider forecast samples.

`ForecastRainSamples` is unchanged. Missing values, absent hours, duplicate timestamps and off-grid positions remain unknown. No interpolation, zero substitution, inferred rain-now result, arbitrary accumulation duration or start/end ETA is introduced. A sample sum is never called a six-hour or daily total. Summary coverage counts make partial maxima and sums explicit.

Focused production Compose regressions in `PrecipitationQualityTest` cover a week-long input with collapsed details, bounded default table size, disclosure reachability, day/hour paging at double font, zero versus unknown, and completely absent forecasts. The existing F6 zero/missing/gap test now checks chart/row semantics instead of requiring accessibility narration to be printed onscreen.

Static verification: new XML resources parse; EN/IT keys and format arguments agree; new resource references resolve; `git diff --check` passes. Compilation, instrumentation, device visual QA and actual weather/provider accuracy are reserved for the parent run and are not claimed by this change.
