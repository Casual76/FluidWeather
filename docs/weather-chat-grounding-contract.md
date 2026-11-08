# F5 chat grounding: implementation B

## Parent integration

AiDataSources has exactly three NEW required trailing constructor arguments:

```kotlin
weatherBriefService: WeatherBriefService,
routineWindowStore: RoutineWindowStore,
generation: () -> Long,
```

Pass the SAME singleton brief service and personal-window store used by home, and the same
monotonic wipe/data generation callback. No new service is constructed here. The parent constructs
A's isolated AiWeatherSummaryService separately after AiAssistant; B does not depend on that
service or change AppGraph, build files, home/UI, engine or parent models.

ResolvedPlace adds compatible optional trailing `zoneId: String? = null`. Saved/geocoded places
retain their IANA metadata. GPS/legacy places use only matching snapshot/context metadata before
explicit device fallback. WeatherBriefFormatter receives the resolved assessment-place zone.

New implementation files: core-ai/grounding/ChatGrounding.kt, SharedWeatherFacts.kt,
tools/WeatherExplanationTool.kt; test grounding/ChatPublicationGuardTest.kt. Existing changes:
data/AiDataSources.kt, data/PlaceResolver.kt, orchestrator/AssistantSession.kt,
tools/Tools.kt, AllTools.kt, SummaryTools.kt, PrecipTools.kt, HourlyTools.kt, DailyTools.kt.
All paths above are relative to the existing main/test Kotlin dev/pampa/fluidweather/core/ai root.

## Shared facts, location and time

WeatherSummaryTool and DryWindowTool use sharedWeatherFacts, the injected WeatherBriefService,
RoutineWindowEvaluator and A's exact WeatherBriefFormatter.document API. UpcomingPrecipitationTool
adds actual hourly forecast samples, explicitly qualified as model samples rather than onset/end.
NowTool also includes the joint assessment. The legacy independent fused-hour wet/dry runs,
precise onset/end, null-to-zero precipitation sums and all-dry-gap claims are removed from those
entrypoints. Daily totals are sums of available non-null samples with counts, not full-day totals;
missing aggregate temperature no longer becomes a displayed zero. Original F2 numerics unchanged.

Ad-hoc identity is established before fetching: SHA-256 of full coordinate bit representations,
63-bit non-GPS id, normal WeatherSnapshot.keyFor(id). ResolvedPlace.placeId remains null for
unsaved locations, preserving action semantics. Existing rounded ai-* snapshots are not relabelled,
borrowed or deleted. Shared F2 identity and <=2km point checks stay intact; a hypothetical hash
collision still cannot bypass F2's point check. No weather-to-AI dependency or weather adapter added.

Radar retains image dBZ/rate, nearest echo, geographic coverage, frame age, trend and motion,
labelled RainViewer/image estimates. Its own current-rain/arrival/end narrative is removed.
Only shared facts can supply present-rain assessment and transitions. The existing diagnostic
constructor remains for the original F2 consumption-time regression tests. Frame usability is
checked after sampling (including the existing <=20min cutoff); absolute ETA is used only to reject
passed transitions in diagnostics, never to publish an independent arrival/end. The shared service
retains full-resolution absolute transition evaluation and the shared presentation window policy.

## Actual publication boundary

Each request carries ChatGrounding as a coroutine context element, including parallel tool work.
WeatherTool.run records only app-rendered returned tool facts with IDs. Shared documents are
mandatory entries; model selection cannot discard their required source/uncertainty text.
Model responses must be an exact JSON object with factIds, 1..8 distinct known string IDs.
Malformed text, unknown/duplicate IDs and extra fields fall back to deterministic returned facts.
No provider prose is rendered, even when it sounds safe. This is a capability catalogue, not a
semantic regex validator. It applies to every answer, preventing a precipitation question from
evading the boundary by being misclassified as an unrelated question.

bufferedAnswer is the actual AssistantSession orchestration wrapper. Engine Answering, Done,
partial-bearing failures and cancellation states use a PRIVATE state flow. Only ordinary working
states relay to the public flow; the existing confirmation gate continues to own confirmations.
The engine receives a private Conversation copy. commitGroundedHistory stores the safe displayed
answer and filtered chips in the public conversation only after successful validation. Raw tool
rounds are cleared; follow-ups retain safe exchanges and loaded groups and must fetch new facts.
Voice observes the same safe public Done answer. Provider failures cannot expose raw partials.
Cancellation propagates and never commits the private exchange.

