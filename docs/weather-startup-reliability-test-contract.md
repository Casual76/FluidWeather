# Startup reliability Android integration contract

Base: local unpublished `11659c9aae623646db98f6f11afff2f14da58c31` (HomeLocalHydration and sensor recovery).

## Test and evidence

`dev.pampa.fluidweather.WeatherStartupReliabilityTest#coldGpsDeniedHome_showsPersistedPressureAndHistory_thenConsumesRoomUpdateWithoutRefresh`

This is one instrumentation method using `FluidWeatherApp.graph`, its real persisted pressure/history stores, `ActivityScenario<MainActivity>`, production Home, and native UiAutomation accessibility nodes. It installs no alternate graph, fake Home, helper-only consumer, provider source, or Compose test content.

1. Seed two explicitly SYNTHETIC history records and a raw pressure reading before launching the Activity. Check persistence through graph stores.
2. Require unknown current weather and unavailable rain on cold Home. Reveal the raw pressure tile, with its formatted fixture value and raw station-pressure caption, within eight seconds measured from before Activity launch. Accessibility scrolling can reveal tiles below the fold; there is no pull/manual refresh.
3. Open the production Nowcast detail and its signal/history disclosure. Check the real history StatRow count and alert/watch breakdown, including the persisted fixtures, in the same Home composition. History is not printed on the compact Home tile; this is its actual production presentation.
4. Insert another history fixture while that detail remains visible, then write a new pressure sample through the graph repository. Require the history count and breakdown to update within five seconds, below the hydration timer's sixty seconds. Close the detail and require the new raw pressure on the same Home within five seconds. No recreation, place change, resume cycle, manual refresh or provider result drives this update.
5. Recheck GPS denial, offline state and absence of GPS weather.

Without independent local hydration, denied GPS plus no cached GPS weather returns from the initial Home weather load before reading these local values. This test should fail there. That regression expectation is source-derived; it has **not** been executed on either base or fix.

## Required parent-controlled setup (failed assertion, never a passing skip)

- Run only this method against `dev.pampa.fluidweather.debug`. Parent owns installation, instrumentation runner and physical Samsung phone UI. Do not run the destructive `WeatherLifecycleIntegrationTest` as part of this selection.
- Device already offline: no active network and no networks including VPN/Wi-Fi. The test changes no airplane, network or location settings. No public service is required. App startup may attempt its ordinary background requests, which must be unavailable in this environment.
- Both fine and coarse location permissions already denied. The production provider checks this before requesting GPS; the test neither grants nor revokes permission and never answers a permission dialog.
- Supported real pressure sensor and matching graph capability required. Sensorless tablets fail this contract; there is no tablet substitute or successful assumption/skip.
- GPS weather snapshot already absent; cached release gate absent; calibration inactive; continuous sampling already off; no future pressure samples or verdict records about to age out during the method. These are checked, not obtained through archive deletion or global changes. Existing unrelated history is supported and included in the expected totals.
- Parent must start a fresh instrumentation process. The method checks the lifecycle monitor on the main thread and fails if a MainActivity already exists. “Cold” here means a newly launched Activity/Home composition reading persisted fixtures, **not** proof across process death: the instrumentation process must remain alive to assert and clean up. The method cannot independently certify process freshness.
- Keep unrelated workers/sampling and user interaction quiescent during execution. Ordinary application lifecycle scheduling is left intact. A concurrent real sample may mask the latest fixture or an unrelated verdict may change counts; this is a failure requiring investigation, not grounds to delete unrelated data or weaken assertions.

If these preconditions cannot be obtained within the parent's existing authorization, report the test unavailable. Do not change production package data, device settings or credentials to make it pass.

## Fixture isolation and cleanup

Pressure is written through `graph.pressureRepository.record`, so Room invalidates the **same graph's** observer. `SampleSource` has no synthetic member; every fixture uses a UUID burst ID prefixed `SYNTHETIC-startup-`, with no coordinates. Synthetic verdicts use the same unique tag as `variant`, explicit millisecond primary keys, and `ALLERTA` solely as fixture data.

A second `FluidWeatherDatabase` handle opens the existing Debug database using its production schema. It inserts verdicts with plain `INSERT` (collision abort), rather than the production DAO's `REPLACE` or the history store's throttling, which could overwrite or refuse fixtures in a populated archive. Graph history reads verify the initial persisted rows. This second handle does not stand in for graph invalidation: the update trigger is the pressure write through the graph repository.

Finally closes the Activity, deletes only pressure rows with this run's burst ID and verdicts with both exact timestamp and variant tag, restores Home order, tutorials-disabled flag, onboarding completion and selected place, and closes only the extra database handle. No wipe/clear/prune, credentials, calibration, units, readiness/model/feedback changes, production edits, or new dependencies. Settings are restored to their prior logical values through public store APIs. A crash, killed instrumentation process or hung native operation can interrupt finally; preserve the fixture tag/IDs (logged as `WeatherStartupTest: Fixture cleanup identifier`) for narrowly scoped recovery, never wipe the archive.

## Deadlines and limits

JUnit method timeout: 90 seconds. Each coroutine/store block: five seconds. Initial unavailable-weather and pressure checks share an eight-second deadline from launch. Navigation, disclosure and initial history checks each allow eight seconds; history update, close and final pressure checks each allow five seconds. Polls are 50 ms; accessibility scroll/click retries 150 ms; each traversal is capped at 2,000 nodes. Native Activity/SQLite/accessibility calls can block outside coroutine cancellation; the method timeout is the outer failure bound, not a guarantee that Android native calls are interruptible.

Native assertions require visible app-package nodes and localized strings/unit formatting. They verify semantic visibility, not pixel legibility or screenshots. The history assertion checks a small subtree containing the StatRow label, total and breakdown. No copied state or helper result substitutes for UI proof. Device-specific semantics/layout behavior still requires the parent's first execution.

Implemented but **unbuilt and unrun** by this sub-agent, per scope. No Gradle, runner, device, provider or network call was executed. No sensor short-read method is included, and this test makes no claim about hardware failure recovery, listener cleanup or sampling gaps; those need separate fake-regression and hardware evidence.
