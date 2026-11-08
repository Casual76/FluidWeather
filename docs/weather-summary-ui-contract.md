# F5 C summary UI handoff

Implementation scope: feature-home UI/controller and dependency surface, dedicated NEW
core-strings weather_summary_ui.xml EN/IT, NEW controller tests and NEW app Android UI tests.
Earlier F0-F4 work, formatter/service/model/backend/data/weather, engine 3.0.5, dependencies,
AppGraph/navhost, builds and frozen artifacts were preserved. B owns chat grounding; parent owns
integration, all execution, review and global status. Global C2/C6 remain NV.

## Parent wiring (required)

- HomeDependencies adds required `weatherSummaryGateway: WeatherSummaryGateway` (pure core-model).
  Parent must inject `graph.weatherSummaryService`.
- HomeScreen adds required `onOpenAiSettings: () -> Unit`; parent wires existing `Routes.Ai`.
- HomeRainOverview adds optional `onOpenSummary: (() -> Unit)? = null`, preserving prior callers.
- HomeSummarySheet is public for focused Android testing; parameters: visible, brief, placeLabel,
  gateway, configured, generation, onDismiss, onOpenAiSettings, active=true. It needs the existing
  FluidGlassModalHost in composition. Production uses HomeShell's shared chrome backdrop.
- AI availability uses `assistant?.enabled == true`; deterministic summary is always available.
- Parent must retain A's service.clear() wipe/provider credential/account/configuration hook.
  UI observes existing deps.dataGeneration; it cannot observe unannounced credential rotation.

## Product and ownership

A compact Quick summary action is alongside source expansion under the existing rain answer and
one-hour timeline. FlowRow wraps the two actions at large font scale. Routine windows, details,
grid reorder, root rain hierarchy and existing source expansion remain in place.

The sheet is FluidGlassModalPortal(Sheet) with FluidSheetScaffold, existing shared backdrop,
engine motion, themed text and Fluid buttons. Body and actions scroll together so 2x text does
not let pinned actions consume the body budget on short displays. Underlying home grid, compact
header and floating bar use the existing engine modal accessibility obscuring modifier. Full
screen-reader focus/motion/glass contrast behavior still requires parent's device review.

Opening, refresh, resume, expiry and context changes perform no gateway request. Formatter text
appears synchronously without AI. Only the explicit select/retry action invokes summarize. Loading
and cancellation retain deterministic text; READY renders only valid IDs through document.render,
retaining mandatory rain/source/uncertainty. Unknown/duplicate/empty IDs fail to deterministic
fallback; provider/error metadata is never displayed. NOT_CONFIGURED offers the existing settings
callback. FAILED/INVALIDATED/cancelled states offer localized explanation/retry when usable.

## Expiry and race policy

HomeSummaryController is pure Kotlin and main-thread confined, using an injected scope, clock,
generation getter and `(now, wiped) -> WeatherSummaryDocument` factory. It exposes StateFlow state,
resume/pause/refresh/request/cancel, and a wipe barrier. Its render helper returns null at expiry.

The visible session supplies the latest brief/place/resources/locale/units on every regeneration;
windows are inside WeatherBrief. On input change, synchronous formatting gates rendering by fresh
context before the post-composition controller refresh cancels the old request. HomeShell also
rejects a brief belonging to a different selected place id. No previous-location selection persists.

While owned and RESUMED a cancellable deadline effect regenerates at actual validUntilMillis and
is rescheduled if context introduces an earlier deadline. A bounded 1-second generation observer
also refreshes; no wait for HomeBriefController's periodic tick is required. Same-context polling
does not extend document/selection validity. Current rendering also checks the consumption clock
and synchronously formats safe text if the stored document has expired. On resume, refresh occurs
before any new selection is admitted. Paused ownership cancels requests and drops selected IDs.

Every request captures a monotonically increasing ticket and document context/expiry. Completion
refreshes the current document and checks ticket, active ownership, generation, matching result
context and both expiries. Context change, deadline, cancel, pause/cover/close or wipe increments
the ticket and cancels the job. Noncooperative late completion cannot restore selection, loading
or error state. Scrim/back/drag dismiss and explicit close pause before dismissing. On wipe, the
session factory formats a null brief; a new explicit request is blocked for that session. Close and
reopen after home data refresh establishes a new generation. Wipe observation is bounded by 1s;
publication checks the generation synchronously even before that observation. Service.clear is an
independent parent-owned backend cache barrier.

## Tests written, not executed

`feature-home/.../HomeSummaryControllerTest.kt` covers zero automatic requests, source/mandatory
retention, invalid IDs, provider exceptions with no error leakage, configuration/retry, pending
place/language/units/window context changes, noncooperative cancellation/close/pause, exact expiry
without a domain tick, mismatched result contexts and wipe barriers. All use fake gateways/clocks.

`app/src/androidTest/.../HomeSummarySheetTest.kt` uses actual formatter/resources/Fluid host and a
fake gateway: immediate no-config opening/settings route, explicit safe selection, malformed IDs,
pending context change, IT/EN at 2x font with scrollable actions and text overflow assertions.
These are synthetic UI checks and do not establish meteorological accuracy or production device QA.

## Checks actually done and remaining limits

Done: allocation/contracts/engine source and skill reference inspection; source-level API and
ownership inspection; Python XML parsing, matching 13 unique EN/IT UI keys and reference resolution;
encoding check for replacement question marks; scoped whitespace checks. No Gradle, build, unit or
Android test execution, emulator/device use, child agents, commits/push, provider/network calls or
secret access. No runtime success is claimed.

Parent must compile after graph/callback wiring, execute focused tests and existing home/routine
regressions, and inspect first viewport, sheet entry/exit, TalkBack focus, small/landscape displays,
EN/IT 2x text, reduced motion, bright/dark backdrop contrast and lifecycle expiry/context/wipe races.
The tests and source have not been compiler-validated by C; all integrated acceptance/review remains
with parent. B's chat enforcement is outside this implementation.
