# F4 routine UI and consumer handoff (B)

## Integration points for parent

- Own one `RoutineWindowStore(applicationContext)` in the graph; pass it as the new required
  `HomeDependencies.routineWindowStore` argument.
- Wire the new required `onOpenRoutines` callback of `HomeScreen` and `SettingsScreen` to
  `settings/routines`, rendering `RoutineWindowsScreen(store, onBack)` in the existing Fluid host.
- No model, engine, graph, navigation or build file was changed by B. Engine remains pinned.
- Preserve personal settings under the existing archive-only wipe; B does not change wipe policy.

## Editor

`RoutineWindowsScreen.kt` uses FluidScreen's lazy scrolling body, FluidTextField, wrapping
full localized weekday chips, FluidListRow time controls and FluidButtons. Platform time picker
matches the existing notifications editor convention and local clock format. Every control has
text; time values and overnight meaning are visible. No fixed text height or one-line subtitle
cap is introduced. At 2x, the chips wrap and all actions remain in scrollable content; actual
device verification is still required.

The store begins empty (A's responsibility). Add creates an unsaved stable UUID draft with no
name, selected days or chosen endpoints. A time picker's initial midnight value is only a picker
seed; opening/cancelling it does not select or save an endpoint. Save requires nonblank name,
at least one day and different valid minute endpoints. End before start is allowed and explicitly
described as next day. Updating and removing use the original ID. Draft fields use
rememberSaveable; recomposition does not reset them. Save/delete serialize through a busy state,
surface errors without clearing the draft, and rethrow cancellation. Read failures are visible.
Back during editing dismisses the draft; Cancel does the same. Successful saves alone exit edit.

## Structured consumer and race protections

HomeData combines saved-window Flow with the existing place/point/snapshot identity and uses
collectLatest inside visible RESUMED lifecycle ownership. The existing controller evaluates
routines on its Default dispatcher alongside immediate model output and enriches the eventual
shared result without changing its rain assessment, radar/network/cache policies or provenance.
All rules are requested with Int.MAX_VALUE; the UI alone collapses the result to two.

After awaited IO, the controller rechecks current saved configuration and data generation;
obsolete configuration clears the result and ends the obsolete consumer. New Flow input starts
a replacement consumer. Place and content changes cancel old work. Generation and cancellation
are rechecked before emission, including after enrichment. An overload retains existing
three-argument controller test/caller compatibility. Each loop reruns at most 60 seconds after
its previous start (or sooner at existing rain ETA boundaries), resolving the device zone anew.
Evaluator A owns deterministic ongoing/overnight/DST/date/coverage behavior; the UI does not
infer interval lengths, scale amounts or fill missing values. Ongoing display retains original
occurrence endpoints and explicitly qualifies remaining-time-only assessment.

## Home presentation

New HomeRoutineWindows is a full-span grid item immediately below the unchanged F3 rain header.
Its key is excluded from drag targets along with the existing header/alert keys. Existing tiles,
their IDs and ordering are preserved. Empty output offers a discreet, live Add callback.
Nonempty output shows the first two distinct rule occurrences and a Show all action for the rest;
Edit always opens the dedicated route. Per-window sources/interval details expand in place.

RoutineFormatter and dedicated EN/IT routines.xml files show configured names, actual endpoint
dates/times/offsets and the resolved zone ID. Device fallback and DST adjustment are explicit.
Forecast wording stays MODEL-qualified, probability is only shown when known, amount only with
complete assessed coverage. Partial/stale/unknown/location mismatch/provenance limitations are
visible. Source ID and original download age stay visible; details retain actual represented
intervals, source timestamps and original time resolution (exact min/s/ms units, no duration guess).
Dates and source times use the consulted zone. Missing source intervals/download/resolution are
labelled unknown, never replaced by the evaluation clock. Temperatures are described as model
samples, not complete-window extrema claims. Evidence is never reclassified as measurement.

## Tests written and verification boundary

- RoutineDraftTest: invalid empty/missing/equal/out-of-range inputs; Unicode name, stable ID,
  weekdays and overnight endpoints.
- RoutinePresentationTest: unknown wording, partial/device/DST/remaining labels, evidence-level
  stale flag and localized weekday order.
- HomeRoutineControllerTest: config removal during in-flight shared service before Flow delivery;
  concurrent location/config replacement; all three rules retained; minute refresh past end.
- Existing HomeBriefControllerTest covers stale snapshot/location, generations and cancellation.

No Gradle, build, unit test, emulator or device command was executed by B. Tests above are
authored, not claimed passing. Light static review covers Fluid API signatures, callback chain,
resource XML/key/placeholder parity and patch whitespace. Parent must compile and run tests
after both implementers finish, then verify editor create/edit/delete/error/recreation and 2x
layout, place-zone/DST/midnight behavior and F3 visual hierarchy on device. C7 integration is
pending parent verification; full global C2/C6 remain NV. No publication or commit/push.
