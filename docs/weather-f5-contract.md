# F5 stable contracts and allocation

F4 approved by both independent Sol high critics on F4-6444b96232f8, all five criteria8.
Prior archives/evidence remain immutable. F5 adds an immediate deterministic summary, optional
on-request AI selection, and shared precipitation facts in chat. No engine/provider upgrade,
new commercial service, notifications, barometer retraining or publication.

## Parent-stabilized APIs

`core-model/WeatherSummary.kt` contains pure JVM DTOs:

```
WeatherSummaryFact(id: String, text: String, required: Boolean = false)
WeatherSummaryDocument(contextKey: String, dataRevision: String, language: String,
  units: UnitPreferences, facts: List<WeatherSummaryFact>, defaultFactIds: List<String>,
  validUntilMillis: Long)
// accepts(ids) checks bounded known unique ids; render(ids=defaultFactIds) always retains required facts.
WeatherSummaryStatus { READY, NOT_CONFIGURED, FAILED, INVALIDATED }
WeatherSummaryResult(contextKey, status, factIds=emptyList(), provider=null, fromCache=false)
fun interface WeatherSummaryGateway {
  suspend fun summarize(document: WeatherSummaryDocument): WeatherSummaryResult
  suspend fun clear() {} // cache/wipe barrier
}
```

Provider prose/errors are deliberately absent from the result API. AI receives the bounded
localized fact catalogue derived from WeatherBrief and personal windows. It can select/order
known ids; facts are rendered by the app, with mandatory source/uncertainty retained. This is an
AI-assisted selection, not unrestricted factual paraphrasing. No extra numbers, times or certainty
can enter through a raw model sentence. Names/tool output are data, never instructions. No need
to send precise coordinates: place identity/revision can be hashed locally.

The existing F3 headline/transition policy was moved without algorithm changes into the pure
`WeatherBriefPresentation` object (BriefRainHeadline/BriefRainTransition). HomeRainPresentation
uses aliases and delegates these two methods; its timeline policy is unchanged. Summary/chat
must use this same qualifier and approximate absolute-window policy. Keep old tests meaningful.

A implements exact APIs:

```
// core-strings
object WeatherBriefFormatter {
  fun document(resources: android.content.res.Resources, brief: WeatherBrief?, placeLabel: String,
    units: UnitFormatter, locale: java.util.Locale, nowMillis: Long): WeatherSummaryDocument
}
// core-ai, package dev.pampa.fluidweather.core.ai.summary
class AiWeatherSummaryService(
  providers: suspend () -> List<dev.antigravity.fluidengine.ai.provider.ReadyProvider>,
  enabled: suspend () -> Boolean = { true },
  clock: () -> Long = System::currentTimeMillis,
  timeoutMillis: Long = 15_000L,
  maxEntries: Int = 16,
) : WeatherSummaryGateway
```

Deterministic document is immediately usable without AI/config/network. Localize EN/IT with
current units and resolved consulted-place zone/fallback. Include present rain, valid approximate
transition or explicit unknown, essential source/limitations, useful current weather/personal
windows where known. Never turn missing input into dry, a model into a measurement, or routine
sample extrema into complete-window extrema. Required facts include main assessment and essential
uncertainty/source limits; AI cannot omit them. Keep the default brief concise, with data detail
available elsewhere. No extra provider requests in formatting/summary service.

Cache selection by data revision, place/point identity, language, units, windows/occurrences and
relevant assessment/transition state. Do not key only on round fetch or brief evaluation time.
Keep original source times and re-render current facts; don't cache stale relative-age prose.
validUntilMillis must enforce the earliest applicable source cutoff, transition/window boundary
or short re-evaluation bound. An expired brief/document/result must not publish a future ETA or
old certainty. Stale/unknown handling must be explicit, not solved only by a prompt.

Use current verified ordered CHAT providers/settings; one selected provider request per user
invocation, bounded tokens/time. No conversation/orchestrator/action gate/registry, webSearch,
tool calls, autonomous followups or raw provider errors. Call complete with tools empty,
ToolChoice.None, webSearch=false and structured schema. Reject malformed/unknown/duplicate ids,
extra free text/fields, refusals/truncation/tool calls; safe deterministic fallback remains.
Cancellation propagates. Wipe clear invalidates cache and late in-flight completion; no key value,
prompt payload or raw response in logs/artifacts. No live AI request needed for tests.

## Ownership and staging

- A fresh Sol high: NEW core-strings WeatherBriefFormatter/support helpers + dedicated
  weather_summary.xml EN/IT; NEW core-ai/summary service/support helpers; focused new tests in
  core-model/core-strings/core-ai; docs/weather-summary-contract.md. No edits to parent-stabilized
  WeatherSummary.kt/WeatherBriefPresentation.kt, existing AI facade/session/tools, home/UI,
  core-weather, AppGraph/navhost, existing resources, engine or build files. Report API needs.
