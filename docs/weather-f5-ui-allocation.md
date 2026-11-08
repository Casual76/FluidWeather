# F5 C UI contract after A handoff

A complete; exact APIs implemented in docs/weather-summary-contract.md and weather-f5-contract.md.
B backend chat/tools remains a separate active writer; C must not touch its files. Parent owns graph,
navhost, builds/device/global state/reviews. Maximum two implementers active (B+C); C is fresh Sol high.

Use WeatherBriefFormatter.document(Resources,brief,placeLabel,UnitFormatter,Locale,nowMillis) and
pure-model WeatherSummaryGateway/Document/Result. Domain rendering prepends mandatory source and
uncertainty facts. Gateway returns IDs/status, never model prose. WeatherBrief.routines already
exists; preserve home's existing structured consumer and main rain answer.

C owns feature-home HomeScreen/HomeRainOverview integration, new summary sheet/controller/tests,
HomeDependencies gateway field, optional callback for existing AI settings route, dedicated new
weather_summary_ui.xml EN/IT and NEW app/androidTest summary UI tests. No A formatter/service/model
edits, B core-ai files, core-weather/core-data, AppGraph/navhost/build.gradle/engine or prior artifacts.
Public Home callback should be `onOpenAiSettings:()->Unit`; HomeDependencies new required
`weatherSummaryGateway: WeatherSummaryGateway`. Parent injects graph.weatherSummaryService and
wires callback to existing Routes.Ai. An absent AI configuration must not disable deterministic UI.

Expose a compact Quick summary action from the existing rain header (below answer/timeline,
alongside source affordance). HomeRainOverview adds optional callback with default null so existing
test/caller behavior stays compatible. Open an actual Fluid sheet using existing app/engine patterns,
with immediate localized deterministic facts and source limitations, no automatic AI call on open.
Use existing shared glass/backdrop/motion, not a new full-screen capture or custom Material sheet.
Keep root rain answer first viewport and personal windows reachable. Appropriate scroll/2x semantics.

Sheet/controller tracks the latest brief/place/locale/units/windows. Build a current document on
change and at exact validUntilMillis, under visible/RESUMED ownership. Document.render has no clock:
consumer MUST NOT render expired claims while waiting for domain's periodic tick. At a cutoff,
regenerate the formatter's safe text immediately; no automatic network/AI request on refresh.
Request result applied only when contextKey matches latest document and clock<validUntil, with
request-generation guard and current active ownership. Cancel/discard on context change, close,
pause or wipe. Canceled/noncooperative fake results cannot resurrect old text/loading/error state.
Current deterministic text stays available through loading/error/cancel. No provider errors exposed.

Explicit AI action is available when home assistant availability is configured (can use the existing
HomeAssistantBar.enabled boolean). Otherwise offer existing AI settings callback alongside usable
deterministic summary; no inert CTA or hidden verification request. Loading, cancel/retry and
NOT_CONFIGURED/FAILED/INVALIDATED states use localized product language. AI selection cached by A
is rendered from the current document; required caveats survive selection. New context means old AI
selection clears and a new explicit user request is needed. Do not leak previous-location text.

Add meaningful coroutine tests for late response/context changes, no automatic calls, expiry,
closing/cancellation/error paths; instrumented tests with fake pure gateway for open-immediate,
not-configured, successful safe ID selection, malformed IDs/fallback, updated context during request,
EN/IT and2x layout. Existing pinned dependencies should suffice; ask parent for any dependency need,
don't edit Gradle or run builds/tests/device yourself. Use actual Fluid + Material3 skill instructions.
No child agents/commits/push/live keys. Parent tests after B/C finish. Handoff exact new functions,
fields/callbacks, refresh/cancellation policy, checks actually done and unverified limits.
