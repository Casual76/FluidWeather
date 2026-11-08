# F5 deterministic brief and isolated AI selection

Public APIs exactly match `weather-f5-contract.md`:

```kotlin
WeatherBriefFormatter.document(resources: Resources, brief: WeatherBrief?, placeLabel: String,
  units: UnitFormatter, locale: Locale, nowMillis: Long): WeatherSummaryDocument

AiWeatherSummaryService(
  providers: suspend () -> List<ReadyProvider>,
  enabled: suspend () -> Boolean = { true },
  clock: () -> Long = System::currentTimeMillis,
  timeoutMillis: Long = 15_000L,
  maxEntries: Int = 16,
) : WeatherSummaryGateway
```

Formatter package: `dev.pampa.fluidweather.strings`. Service package:
`dev.pampa.fluidweather.core.ai.summary`. No model changes or new dependencies.

## Deterministic rendering

`document` is synchronous, performs no IO, and needs no AI configuration. EN/IT resources live
only in the new dedicated `weather_summary.xml` files. Existing home and routine strings are reused.
The caller must provide Resources and UnitFormatter configured for the supplied locale. Other
languages use the EN catalogue, consistent with the document model's supported language enum.

Mandatory facts retain consulted place, zone (including device fallback), shared rain headline,
transition or explicit unknown, uncertainty, source identity and source limitations. The catalogue
also includes optional source/download/represented-interval/resolution details and up to three
personal windows (included in deterministic defaults). Each window's caveats are inseparable from
its factual sentence. `render(ids)` in the parent-owned DTO always
prepends these facts even when the provider selects only `current`. Unknown/duplicate/empty/overlong
ID selections fall back to deterministic defaults. Facts remain app text; no model prose is rendered.

The shared `WeatherBriefPresentation` supplies qualifiers and approximate absolute transition windows.
The formatter only gates consumption; it does not evaluate wet/dry independently. It suppresses
rain certainty when inputs have expired, provenance is insufficient, evaluation is at least one
minute old, or a transition has already passed. Missing input never becomes dry. Model conditions
are explicitly labelled. Routine amounts require complete coverage; temperature extrema are labelled
as available model samples, never extrema of the entire occurrence. Missing routine coverage and
remaining-period/DST/fallback caveats survive AI selection. More than three routines/four source
detail records are explicitly disclosed and remain accessible in existing details.

Source timestamps and download times are absolute localized dates/times, not relative age prose.
Place zone is preferred, then an explicitly resolved routine zone, then device fallback. Exact routine
occurrence labels retain both dates, offsets and zone. No location coordinates are sent to AI by
the service; context and revision hashes remain local. Place labels and user routine names are
catalogue data, never instructions.

## Expiry and identity

`validUntilMillis` is the earliest future bound among now + 60 seconds, original assessment +
60 seconds, applicable source cutoffs, rain horizon, absolute arrival/end and approximate window
boundaries, and displayed routine assessed/occurrence boundaries. The service rejects documents at
or after expiry and rechecks after IO, including cached selections. Expired source claims are
suppressed at formatting time rather than merely being assigned a new minute of validity.

The new internal policy mirrors the existing source limits without a core-weather dependency:
model downloads 60 minutes, current model valid time 30 minutes, recent precipitation valid time
20 minutes, radar generation 20 minutes (future tolerance at most 60 seconds). Radar download is
also conservatively capped at 20 minutes. A measurement has no freshness contract here and is
not admitted as a current assertion. These are conservative gates; parent should keep them aligned
if F2/F4 policy changes. Unknown download/source times cannot authorize a recent assertion.

The local SHA-256 data revision includes the original revision plus actual rain/evidence/place/point,
current/timeline/accumulation/routine data, excluding evaluation clocks and the sliding rain horizon
origin (its duration remains included). Context also hashes locale, unit preferences, resolved zone,
place label, current validity, shared state/transition and actual localized facts. Changed samples,
same-place data, windows/occurrences, units or language cannot share a selection accidentally.

Consumer requirement for C/parent: only render READY IDs when `result.contextKey == currentDocument.contextKey`
and `clock() < currentDocument.validUntilMillis`. Schedule refresh at the actual `validUntilMillis`,
cancel/discard on context changes, and regenerate deterministic text on expiry. The pure parent DTO's
`render` intentionally has no clock parameter and does not itself enforce expiry. Never keep rendering
an expired document while waiting for the next periodic evaluation. No automatic AI request on refresh.

## Provider request and parser

