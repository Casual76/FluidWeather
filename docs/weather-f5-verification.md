# F5 verification: deterministic summary, isolated AI selection and grounded chat

## Scope and implementation

F5 adds an immediate EN/IT summary from the shared WeatherBrief, an explicit optional AI request,
and shared precipitation facts for summary/precipitation chat tools. The existing F3 headline/
transition presentation policy is now in pure core-model code, delegated to by home. Home keeps
its main rain answer, routine windows, tile IDs/order and detail routes. The summary sheet uses
the existing Fluid glass modal host, backdrop and motion; home depends on the pure-model gateway.
Engine remains3.0.5, trained barometer policy remains AnalogPolicy.OFF, Room schema remains8.

AI selection is deliberately bounded: a configured CHAT provider may choose/order known fact IDs;
the app renders the text and always retains required uncertainty/source facts. No arbitrary model
weather prose can enter this summary API. One provider, tools empty, ToolChoice.None, webSearch
false, bounded output/timeout; malformed/duplicate/unknown/extra text falls back to deterministic
facts. The service stores validated IDs with original expiry, never generated sentences. Original
source clocks and approximate absolute ETA windows remain; expired context is rejected.

AppGraph supplies one brief service and routine store to AiDataSources. The isolated service is
cleared on archive wipe and key-state/settings/availability metadata emissions, without reading
key plaintext. Its generation barrier rejects pre-clear completions. HomeSummaryController checks
context, generation, expiry and ownership before publication; opening/resuming/refreshing does
not call AI. The existing AI settings route is wired from the no-configuration sheet.

Chat uses request-local app facts, a private engine state flow and a copied request conversation.
Only working states are relayed before final rendering. Final, stored history and the Done text
consumed by voice are app-rendered; context/snapshot/config changes discard obsolete weather.
Known tool detail blocks and curated general explanations remain available; unsupported free-form
prose without returned facts yields a localized no-facts response. Some deeper legacy tool labels
remain Italian. Cross-store reads are rechecked individually rather than transactionally. The
new unit tests exercise the actual buffering/history/registration helpers; they do not instantiate
the complete AssistantSession with a live model or audio backend. The production call path and
voice consumption require code inspection in addition to those tests. No live IA calls were made.

## Executed verification

All logs and XML mentioned below are under artifacts/weather-improvement/f5.

Initial integrated command (f5-integration-checks.log):

```
.\gradlew.bat --max-workers=2 --no-daemon --no-watch-fs :core-model:test :core-strings:testDebugUnitTest :core-ai:testDebugUnitTest :core-weather:testDebugUnitTest :feature-home:testDebugUnitTest :feature-assistant:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

BUILD SUCCESSFUL. Current archived XML:461 results, zero failure/error/skipped: model93,
strings17, ai51, weather256, home38, assistant6. These include UP-TO-DATE results and are not461
new executions. unit-test-summary.json can be recomputed from unit-results. Engine-doctor passed
(engine-doctor.log), and diff whitespace checks passed. Targeted rebuilds and test tasks after
parent corrections are separate logs, not additional distinct cases.

The initial phone runner failed before executing any test: Android ICU rejected the summary
parser regex's unescaped closing delimiters, preventing AppGraph startup. This is a real app bug,
not a resource issue. phone-ui-tests-initial-icu-failure.log is preserved. Parent escaped those
delimiters and added WeatherSummaryParserAndroidTest, exercising the real isolated service with
a fake provider on Android, including whitespace, known IDs, duplicate keys/IDs, extra prose and
unknown IDs. No network/key is used. f5-android-parser-fix-build.log reports success.

The corrected phone run completed17 tests:16 passed, one existing routine-editor test failed with
RootViewWithoutFocusException in its native picker. The subsequent Device screenshot showed an
unrelated app (RelayQA) in foreground. Interference is a plausible explanation, not a proven root
cause. No other session's app was stopped or changed. The phone was detached without shutdown;
a previously-off Pampa_Tablet was opened separately through Device, installed with the same APKs
and reran all17 successfully (tablet-ui-tests.log, OK (17 tests), terminal code-1). This is17 distinct
cases, not33. They comprise6 summary UI,1 Android parser,2 routine,6 overview,1 root-motion and1
singleton wiring test. EN/IT 2x text, explicit selection, malformed IDs, no-config immediate text,
pending location change, routine CRUD/overnight and prior home regressions are exercised.

## Actual app QA and limitations

All UI launches/actions/settings/screenshots use the Device-bound agent-device command. adb is
used only for APK installation and the project's test runner. Physical devices remain untouched.
Full-app tablet captures include unknown-location home and immediate no-config summary, 2x text
and its scroll-reachable settings/close actions, and the existing AI settings route with empty key
fields. Returning to home and selecting saved Roma preserves its Europe/Rome zone and existing
Fahrenheit units. Its cached forecast can still show future values while recent rain is unavailable;
that is not evidence of valid current observations. Home and summary must retain this distinction.
The synthetic Android cases cover configured selection without any live provider credentials.

Parent shortened the repeated generic caveat/refresh text in EN/IT after visual inspection, retaining
model/radar versus local-measurement qualification and unknown coverage. Earlier long-text images
are explicitly before-compact evidence; final images are identified separately. This does not
change source gates, numerical algorithms or the fact-selection contract.

No field accuracy is inferred. Global C2 remains NV/open. Full TalkBack, quantified contrast,
matched p95/memory and physical sensor validation remain NV/open for F6/F7. Synthetic UI tests and
emulator screenshots do not establish those results. Existing Daily2x wrapping, sub-minute spoken
timeline and global upstream wipe/composition-recreation protection remain tracked F6 work.
No publication, source additions, notifications, model retraining, commit or push occurred.

Final compact-resource build/test tasks passed (f5-final-build.log). The same17 Android cases were
rerun on the final APKs: tablet-ui-tests-final.log has17 successful status records, OK (17 tests),
45.142s, terminal code-1. Tested APK hashes/sizes are in tested-apks.json. Earlier run logs are kept
separately; repeated cases do not increase the distinct test count. Final full-app sheet captures
are tablet-final-summary.png/tree and tablet-final-summary-200.png/tree plus the scrolled
200-actions PNG/tree. Initial long-text captures have before-compact in their names. Actual app
UI is English; dedicated final Android cases exercise Italian and English at2x with fake gateways.
The Roma screenshots use cached forecasts and unavailable current rain, not a certified fresh
MODEL-dry assessment; no such visual assertion is made here.

One post-test Device relaunch returned adb timed out after5000ms. A safe adb devices connectivity
check and one Device-command retry succeeded; no shared server reset or other process kill was
used. Final captures and close action then completed. Default font was restored, owned tablet was
shut down through Device, and discovery confirmed it off with this thread's open list empty.
Medium_Phone was left running/detached after the foreign-app focus incident, to avoid shutting
down another session's active work. No physical device was used.

## Version note

This document records F5 round0 integration. Correction1 verification is recorded separately in
weather-f5-round1-fixes.md and artifacts/weather-improvement/f5-round1. Round0 artifacts/manifests/
archive are immutable; earlier result counts are historical and must not be merged with round1.
