# F5 correction1: forecast semantics, tool publication and horizon contracts

Applies after F5 round0 F5-ca14d83448b9. The old manifest/ZIP/f5 artifacts remain immutable;
active sources evolve for this correction. New evidence lives only in f5-round1. C2 field and
C6 global accessibility/performance remain NV, as do live model/audio and complete AssistantSession
execution beyond inspected production wiring. No engine/model retraining, new provider, publication,
notifications, commit, push or secret access.

## Confirmed defects and changes

1. R1-02/R2-1: the summary used a no-future-source predicate on routine forecast valid times.
   Fresh download at09:00, window10:00-11:00 could be known in the common evaluator and unknown in
   summary/chat. WeatherSummaryPolicy now permits future MODEL valid times for forecast consumption
   (recent=false/current=false), retaining no-future-download and60-minute download freshness.
   Current/recent MODEL and radar source-time checks are preserved. Numerical state/coverage comes
   exclusively from RoutineWindowEvaluator; no second prediction algorithm was introduced. Source
   details use the same forecast-valid predicate rather than falsely labelling such samples stale.
2. R1-01/R2-2: raw invalid date/search/comparison arguments were copied into allowed tool facts,
   from where selected IDs or fallback could reach displayed answer/history/Done-to-voice. Fixed
   localized ToolFailure enum messages replace free-text errors. The audit also removed query echoes
   from save/remove and observation deletion, and exception text from official-alert failure. Units
   parse convenient prefixes but publish canonical SetUnit enum names, never the unparsed suffix.
   Comparison failures no longer use the unresolved name as a row label. Resolved source/archive
   names and user-owned data remain domain fields, not a claim of universal semantic validation.
   WeatherPlaceResolver exposes domain IO for real tool tests; PlaceResolver still performs actual
   matching/fetching/snapshot observation. The UI/engine/API provider transports are unchanged.
3. R2-3: meteo_riepilogo exposed giorni1..7 but did not consume it. The schema now has only luogo
   and describes current/shared conditions plus configured personal windows, explicitly routing
   future horizons to existing previsione_giorni/giorno_dettaglio/previsione_oraria/pioggia_prossime_ore.
   The analogous unused ore field and longest-dry-window promise on finestra_asciutta were removed.
   Its existing ID remains; unconfigured arbitrary dry windows are not invented across missing data.

Bounded parent corrections, no implementation delegate or source overlap. Two fresh independent
Sol high reviewers will assess this corrected version; no prior approval is assumed.

## Regression evidence

ChatToolBoundaryTest (five new JVM tests) invokes actual DayDetailTool, SearchPlaceTool,
SavePlaceTool, ComparePlacesTool and UnitsTool through WeatherTool.run and ChatGrounding, with fake
domain records. Injection includes a newline and invented exact rain cessation. Both valid ID
selection and malformed-final fallback are checked; raw Answering is buffered and safe rendered
text is committed through the actual history helper. EN/IT fixed errors and canonical unit receipts
are asserted. A schema test verifies unsupported horizon parameters are absent and routes exist.
This is not a full orchestrator/provider/voice execution and does not prove future unknown tools safe.

WeatherSummaryPolicyTest adds a future MODEL forecast case with current/recent/radar rejection
and download-future/expiry controls. WeatherSummaryForecastAndroidTest adds two Android methods:
actual common evaluator -> actual formatter -> EN/IT Resources for future WET/DRY, complete total,
source details not falsely stale, model temperature sample and default rendered text; expiry of the
original download suppresses state/amount/temperature even if evaluation time is renewed. Multiple
language/state assertions within a method are not counted as additional distinct test methods.

The first integrated compile failed only in the new Android test because same-package app.R
shadowed the strings R namespace. Explicit strings.R import fixed it; original integration-checks.log
is preserved. integration-final.log reports BUILD SUCCESSFUL for:

```
.\gradlew.bat --max-workers=2 --no-daemon --no-watch-fs :core-model:test :core-strings:testDebugUnitTest :core-ai:testDebugUnitTest :core-weather:testDebugUnitTest :feature-home:testDebugUnitTest :feature-assistant:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Current XML archived under unit-results:467, zero failures/errors/skips:93 model,18 strings,56 ai,
256 weather,38 home,6 assistant. These include UP-TO-DATE/cached tasks, not467 new executions.
The six new JVM cases actually executed and passed; the final full task selection also restores
results narrowed by the previous reviewer's filtered runs. engine-doctor passes at clean3.0.5;
diff whitespace check passes. tested-apks.json records final app/test APK sizes/hashes.

The first correction Android run completed19 methods,18 passed and the existing routine native
picker test lost window focus. No foreign foreground app was observed this time (post-run screen
was launcher); the earlier interference explanation is not established. The test's two Espresso
selectors now explicitly use inRoot(isDialog()), choosing the TimePickerDialog rather than the
unfocused Activity root. All original CRUD/overnight/persistence assertions remain. The focused
editor test then passes (picker-test.log, OK (1 test),12.508s); picker-test-build.log records its
successful test-APK rebuild. This is a test-targeting correction, not a product picker redesign.
Full final suite result is recorded below after completion; earlier failed run remains separate.

UI geometry/resources/components outside the tested formatter output are unchanged from round0;
round0 final screenshots and17 prior cases remain historical evidence. No new full-app visual
claim is inferred from the two formatter tests. Physical devices and the already-running phone
are untouched. Only previously-off Pampa_Tablet is opened through Device for the project runner,
with adb limited to APK installation and instrumentation. The owned tablet is closed after checks.

Final Android result: tablet-tests-final.log has19 successful status records, OK (19 tests),
40.934s, instrumentation terminal code-1. This comprises the prior17 cases plus2 new forecast
formatter methods. The focused editor repeat is not an extra distinct case. No remaining failure
in the final suite. No assertion was removed or skipped. Device close(shutdown=true) completed;
subsequent discovery confirms Pampa_Tablet off and this thread's open list empty. Other sessions'
Medium_Phone is also off at this discovery; it and physical devices were not operated. No further build/test/device
repeat is needed before freezing unless a new defect or source change warrants it.