The injected provider supplier must return the existing user-configured, verified, ordered CHAT
providers; enabled must reflect current AI availability. Only the first provider's `chatModel` is
used, with one `complete`, no fallback, no session, registry, orchestrator, tools, actions, speech,
web search, or followup. Request flags: empty tools, ToolChoice.None, parallelToolCalls=false,
webSearch=false, reasoning=NONE, temperature=0, maxOutputTokens=256, read timeout matching the
bounded overall timeout (default 15 seconds, constructor permits 1–60,000 ms).

The structured payload contains language and facts `{id,text,required}` only. The service does not
send WeatherBrief objects, coordinates, hashes, history, API keys or unrelated settings. The model
must see localized facts to choose IDs; "IDs only" applies to its response and returned result.
Payload admission: at most 32 facts, IDs `[A-Za-z0-9_-]{1,64}`, at most 8,000 characters per fact,
at most 24,000 aggregate fact/id characters. Formatter output is bounded by the source/routine caps.
Oversized externally supplied documents fail before network IO.

The JSON schema permits only `{ "factIds": ["known_id", ...] }`, 1–8 unique known IDs.
An exact restricted JSON grammar independently enforces that shape, rejecting duplicate object
keys, extra fields, free prose, code fences, escaped/exotic IDs, nonstrings, trailing content,
unknown IDs and duplicates. Response text is capped at 1,024 characters. Only STOP finishes without
tool calls or citations are accepted. LENGTH/BLOCKED/OTHER/TOOL_CALLS, textual refusal and malformed
JSON fail closed. The engine DTO has no separate refusal field; BLOCKED or refusal text is rejected.
Raw provider metadata is never rendered, retained in cache, or logged by this service.

## Cache, cancellation and wipe

The bounded in-memory FIFO map stores validated ID lists and their original document expiry only;
default 16 entries, allowed 1–128. Its hash includes full document context/revision/language/units/
catalogue/default IDs and configured provider order plus CHAT model IDs. Hits revalidate current
availability and configuration and apply IDs to the current document. It never caches generated
sentences or extends the original cache entry's lifetime. Failures are not cached.

Configuration is read again after IO. Cancellation propagates; timeout and provider exceptions yield
FAILED without raw errors. Expired context or a clear-generation mismatch yields INVALIDATED.
`clear()` clears entries and increments a mutex-protected generation barrier: pre-wipe work cannot
publish READY or repopulate cache, even if the provider completes late. Clear does not forcibly
cancel transport; caller cancellation owns that. A provider ignoring cancellation may delay return,
but ensureActive prevents its late result from writing cache or returning READY after timeout.

Integration obligation: call `clear()` during app data wipe and changes of provider credentials/account
or other relevant configuration not represented by ReadyProvider. The stabilized DTO exposes order
and model but no safe credential/config revision; detecting same-provider/same-model key rotation
without that clear hook is impossible under this constructor. The service never reads key material.
No API extension was introduced.

## Validation handoff

New tests: WeatherSummaryDocumentTest (mandatory/fallback rendering), WeatherSummaryPolicyTest
(freshness, source cutoffs, shared qualifiers/ETA, revision changes), AiWeatherSummaryServiceTest
(request flags, first provider only, malformed/hallucinated/extra/duplicate/truncated/tool/citation
responses, cache identity/expiry/clear, no configuration, timeout/errors/cancellation, noncooperative
late completion, configuration changes, wipe race and payload admission).

Implementer ran only static inspection, XML parsing/resource-reference/placeholder consistency and
whitespace checks. No Gradle/build/test/device execution or live provider calls. Android Resources
formatting and integrated consumer scheduling still need parent execution/review; tests are written
but unrun. C5 chat publication enforcement belongs to B, summary consumer invalidation to C/parent.
Global C2 field/C6 final performance/full accessibility remain NV.

## F5 correction1: forecast valid timestamps

WeatherEvidence.sourceTimestampMillis is a source VALID time, not necessarily an observation or
issue time. RoutineWindowEvaluator legitimately supplies future model sample valid times. The
summary forecast predicate now allows them when recent=false and current=false, retaining the
original download <=now and download age <60min checks. Current/recent MODEL evidence still rejects
future valid times; radar retains its60s tolerance and20min source/download bounds. Actual
remaining-window coverage and state continue to come from the common evaluator, not the formatter.
Details no longer label a valid future forecast sample stale merely for its valid timestamp.
See weather-f5-round1-fixes.md for executed evaluator-to-formatter regressions and expiry cases.