Navigation chips preserve static valid targets. Place chip values must match an actually consulted
place name, preventing arbitrary model chip prose from bypassing the text guard. Requested actions
still use existing gates. If an action changes context, old meteorological facts are discarded and
the app-returned action outcome is retained alongside the context-changed explanation.

## Revalidation

After tool IO and again after the model request: compare wipe/data generation, selected identity,
saved place configuration, personal windows, units, locale, device zone and assistant settings.
Every resolver snapshot consumption records the full snapshot object, not just fetchedAtMillis.
A different object consumed under the same key marks mixed context; final store reads also reject
same-timestamp content updates, removal and any consulted location's changed data. Re-resolve GPS
before publication. Request cancellation is checked after IO. Shared document validUntilMillis and
all returned fact deadlines are checked at final rendering, so a long model request cannot publish
an expired transition. Changes produce a localized retry explanation instead of old weather.

## Scope and remaining limits

This guarantees provider prose cannot invent new displayed weather claims. It does not certify
source correctness, tool selection relevance, place names supplied by upstream data, or universal
semantic accuracy of every pre-existing diagnostic tool. Data-store/config reads are individually
checked; there is no cross-store transaction. The parent's generation callback remains essential
for wipe invalidation, including change-and-revert while a request is running.

Shared summary facts and new curated general explanations are EN/IT. Existing detailed tool bodies
still contain legacy Italian field labels (including some hourly/daily/radar/sensor/air/sky rows),
with bilingual source/forecast qualifiers where added; this is an explicit remaining localization
limit. Model prose is deliberately not used as a translation fallback. The curated explanation tool
preserves useful general radar/probability/sensor/forecast/air/sky answers without automatically
turning every question into a selected-place summary. Unsupported free-form explanation requests
with no returned facts receive a localized no-facts answer. Deep details can be longer than the
old free-prose chat because entire app-qualified returned blocks are indivisible facts.

No live provider calls, plaintext secrets, payload dumps, provider upgrades, barometer changes,
notifications, publishing or commits. Global C2 and C6 remain NV. C5 integration/runtime acceptance
is for the parent to verify after wiring; this handoff does not claim device or build verification.

## Verification performed by B

Read the F5 contract first; read Fluid Engine and Material3 Expressive skills plus Fluid Engine
rules/AI integration guidance. Static source/API inspection, focused rg searches and git diff --check
on the touched existing core-ai paths. No Gradle, compiler, test runner, build, device or live
provider execution. Earlier phase changes and archived artifacts were preserved.

Wrote 14 focused JVM tests (UNRUN) for exact ID rendering, malicious precise/certain prose,
unknown/duplicate/extra fields, mandatory caveats, expired/changed context, same-fetch-time content
updates, mixed consulted places, no data, actual buffered stream/history path, cancellation, errors,
action outcomes after context change, malicious chip text, canonical ad-hoc identities, and actual
WeatherTool.run registration with a fake subsequent provider answer. Existing RadarAroundToolTest
consumption-time/freshness regressions remain unchanged and UNRUN.

## F5 correction1: tool argument boundary and advertised horizons

Catalogue IDs alone do not certify text semantics. Tool display text must be formed from domain
records, parsed values/canonical enums or fixed localized ToolFailure messages; never interpolate
an unrestricted model argument or exception message into a displayable error/receipt. The audit
removed such echoes from invalid day input, empty search/save results, unresolved comparison names,
saved-place removal and observation deletion. Unit parser prefixes remain convenient but the
receipt uses the canonical SetUnit enum value, not its input suffix. Official-alert retrieval
errors use fixed text. Resolved geocoder/archive place names and user-owned names remain domain
data; no universal certification of external/user text is asserted.

WeatherPlaceResolver is a domain IO interface implemented by the existing resolver; actual
snapshot observation and place resolution remain in PlaceResolver. Real tools can now be exercised
with fake domain records through tool.run, the request catalogue, selection/fallback and safe
history commit. ChatToolBoundaryTest covers malicious arguments on DayDetailTool, SearchPlaceTool,
SavePlaceTool, ComparePlacesTool and UnitsTool. No live model is needed to reproduce this ingress.

meteo_riepilogo now advertises only current shared conditions, approximate transitions and configured
personal windows, with explicit routes to previsione_giorni/giorno_dettaglio and hourly tools.
Its unused giorni field was removed. finestra_asciutta likewise exposes no unused ore field and
explicitly does not promise a longest dry interval. Existing tool IDs remain stable. Hourly and
daily tool horizons remain implemented by their own existing parameters.
