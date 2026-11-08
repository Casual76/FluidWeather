**Verdict: CORRECT — Premium-R1-73c919c3ff25.** The four R1 correction targets are supported. One remaining P3 reliability defect prevents my mandatory approval. No averaging is applied.

Reviewed HEAD: `7834adf2ac96387e5745c6b3f16dac6fc1f26236`  
Production inputs: `38323f9a9691db9ad5fea42831e79928c1bc5527`  
Baseline: `8e97f750d8ae23c15b80fd9c89c199d2e3ef14b6`

| Criterion | Score | Vote | Evidence and boundary |
|---|---:|---|---|
| P1.sky | 8/10 | PASS | Viewed tablet Home and day/night sheets. Upper soft clouds remain; no Deck/atlas/CloudVolume implementation. Shared evidence adapter retains original clocks, location matching and present-precipitation gates. Rendering preparation and animation retain lifecycle controls. |
| P2.widget | 8/10 | PASS | Original fallback valid time is displayed separately from download age. Resource locale supplies compact hours. Every essential text line is checked for ellipsis. Archived current run contains 64 distinct production RemoteViews cases. Navigation source and both native methods support requested place/destination behavior. |
| P3.reliability | **7/10** | **CORRECT** | Local hydration is independent of GPS/weather and has generation/cancellation guards. Sensor retry, listener cleanup, serialization, absence hooks and surveillance restart are supported. However, swallowed production read failures can clear displayed cleaning and reset readiness. Finding below. |
| P4.regression | 8/10 | PASS | Protected source areas remain unchanged against baseline; Home cloud drawing is unchanged against R0. Original weather clocks and cache identities remain distinct. Principal-app preservation is supported by archived procedure, not independently observed device execution. |
| P5.delivery | 8/10 evidence portion | PASS / collective OPEN | Frozen files, ZIP and external APK identities verify. Archived build, doctor, signing and current tablet evidence are proportionate. Root still owns the three-review collective gate; this report does not close it. |

**Required correction: production read failures are mistaken for empty local data — priority P2, blocks P3 approval.**

The production boundary differs from the failure simulated by the hydration regression:

- [NowcastUseCase.kt:291](/C:/VibeCoded%20Projects/FluidWeather/core-weather/src/main/kotlin/dev/pampa/fluidweather/core/weather/NowcastUseCase.kt:291) catches a pressure-repository failure and returns `null` from `clean()`.
- [HomeLocalHydration.kt:70](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/HomeLocalHydration.kt:70) receives that as `Result.success(null)`. Its `getOrElse` therefore assigns `null`, removing the previously displayed cleaning result.
- [NowcastUseCase.kt:329](/C:/VibeCoded%20Projects/FluidWeather/core-weather/src/main/kotlin/dev/pampa/fluidweather/core/weather/NowcastUseCase.kt:329) similarly catches the readiness sample-read failure and returns a normal readiness object with `historyHours = 0.0`. Hydration accepts it as successful replacement.
- The recovery test at [HomeLocalHydrationTest.kt:40](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/test/kotlin/dev/pampa/fluidweather/feature/home/HomeLocalHydrationTest.kt:40) throws directly from the injected cleaning reader. That exercises exception preservation, but bypasses the production API’s swallowed-error behavior.

**Repro, inferred from source; not executed:** start with visible cleaning and nonzero readiness history. During a timer or Room-triggered hydration, make the pressure read inside `clean()`/`readiness()` fail. The APIs return their normal fallback values; hydration clears cleaning and can reset readiness despite an intact archive. A later successful read can recover them.

**Consequence:** transient local IO failure causes misleading disappearance/reset of already hydrated sensor information. This contradicts the failure-preservation promise in [weather-local-hydration-contract.md:18](/C:/VibeCoded%20Projects/FluidWeather/docs/weather-local-hydration-contract.md:18).

