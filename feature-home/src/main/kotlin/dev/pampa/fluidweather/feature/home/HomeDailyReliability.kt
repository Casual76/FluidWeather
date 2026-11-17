package dev.pampa.fluidweather.feature.home

import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.fluidweather.core.model.WeatherZones
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** A changed local palette retains the user's nonvisual preferences. */
internal fun homeThemeSettings(settings: EngineSettings): EngineSettings =
  settings.copy(themeMode = ThemeMode.DARK, dynamicColorEnabled = false)

/** Metadata is never created from the device fallback. */
internal fun homeWeatherZone(state: HomeUiState, device: ZoneId = ZoneId.systemDefault()) =
  WeatherZones.resolve(state.zoneId, state.weatherBrief?.precipitation?.place?.zoneId, device)

internal fun homeSourceTime(snapshot: WeatherSnapshot, nowMillis: Long): Long? =
  snapshot.sourceDataAtMillis?.takeIf { it in 0L..nowMillis }

/** Both automatic acquisition and an explicit gesture use this real IO/fallback boundary. */
internal suspend fun loadHomeForecast(
  cached: WeatherSnapshot?,
  nowMillis: Long,
  cadenceMillis: Long,
  canRefresh: Boolean,
  explicit: Boolean,
  refresh: suspend () -> WeatherSnapshot,
): WeatherSnapshot? {
  if (!canRefresh || (!explicit && cached != null && cached.ageMillis(nowMillis) <= cadenceMillis)) return cached
  return try { refresh() }
  catch (cancelled: CancellationException) { throw cancelled }
  catch (_: Exception) { cached }
}

/** Register before reading, and release the system subscription when Home leaves composition. */
internal fun homePowerSaveChanges(read: () -> Boolean, subscribe: (() -> Unit) -> (() -> Unit)) = callbackFlow {
  val changed: () -> Unit = { trySend(read()); Unit }
  val unsubscribe = subscribe(changed)
  changed()
  awaitClose { unsubscribe() }
}.distinctUntilChanged()
