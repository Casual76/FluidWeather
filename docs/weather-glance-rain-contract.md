# Rain glance presentation handoff

Scope: HomeRainOverview, new HomeRainCompact helper, rain-only EN/IT resources and focused tests.
Started at 99f24ca140fe129696bfffc03ee93070071a55de, which contains the requested main base 169c719 and the subsequent acceptance documentation commit. No model, core evaluator, HomeData, HomeScreen, summary, routine, widget, painter or engine changes.

## Visible contract

- A weather/rain/warning Material vector accompanies the short headline. Unavailable remains explicit; forecast-only fallback still says that current rain is unavailable. Radar no-echo wording never becomes ground-level dry.
- Forecast keeps its next-hour title and original place-zone time ticks. Missing segments retain their dotted stroke; the same swatch is paired with ?No data? / ?Dati assenti?. Known zero remains a solid segment.
- Maximum known probability is a droplet plus localized percent. A supplied 0 is rendered; null has no metric. A partial-hour maximum never implies full-hour coverage. Positive total precipitation appears only when the existing presenter supplies a complete-hour total.
- Current conditions use temperature and condition without a visible method sentence. Existing qualified current description remains accessible. No new missing-value defaults.
- Details uses the existing disclosure and shows source identity/type, source timestamps, downloads, effective intervals, temporal/spatial resolution, zone fallback and limits. MODEL_ONLY prose is omitted when the forecast method notice already explains it. Original metadata is not recomputed or replaced with the consumption clock.
- Existing summary action and showCurrent/showForecast behavior and all callers are preserved. The whole card has no tap/long-tap action; disclosure and summary remain separate controls.

## Material and accessibility

Uses the existing GlassTile and its shared LocalFluidCanvasBackdrop. Colors are resolved inside GlassTile so its white glass palette reaches text, icons, timeline and buttons; without a canvas, the existing theme fallback remains. No extra glass layer or painter alteration. Typography uses existing theme slots without shrinking the overall UI. Wrapping headline plus FlowRow metrics/actions support large fonts.

Metric semantics retain full names, so a naked percent is not ambiguous to TalkBack. Timeline semantics retain actual intervals, states, amounts, probability and resolution; bar scale remains named there. Decorative icons have no redundant description. Each gap key has the existing explanatory accessibility label once. No chart is rendered for an entirely unavailable forecast.

## Unchanged reliability boundaries

HomeRainPresentation.kt and all evaluators are untouched. Current versus future selection, model versus radar distinctions, overlap/unknown handling, clipping without prorating, conflict/uncertainty, transition windows and expiry guards are unchanged. Sources and limits are disclosed rather than removed. No cache/wipe/time semantics or network/provider activity changed.

## Verification and parent tasks

Only static inspection, XML parsing, resource parity/reference checks and git diff whitespace/scope checks were performed. No build, Gradle, test runner, device, network, provider, secret, publication, version bump, push or PR activity. Tests below are written/updated but UNRUN; compilation and runtime behavior are not certified.

- HomeRainOverviewTest: 360/411 dp, unavailable no-chart heading, conflict disclosure and no ETA, summary callback and hidden-forecast contract, real 0 versus null probability, gap key, EN and IT populated partial forecast at 200% font, source toggle and text overflow/reachability.
- HomeRainCompactContractTest: partial dry cannot become current dry or a full-hour zero amount, known zero versus holes, absent probability, expired timeline unavailable.
- Existing HomeRainPresentationTest remains unchanged for transition, clipping, overlap, provenance and source-clock behavior.

Parent owns compilation/tests and physical tablet checks, including real white ink on bright and dark sky glass, uncertainty/dry/gap stroke readability, large-font FlowRow layout, disclosure with long source clocks and accessibility traversal. Parent integrates the independent units then runs the three fresh reviews. This unit does not repair summary/routine/widget surfaces.
