# F4 integration and evidence

Scope: personal settings, one next occurrence per configured rule, and quick model forecasts
below the rain overview. No additional notifications or provider requests. Engine3.0.5 and the
trained barometer configuration are unchanged. C2 field and global C6 remain NV/open.

## Parent integration and corrections before review

- One graph-owned RoutineWindowStore is passed to home and the settings/routines route. Both
  Home Add/Edit and the settings category navigate to the same Fluid screen. Routine settings
  follow the existing archive-only wipe policy, which preserves settings.
- Explicit core-strings.R imports resolve the two feature-local R namespace collisions found
  in the first compile. That failed log is retained, followed by successful final builds.
- Routine temperatures/amounts now use the shared UnitFormatter and selected units. The domain
  stays Celsius/mm; local resources no longer append metric symbols to converted values.
- The compact forecast groups known metrics in one paragraph; detailed provenance limitations,
  all original intervals/download clocks and unknown model resolution remain expandable. Partial,
  stale, fallback, DST, ongoing and location-mismatch limitations remain in the quick view.
- A new HomeRoutineRead helper handles IOException in settings observation and rechecks without
  terminating weather consumption. It exposes an error, hides obsolete routine output and retries
  observation after30s. Cancellation propagates. Two JVM tests cover recovery and recheck errors.
- The editor handles system Back while a draft is open, returning to its list and respecting the
  busy state. The Android editor test includes discarding an unsaved draft with Back.

## Current automated results

Current copied XML: core-model91, core-data33, core-weather256, core-strings13,
feature-settings11, feature-home28 =432, all zero failure/error/skipped. Some unchanged Gradle
tasks were UP-TO-DATE: these are valid current results, not432 newly executed tests. New tests
cover real disk recreation/concurrent edits/codec failure, DST/overnight/fallback/coverage,
ongoing remaining-only forecasts, config replacement during IO, and editor validation.

Final production/test APK build: f4-compact-build.log (preceded by successful integration,
read-recovery and Back-handler builds). engine-doctor.log is successful; the initial invocation
missing AppRoot is preserved separately. git diff --check succeeds; engine worktree is clean.

The final Android run is tablet-ui-tests-compact-final.log on Pampa_Tablet Android15,2560x1600,
emulator-5558. Independently counted10 successful status0 records, OK(10tests), terminal CODE-1,
in34.595s. The summary is android-test-summary.json.
Tests: two RoutineWindowsUiTest cases plus the previous eight F3 tests. They exercise a2x editor
from empty/invalid through explicit native time selection, overnight create, stable-ID rename,
delete, Back, first-two/expand/callback and Fahrenheit/inch rendering. Existing rain overview
tests retain360/411dp/2x/unknown/conflict/source expansion, root reduced motion and radar identity.
The tests back up and restore the routine settings around each case. Earlier successful10-test
runs are preserved as earlier executions, not counted as additional distinct cases.

## Actual app QA

Device work uses Device tools and the returned agent-device command. adb is limited to APK
installation and the project's AndroidJUnitRunner. Existing booted Medium_Phone/Classeviva and
physical devices were left untouched. Only Pampa_Tablet was opened for this phase.

The final app's home Add link opens the actual editor route. New configuration is empty
(tablet-empty-home.png/tree). A manual Passeggiata rule selects Saturday18:00 to Sunday01:00.
Both native pickers, next-day explanatory text and saving were exercised. System font was changed
to accessibility-extra-large; readback platformValue2.0 is retained in tablet-font-setting.json.
The unsaved name/day/endpoints survived that configuration recreation. Editor2x is captured in
tablet-editor-200.png/tree; actions remain reachable by scrolling (also exercised in Android test).
Default font category large was restored.

After save, the rule appears in the list and home. Process relaunch retains the rule; GPS selection
resets by existing session-only design. Selecting saved Roma uses Europe/Rome and shows different
actual dates/offsets from device GMT fallback. This uses model forecasts, not independent local
observations. Pre-compact routine capture is explicitly named before-compact; final captures are
identified separately: tablet-final-fallback-home and tablet-final-rome-home PNG/trees show the
final compact presentation. The latter has a qualified complete model-dry window, actual overnight
dates in Europe/Rome, original download age, and selected Fahrenheit/inch units. The fallback
capture follows process relaunch and preserves the rule while declaring device-zone use.

The manual test rule was then removed through Edit > Delete. tablet-cleaned-editor-tree.json
explicitly shows No moments saved yet, restoring the initial empty personal configuration.
The owned Pampa_Tablet was closed with shutdown=true; subsequent Device discovery confirms it
is off and this thread has no open device. Other sessions' devices were not stopped.

## Deliberate limits

DST policy and tests are in weather-routine-data-contract.md: shift gap endpoints forward, choose
earlier overlap offsets, skip nonpositive real intervals, and flag adjustments. Rare historical
date-line transitions and underlying Preferences file-corruption replacement lack dedicated
fixtures. Malformed/future encoded settings are retained, edits fail visibly, explicit store
clear recovers; read displays no invented configuration. No downgrade migration is claimed.

Only original matching context is used; fused/current/radar do not substitute unknown forecast
accumulations. Complementary minutely/hourly gaps are not stitched. Native resolution can remain
unknown; no minute accuracy is inferred from data cadence. Complete dry/amount require exact
coverage; partial contained wet evidence does not fill missing parts. Ongoing windows evaluate
only the remaining interval, while retaining the full occurrence for display.

F6 still owns global upstream wipe protection across composition recreation, full TalkBack,
pre-existing Daily2x wrapping and the sub-minute spoken interval finding from F3. F7 owns field
accuracy and the controlled performance/memory comparison. This phase does not approve them.
