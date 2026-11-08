# Weather widget navigation contract

The launcher draws cached data for a resolved place. A tap must select that same
place before opening Nowcast, Hourly or Daily. Selection remains session-only;
ordinary application startup still defaults to GPS.

## URI and identity

`AppDestination.Widget(widget, placeId: Long? = null)` adds an optional stable
repository ID. `AppDeepLink.uri` writes it as `?placeId=<Long>`; `parse` reads it.
GPS is the explicit ID `-1`. There are no coordinates, names or fabricated places
in the request. Android PendingIntent identity includes the complete data URI,
so two places targeting Hourly remain distinct even if extras are ignored.

`FluidWeatherAppWidget` attaches the ID only after its existing stable-place and
cached-input checks pass. This also applies when a stable saved place has no
weather cache yet. Unresolved/unstable reads carry no ID. `WeatherWidgetContent`
preserves the base URI place ID when retargeting condition, hours and days. The
outer widget tap opens Home using the same target identity. `AppDestination.Home`
keeps this distinct from the condition's Nowcast target. Its route owner resolves
the saved ID before returning to Home and consumes the request without opening a
detail sheet. The Home URI also participates in PendingIntent identity. No widget network
or GPS acquisition is introduced.

## Delivery and ordering

Correction1: root Home requests carry a consumable nonce to the Home owner. It dismisses an active widget detail even when the place ID is unchanged, then acknowledges only the matching Home request. Superseding non-Home requests clear the pending Home nonce; a Home request also drops an obsolete pending widget target. The native integration regression deliberately leaves Hourly open for the same-place root request, rather than closing it beforehand.

MainActivity retains its request serial for cold launch and onNewIntent delivery.
NavHost forwards widget, optional place ID and serial to HomeScreen. Home waits
for the first actual repository emission, rather than treating the initial GPS
placeholder as proof that a saved place was deleted. WidgetPlaceTarget resolves
only IDs in that emission; explicit GPS is accepted without a saved row.

For a targeted request Home selects the resolved ID in SelectedPlaceStore. It
withholds the requested detail until both the selected ID and the composed
selected Place match. The existing rememberHomeState holder is keyed by Place,
so a new place starts with its own state, never the previous place's weather.
Detail opening does not wait for providers, a GPS fix, or weather availability;
empty data remains an ordinary detail loading/unavailable state. Existing detail
selection is keyed by place ID, so a change cannot retain an old location sheet.
The request serial also keys the open effect, supporting repeated same-target
requests. Consumption clears the pending widget target in NavHost/MainActivity.

Unknown or deleted numeric IDs resolve to explicit GPS immediately after the
repository emission. Deletion during pending delivery re-resolves the target.
It never creates a saved place or waits for a missing ID to appear. Legacy and
assistant links without a place ID retain session selection and open as before.
Malformed/overflow IDs are treated as absent. Existing `fluidweather://widget/?`
and `fluidweather://open/?` paths, screen links and IDs remain accepted.

## Verification and limits

Added JVM WidgetPlaceTargetTest covers saved membership, deletion/unknown
fallback, GPS and absent legacy identity. Added Android
WeatherWidgetNavigationTest covers URI round trips, Intent.filterEquals identity,
legacy/malformed/screen compatibility and the real MainActivity/graph path:
GPS-default cold Activity -> synthetic saved-place Hourly with owned cached
probability -> dismiss/repeat identical URI -> delete saved place -> GPS Hourly.
It checks the cached Hourly row as well as SelectedPlaceStore, not selection alone.

Allocation tests were unrun by the implementer. Root subsequently compiled them
and `premium-tablet-navigation-passed.log` records 2/2 in7.442s, terminal -1, on
SM-X710. The native runtime flow includes consecutive identical Hourly requests,
root Home without a sheet and deleted-place GPS fallback. Back closes each detail.
This supersedes the allocation's unrun status. Parent owns all Gradle,
build and physical-device work. The integration source requires an offline debug
package, no existing MainActivity, completed onboarding and no release gate. It temporarily
disables Debug tutorials and restores their prior setting in finally. It
changes no global settings and does not wipe data. It removes only its unique
saved-place row and snapshot file, restores session selection and closes its own
Activity. Instrumentation shares the application process: this exercises a cold
Activity starting from GPS, not OS process-death restoration. Root's integration
test now observes the native visible package/window rather than waiting for global
Compose idleness on an animated Home. It requires the Hourly title, close control,
the unique cached 37% fixture and the selected repository identity together; then
tests a consecutive identical URI, a root Home target and deletion fallback.
Cleanup runs even if Activity closure fails and retains a primary assertion error.
An explicit optional `cleanupOwnedPlaceId` instrumentation argument can recover a
known failed-run fixture only after validating its SYNTHETIC UUID name and exact
fixture coordinates; no archive scan or broad wipe is performed. Real launcher
PendingIntent delivery and physical tablet rendering still require parent QA.
Engine stays at 3.0.5; no model, cache runtime, feedback, sky, layout, dependency
or release version changes belong to this patch.