**Minimum fix:** distinguish failed acquisition from confirmed empty acquisition before replacing cleaning/readiness. Preserve displayed values on failure while retaining deliberate clearing for successfully confirmed empty data and wipe. Add a focused regression that reproduces the production swallowed-error boundary, alongside the existing successful-empty case. No new hardware or process-death prerequisite is needed.

The four R1 corrections pass my scoped assessment:

1. **Fallback validity and concise UI:** `forecastAtMillis` comes from the selected original hourly timestamp; download age remains based on `snapshot.fetchedAtMillis`. I viewed both compact EN/IT stale images and the wide stale image: valid time and `9 h` age are visibly separate. The padding/hero adjustment stays within the existing layout, and glyph/ancestor/overlap assertions remain. Repetitive model disclaimers are unnecessary under the explicit product requirement.
2. **Same-place Home dismissal:** the route owns a consumable Home nonce; acknowledgment checks the matching nonce and Home destination. Superseding non-Home requests clear that nonce, and Home drops the obsolete pending widget target. The native test leaves Hourly open before delivering same-place Home, then checks dismissal without Back or a place change. Repeated identical URI, different-place Home and deleted-place fallback remain covered.
3. **Text and locale:** essential ellipsis checks iterate every `Layout` line, including a second condition line. HourStrip passes the widget resource locale to `TimeFormats.hour`.
4. **Header scrim:** normalized R0/R1 painter comparison isolates the change to `ScrimPainter`. Widget rendering enables it; Home drawing remains unchanged. The viewed empty widget shows attenuated header glare.

My executed checks were strictly read-only:

- Verified **772/772 individual file byte counts and SHA-256 hashes**, on disk and inside the ZIP.
- Recomputed the manifest using the declared ordered `path:sha256`, UTF-8/LF/no-trailing-LF algorithm:  
  `73c919c3ff2524b34ff757333366a8966d9634cca57cc773643141b5ad472a36`.
- Verified 773 ZIP entries, no duplicate names, only the embedded manifest as the extra entry, exact embedded/external manifest equality and valid ZIP CRCs.
- Confirmed `candidate-apks.json` matches embedded external-APK metadata; verified all three external APK sizes/hashes and archive CRCs:

| APK | Bytes | SHA-256 |
|---|---:|---|
| Debug | 73,532,432 | `38050ebb52019142b9cca542cc27655417158a6c1d6dba815f2cc5d2d3b280d2` |
| Android test | 1,315,396 | `64a8e1690dcb926e8ed40847b632805f4714879dba04ee6560eb403246fd46dc` |
| Release | 25,012,599 | `1740bc185417ce07896e70de664a801b02cd9465e4a4727c7381cfe7dd76042f` |

- Confirmed current HEAD and pinned engine commit. Production/test differences between the production-input commit and reviewed HEAD are absent.
- Compared permitted R0 source content and protected baseline paths.
- Parsed the current tablet log: **66 distinct successful methods**, comprising **64 RemoteViews + 2 navigation**, with 66 start and 66 success records.
- Counted archived JVM XML: **244 cases**, zero failures/errors/skips; these include reused outputs.
- Viewed tablet Home, day and night sheets, and all five supplied current widget PNGs.

The **archived executions**, not executions by me, support the 100.645-second tablet run, release build success in 2m24s, clean engine-doctor and release signing/package identity for version 2.1.2/code23. Older tablet and phone runs remain historical. The exact phone APK is unavailable. Release was not device-tested. The generic implicit URL smoke remains **FAILED**; passing explicit-component navigation is separate evidence.

**PASS boundary:** the four R1 corrections and P1/P2/P4 plus P5’s inspectable evidence portion.  
**CORRECT boundary:** the production failure-to-empty hydration path above.  
**OPEN boundary:** root’s collective review gate and the already declared field, launcher, process-death, long-duration and performance limits. Those limits are not newly imposed acceptance requirements.

No files were written; no build/test runner, device, provider/network, secret access or delegation was used.
