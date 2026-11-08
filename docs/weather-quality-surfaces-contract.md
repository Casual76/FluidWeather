# Detail surfaces quality repair

## Ownership and optical contract

`BlackSheetChrome` now installs a local `BlackSheetSurface` for both the regular and lazy variants, so widget details, Reporting and Benchmark use the same boundary. The surface records only opaque `BlackSheetColor` in a sibling below the content with the engine's `glassMaterialSource`; neither labels nor controls occur in that recording. Both `LocalFluidCanvasBackdrop` and `LocalGlassBackdrop` refer to it. The sheet explicitly supplies a dark Fluid palette, derived from the current accent, and `LocalFluidSurfaceSide = true`. Controls retain the engine material and press behavior. The close control is `FluidGlassIconButton` with an opaque foreground.

This fixes the app integration error seen in the supplied Pressure/Moon screenshots: a Material modal has a separate Android window but still inherits Home's composition locals. `FluidChip` prefers the inherited canvas backdrop unless the app replaces it. No engine files, versions, dependencies, navigation or provider behavior changed.

## Information hierarchy

- Details and Nowcast no longer repeat Home's rain overview. Details begins with one concise forecast-valid-time line, then the actual weather metrics. The original full sample-time distinction, contributing provider IDs, download time (or unknown), and metric explanations remain under Method and sources. A phone barometer is not shown for a remote location.
- Nowcast keeps its barometric identity, result and three probability windows visible. Factors and ensemble explanation, learning, pressure/history and full methodology use explicit disclosures. The introductory line keeps the barometer separate from the rain forecast.
- Pressure shows the selected-period cleaned curve first; raw readings, tide correction, rejected samples and processing details are available in a separate disclosure. Threshold and calibration explanations remain available under Method and sources. Remote-provider pressure remains a forecast.
- Moon, Sun and Air Quality retain their data, calendars/charts and unavailable states, with longer methodology in Method and sources. Shared chips scroll horizontally when labels or font scaling exceed the available width. Statistic-row details now wrap below the label/value pair.
- `PageDisclosure(title: String = stringResource(R.string.quality_method_sources), modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)` is the shared feature-local API. It uses a real Fluid grouped row, starts collapsed, exposes the expanded/collapsed state to accessibility and preserves state across recreation. The Nowcast factors tutorial anchor remains on the visible disclosure. Strings are in the unique `weather_details_quality.xml` resources in English and Italian.

## Verification handoff

Inspected the four supplied Details, Nowcast, Pressure and Moon screenshots and the current engine backdrop selection/recording APIs. Static `git diff --check` passes for this work; English/Italian resource keys and referenced new string names were checked for parity. `WeatherDetailSurfaceTest` adds two instrumentation cases for real regular/lazy sheets: inherited-vs-local backdrop identity, dark surface, readable Fluid chip glyphs, stable control pixels after changing the parent canvas from red to green, and chip interaction.

No Gradle task, device action, weather provider request, commit or push was run by this implementer. Parent must execute the new instrumentation tests and inspect actual Android 15 frames, including dark/light app themes, selected and unselected chips, open/close, scrolling and expanded metadata. Source inspection and prepared tests do not establish that runtime or visual gate.
