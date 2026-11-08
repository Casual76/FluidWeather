# Glance polish verification: 2.1.3

Candidate version 2.1.3/code24. Production was compiled from `a359a6f28a85b269b477e3c59c97a4d672cd6131`, based on the accepted stable2.1.2/main169c719. Later documentation-only commits do not change compiled inputs. Review acceptance is recorded separately in the status ledger; this document records execution and its limits.

## Resulting experience

- The widget has a dominant left temperature, right condition/icon/high-low, four or six aligned future hours, and five future daily rows in tall formats. Daily ranges share one temperature domain, with rounded gradient bars. Filled multicolour native vector icons replace monochrome outlines. The same production Glance content is used by the launcher and the QA host.
- Eight responsive declarations cover110x110,180x180,250x110,250x200,250x340,320x170,350x180 and350x350dp. In short/narrow formats the weather icon replaces a long condition phrase. Original forecast valid time and download age remain separate, visible values.
- The summary opens with place, current temperature/condition, a short rain fact and compact personal windows. No source catalogue or method paragraph is displayed initially. Sources/method and the complete original catalogue remain expandable. AI still selects app-authored fact IDs; provider prose is never used as the displayed forecast.
- Moments show name/day, clock, drop/percentage and thermometer/range. A short uncertainty/partial state remains when relevant. An information icon opens original interval, zone, download/source and sampled-temperature limitations. The home heading uses light ink over the sky; Add/Edit uses real shared Fluid glass.
- The rain overview uses icon/value metrics and a concise missing-data key, preserving its detailed semantics and source disclosure.

The home sky renderer, weather evaluator, model, sensor policy, caches/acquisition, Room schema and engine have no production changes in this phase. Widget sky rasterization adds a calm tonal veil while retaining the accepted SkyFrame renderer. The widget remains cache-only. Existing location-preserving navigation destinations are retained.

## Target and execution boundaries

Physical Samsung SM-X710, Android16, hostlocal. Only `dev.pampa.fluidweather.debug` and its test package were installed. The principal package/widget/archive was not changed. No phone command or emulator was used. The phone remains allocated to the higher-priority session.

Compose tests use360/800dp virtual viewports on this tablet, not full-app phone captures. Populated summary/moment cases use the production formatter, actual Resources, Fluid portal/material and an actual static sky backdrop, with synthetic data and a fake ID selector. The32 configurations cover EN/IT, two widths, font1.0/1.5 and WET/DRY/UNCERTAIN/UNAVAILABLE; two methods each give64. Default metadata stays collapsed;33%, tomorrow, localized state and sampled range remain readable; full original metadata remains reachable after selection. Additional existing summary and rain methods exercise cancellation/configuration, no-data, gap, conflict, transition and large-type behavior.

The widget host composes actual Glance into actual RemoteViews/framework Views at8 sizes x2 languages x2 font scales x4 populated/empty variants =128 methods. It verifies every essential line for ellipsis, rasterizes actual glyphs, checks each clipping ancestor and text intersection, real aligned hourly ImageViews, complete painted daily bars and correct hierarchy. The long synthetic place name intentionally stresses ellipsis. Software Canvas PNGs prove content/ink; they do not independently prove hardware outline clipping or every launcher effect.

Read-only metadata from the existing principal widget identified198.588x198.588dp content. A separate Debug-only native `AppWidgetHostView` receives the production size declarations as a real RemoteViews size map. One method exercises four host cases:198.588-square and336x180, EN1.0/IT1.5. Android chooses the four-hour square and six-hour wide layouts. No widget ID is allocated/bound, and no existing launcher widget is updated. This proves native size-map negotiation at those bounds, not launcher resize gestures or click dispatch.

The bound Device command could not launch Samsung's launcher (`unable to resolve Intent`, MAIN/LAUNCHER); the one diagnostic retry also failed. That manual route was stopped without adb/SSH UI workarounds. Project test runners, installs and artifact pulls were used for the evidence here. All current tests leave global network/font settings unchanged.

## Current evidence

Artifact root: `artifacts/glance-polish-2.1.3/` (local ignored QA evidence, not publicly distributed user screenshots). Candidate bytes are preserved under `candidate-a359a6f/`, with original hashes in `tested-apks.json`.

- `verified-build.log`: Debug, AndroidTest and signed Release plus the four pertinent JVM test tasks; BUILD SUCCESSFUL in2m12s,1500 tasks:29 executed,1471 up-to-date.
- Canonical JVM XML:36 files /219 results: strings27, core-ui60, home79, appwidget53. Zero failures/errors/skips. These include reused outputs, not219 fresh executions. Earlier integrated-build logs retain their actual execution/cache scopes.
- `widget-host-verified.log`:128/128 completed methods,162.723s, terminal instrumentation code-1.
- `glance-ui-verified.log`:82/82 completed methods (64 populated,6 existing summary,11 rain,1 native size-map),114.983s, terminal code-1. The native method contains four host cases, not four JUnit methods.
- `widget-uri-verified.log`:1/1 completed URI-compatibility method,0.034s, terminal code-1. The shell orchestration reported a false failure because it expected plural `tests`; the original runner says `OK (1 test)`. The record parser accepts singular/plural and independently confirms the successful status. It was not rerun or counted twice.
- `engine-doctor.log`: clean engine3.0.5, unchanged gitlink/checkout `a055257fc4b559bee54b9ce114f265bcd237adfd`.

The existing real MainActivity offline/deleted-target widget navigation regression passed on stable2.1.2. That full runtime route is historical evidence in this phase; URI identity/legacy parsing is rerun here. The underlying router and navigation handling have not changed. No fresh launcher click/resize or principal-package installation is claimed.

## Corrections and accounting

The first two integrated compilations failed on a no-argument UnitPreferences fixture and a missing FluidListGroup import; both were corrected. The first populated64-method run failed at a text-advance assertion: fractional intrinsic advances were rounded to integer Compose bounds even though painted glyphs fit. Current assertions inspect rasterized glyph pixels and truncation instead. The same issue affected the existing large-font Roma title check; it uses the shared painted-text assertion. The normal and enlarged populated captures were inspected, not inferred from test counts.

Widget host runs initially found real clipping/ellipsis:30 of128 failed in the first run, then16 in the second. Layout budgets were corrected through smaller short-format gaps/padding and omission of redundant long condition words where the icon suffices. All-line ellipsis, glyph, ancestor and overlap assertions were retained. The verified128-method run passes. Earlier failures, diagnostic subsets and the zero-test regex attempt are retained and are not added to the successful final totals. The82-method pre-final run had81 successes and one fractional-title assertion failure; it is not a passing suite.

## Release identity and limits

Release package `dev.pampa.fluidweather`,2.1.3/code24, non-debuggable, ARMv7+ARM64. ZIP and signature verification pass; SHA256 certificate `825738500ce9605429ffba3c4019228f992f2c37d1b70a2230a7ce2c25637e43` matches the previous actual stable. Debug widget-host activities are absent from the Release manifest.

Signed APK:25,033,536 bytes, SHA256 `8376bddfd3c885a8fd01883ac1387a88ab74901ef6bd902e55dca09ffb0d8a25`. APKs are external to the source/evidence ZIP and verified separately. Publication, if accepted, has its own record.

These tests establish the stated presentation and integration behavior. Live weather accuracy, future rain/storm sky fidelity, model improvement, sustained multi-day sensor behavior, process death, live AI/audio, other launchers and actual full-phone behavior are not established by these fixtures. Practical legibility/large-text checks are scoped; no new general accessibility, performance or field-accuracy acceptance is inferred.
