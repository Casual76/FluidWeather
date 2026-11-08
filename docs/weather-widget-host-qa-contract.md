# Production widget RemoteViews host QA contract

Implementation base: explicit unpublished local commit `11659c9aae623646db98f6f11afff2f14da58c31`, integrated with `git merge --ff-only` before editing.

## Production API and integration boundary

`feature-appwidget/.../WeatherWidgetContent.kt` exports:

```kotlin
@Composable
fun WeatherWidgetContent(
  model: AppWidgetModel,
  units: UnitFormatter,
  nowMillis: Long,
  target: Intent,
  sky: android.graphics.Bitmap,
)
```

This is the former private `FluidWeatherAppWidget.Body`, moved with its content helpers. The production `provideContent` calls it. Cache reads, place selection, model builder, scenes and `provideGlance` flow are unchanged. No widget density fix is claimed: device measurement is still outstanding.

Debug-only `WeatherWidgetHostRenderer.render(context, case, model)` uses the installed Glance 1.1.1 `@ExperimentalGlanceRemoteViewsApi GlanceRemoteViews().compose(context = context, size = case.size) { WeatherWidgetContent(...) }.remoteViews`. API signatures were inspected from local cached bytecode, not verified by compilation. No reflection, fallback replica, Glance testing tree or API skip is used. `RemoteViews.apply(context, frame)` creates the framework views shown in the Activity. Existing `:engine-widget` is exposed to the app's debug compile classpath; no library version changes.

The renderer invokes `AppWidgetModelBuilder.of` with synthetic `WeatherSnapshot`, fused hourly and multi-day samples and typed current evidence. It loads `moon_nearside` through real Resources and invokes production `SkyScene.of` with the shared atmospheric traits. The rejected cloud atlas has been removed by root integration. There is no repository, GPS, provider or preference access in the fixture/renderer. Expanded uses the production LARGE sky canvas, as the launcher does.

## Debug screenshot host

Component: `dev.pampa.fluidweather.debug/dev.pampa.fluidweather.widgetqa.WeatherWidgetHostActivity`.

Supported typed Intent extras (strictly validated):

| Extra | Type | Values/default |
|---|---|---|
| width, height | int | 110/110, 250/110, 250/200, 250/340; default 250/200 |
| language | String | en, it; default en |
| fontScale | float | 1.0, 1.5; default 1.0 |
| variant | String | empty, stale, current, missing; default current |

Every case uses a long place name. Empty has no snapshot/brief; stale has a nine-hour-old download and forecast fallback; current has five-minute-old typed model current evidence; missing retains forecast rows with unknown temperatures. Time and zone are fixed in `WidgetHostCase`. No actual weather is implied.

Exact Android launch payload example, for the parent's Device command launcher:

```text
intent:#Intent;component=dev.pampa.fluidweather.debug/dev.pampa.fluidweather.widgetqa.WeatherWidgetHostActivity;i.width=250;i.height=340;S.language=it;f.fontScale=1.5;S.variant=current;end
```

Parent must use `device_list`, `device_open(hostId, id)` and the returned **device-bound command**, inspecting `help open` for its supported explicit Activity/Intent launch form, then `device_screenshot`. The agent-device command prefix/options are session-specific and were not obtained in this task (device access was forbidden). Do not substitute an adb UI launch. With no extras, opening the explicit component gives the default case. The status above the frame changes from `Rendering ...` to the case key after inflation; failures display `FAILED ...`. The frame itself contains only the generated RemoteViews. No launcher binding, widget ID, home configuration, global locale or font-scale changes are required. Locale/font changes use `createConfigurationContext`, not global settings or `Locale.setDefault`.

## Parent-owned builds and instrumentation

Root execution update: the phone run `premium-phone-widget-host-test-4.log` completed 64/64 cases in 54.627s, terminal -1, after correcting compact/short layout clipping and long-condition wrapping. This supersedes the allocation's unrun status, not the host-vs-launcher limitations below. The phone was subsequently handed off; final tablet evidence is recorded in `weather-premium-verification.md`.

Commands to run from the integrated checkout (not executed by this implementation agent):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# After the parent installs both APKs on its selected physical target:
adb -s <serial> shell am instrument -w -e class dev.pampa.fluidweather.WeatherWidgetRemoteViewsHostTest dev.pampa.fluidweather.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The instrumentation command is a project test runner invocation, not device UI control. The suite launches the explicit Activity via `ActivityScenario`. It runs 64 independent cases: four sizes x two resource locales x two font scales x four variants. Every successfully inflated case saves a widget-only PNG before layout assertions at:

```text
<debug external files directory>/widget-host-qa/<width>x<height>_<language>_<fontScale>_<variant>.png
```

Typically `/sdcard/Android/data/dev.pampa.fluidweather.debug/files/widget-host-qa/`; use the actual package external-files directory on the target. Parent may collect these artifacts via adb file transfer. These PNGs include any subsequently asserted layout defect.

Assertions check exact frame pixels at target density, configured locale/font scale, actual framework TextViews, nonzero visible text and single-line bounds. Each TextView's real `Layout` is rasterized with spare space; nontransparent glyph pixels (including ascent/descenders) must fit the TextView and every clipping ancestor through the widget root. Pairwise painted bounds must not overlap. Intentional framework end ellipsis is accepted only for the long place name. Essential text must not be ellipsized, and partial glyph clipping is never accepted. Hero temperature must follow and exceed place typography; fixed text counts bound hourly/day rows; future hours must follow the hero, and expanded day labels must be present once each. Empty and stale copy is asserted through localized Resources. Assertions are deliberately allowed to fail for existing compact/large-font layouts; do not lower font scales or row expectations to pass them.

## Limits and outstanding validation

**Builds, test runners, devices, screenshots and network/provider calls were not run.** Only source inspection, local cached API inspection and git diff checks were performed. Compilation and device behavior remain unverified. There is no passing-layout claim and no measured density fix.

This host exercises one real RemoteViews composition per exact size, not launcher negotiation, responsive multi-size binder budgets, launcher insets, refresh scheduling, click routing or real cache IO. No clicks are simulated. Bitmap appearance/cloud fidelity and icon contrast still need physical screenshot inspection. Glyph bounds use framework Layout painting, not OCR; raster effects applied by external launcher hosts are outside this test. A display must fit 250x340dp plus the small status label for a full on-screen screenshot.

Production `HourStrip` currently calls `TimeFormats.time` without an explicit locale, so hour formatting still follows the process default locale. EN/IT resource strings, day labels and fixture unit formatting follow the case Context. The tests use the unchanged production time formatter; they do not claim isolated locale coverage for this existing default-locale call.

The installed debug app retains its ordinary `FluidWeatherApp.onCreate` lifecycle, including graph/scheduler initialization. The QA renderer does not call it, bypass it, reconfigure it or guarantee that the whole debug process performs no background work. Instrumentation also uses the existing runner/Application. The release/principal package and global settings are not touched. If the parent requires a process-wide offline/no-Application-side-effects guarantee, that needs a separately agreed application/runner boundary; it is not silently provided here.