- B fresh Sol high: existing core-ai facade/data/resolver/tools/prompt/session and NEW chat
  grounding helpers/tests; dedicated chat_grounding.xml EN/IT only if needed; docs/weather-chat-
  grounding-contract.md. No A-owned files, home/UI, core-model, AppGraph/navhost, engine or builds.
  May add a minimal shared-service adapter/accessor in core-weather only if necessary and documented;
  preserve F2 policy/cache tests and no AI dependency in weather. Parent handles graph wiring.
- C fresh Sol high AFTER A completes/stabilizes APIs: home summary UI/controller/consumer wiring,
  dedicated UI strings/tests. Separate brief will follow. C does not edit B backend. Max two active
  implementers at once; total may be three. Parent owns integration/global status, all builds/device
  use, version freeze and two independent reviews. No implementer Gradle/device/subagents/commits.

## B chat alignment and factual boundary

Inject WeatherBriefService and RoutineWindowStore/data-generation callback into AiDataSources;
parent supplies them. Resolve selected/saved/ad-hoc place identity AND zone conservatively. The
existing adHoc snapshot key is not a normal Place.id key: don't bypass F2 matching or silently
borrow a different location. Shared precip assessment from the singleton and personal-window
evaluation from the same RoutineWindowEvaluator as home must supply current/imminent rain facts.
Do not copy a second wet/dry/ETA algorithm. Use consumption time after IO, original downloads and
current context, including when a model request takes long or location/data/settings change.

WeatherSummaryTool, UpcomingPrecipitationTool, DryWindowTool and RadarAroundTool must stop giving
contradictory legacy current rain/precise transition claims. Hourly/daily followups remain available
as explicitly model forecast details at their supported resolution. Missing/duplicate/gapped input
cannot become all-dry, zero total or a contiguous dry window. Radar numeric diagnostics can remain
as source-labelled image data, but present rain and transition are the joint shared result; never
reintroduce an independent unqualified radar ETA on top of a conflicting brief.

Prompt instructions alone are insufficient for C5. Add an enforced factual rendering boundary for
current/imminent precipitation answers: AI may refer to allowed fact ids/quoted facts; unverified
rain numbers/times/certainty must not reach visible streaming, final text, voice or conversation
history. Buffer unsafe Answering states until validated/rendered; keep ordinary working states.
Use an app-local wrapper/state flow, no engine patch. If extending the fact catalogue with deeper
tool data, only use actually returned, localized, source-qualified facts and required caveats.
Unknown/expired/mismatched facts yield safe deterministic explanation. Do not silently reduce every
free question to selected-place summary: preserve voice/text, other-place queries, actions, chip
navigation, useful hourly/daily/model/sensor/air/sky explanations and conversation continuity.
Document exactly what is enforced versus any remaining limitation; don't claim universal semantic
validation by regex/prompt. Test actual publication/history paths, not just prompt strings.

Data/place/window/language/unit changes during request must discard obsolete factual responses
and not apply their text to a new context. Re-evaluate after IO. Preserve cancellation. User-caused
and requested in-app actions still use existing gates; no summary action can call them.

## Planned C UI contract

Summary is always available from home and opens immediately with deterministic localized text.
Prefer a compact affordance/sheet integrated with the existing rain header, so the main answer
and personal windows are not buried under duplicate prose. Use actual Fluid components and shared
glass/motion. Add optional AI action with configured availability, loading, cancel/retry and safe
error/configuration route. No automatic request on open/context refresh. Inject WeatherSummaryGateway
through a pure-model interface; feature-home must not depend on core-ai. Parent owns graph service
construction and existing AI settings route callback. Changed context cancels/discards old result;
display current deterministic text and require a new explicit AI request. Cached ids re-render only
against matching valid document; never retain previous-place text. Full C API follows A handoff.

## Phase acceptance

C5.deterministic: concise immediate EN/IT summary without IA, faithful shared states/precision/units.
C5.ai-context: on-request isolated provider use, strict facts, valid cache/invalidation, failures,
cancellation/timeout, context update and wipe. C5.chat-grounding: summary/precip tools/chat use shared
facts and cannot publish invented rain timing/precision/certainty, including streamed/history/voice
paths; existing question/action/navigation flows remain usable. C3.summary-flow: discoverable quick
summary, coherent context, UI usability/font/motion and main hierarchy preserved. C7.integration:
pertinent build/tests/end-to-end, prior API/migration/model unchanged. Reviewers0–10,8 verified
satisfied; below8 correct; max3 correction rounds after initial review. Global C2/C6 remain NV.
