package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.GeoPoint
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.model.WeatherBrief
import dev.pampa.fluidweather.core.model.WeatherZones
import dev.pampa.fluidweather.core.model.RoutineWindow
import dev.pampa.fluidweather.core.weather.RoutineWindowEvaluator
import java.time.ZoneId
import dev.pampa.fluidweather.core.weather.LocalPrecipitationEvaluator
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class HomeBriefInput(val place: Place, val point: GeoPoint?, val snapshot: WeatherSnapshot?, val windows: List<RoutineWindow> = emptyList())

internal fun homePoint(latitude: Double?, longitude: Double?): GeoPoint? =
  if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
    latitude in -90.0..90.0 && longitude in -180.0..180.0) GeoPoint(latitude, longitude) else null

/** Only display legacy details at the requested point. Domain model validation stays in F2. */
internal fun homeSnapshotMatches(place: Place, point: GeoPoint?, snapshot: WeatherSnapshot): Boolean =
  point != null && snapshot.placeKey == (if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)) &&
    homePoint(snapshot.latitude, snapshot.longitude) != null &&
    snapshot.distanceKmTo(point.latitude, point.longitude) <= 2.0

/**
 * One structured consumer, owned by RESUMED/visible home. collectLatest at the caller cancels
 * obsolete location/content jobs, including snapshots with the same round timestamp.
 * No radar/provider policy or input cache here: both belong to the shared F2 service.
 */
internal class HomeBriefController(
  private val sharedBrief: suspend (Place, GeoPoint?, WeatherSnapshot?) -> WeatherBrief,
  private val clock: () -> Long = System::currentTimeMillis,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val evaluator: LocalPrecipitationEvaluator = LocalPrecipitationEvaluator(),
  private val generation: () -> Long = { 0L },
  private val routineEvaluator: RoutineWindowEvaluator = RoutineWindowEvaluator(),
  private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
) {
  suspend fun consume(input: HomeBriefInput, latestSnapshot: suspend () -> WeatherSnapshot?, emit: (WeatherBrief?) -> Unit) =
    consume(input, latestSnapshot, { input.windows }, emit)

  suspend fun consume(
    input: HomeBriefInput,
    latestSnapshot: suspend () -> WeatherSnapshot?,
    latestWindows: suspend () -> List<RoutineWindow>,
    emit: (WeatherBrief?) -> Unit,
  ) {
    val observed = generation()
    var snapshot = input.snapshot
    while (true) {
      currentCoroutineContext().ensureActive()
      if (generation() != observed) { emit(null); return }
      val cycleStarted = clock()
      val localSnapshot = snapshot?.takeIf { homeSnapshotMatches(input.place, input.point, it) }
      val displayPlace = input.place.copy(zoneId = WeatherZones.validId(input.place.zoneId)
        ?: WeatherZones.validId(localSnapshot?.zoneId)
        ?: WeatherZones.validId(localSnapshot?.context?.zoneId))
      fun WeatherBrief.withRoutines(): WeatherBrief = if (input.windows.isEmpty() && routines.isEmpty()) this else copy(
        routines = routineEvaluator.evaluate(input.windows, input.place, input.point, snapshot, clock(), deviceZone(), Int.MAX_VALUE),
      )
      val immediate = withContext(dispatcher) {
        evaluator.evaluate(displayPlace, input.point, snapshot, radarSample = null, evaluatedAtMillis = clock()).withRoutines()
      }
      currentCoroutineContext().ensureActive()
      if (generation() != observed) { emit(null); return }
      if (latestWindows() != input.windows) { emit(null); return }
      currentCoroutineContext().ensureActive()
      if (generation() != observed) { emit(null); return }
      emit(immediate)
      val result = withContext(dispatcher) { sharedBrief(displayPlace, input.point, snapshot) }
      currentCoroutineContext().ensureActive()
      if (generation() != observed) { emit(null); return }
      // The store can change during radar work without emitting (equal fetch timestamp).
      val afterLoad = latestSnapshot()
      val windowsAfterLoad = latestWindows()
      currentCoroutineContext().ensureActive()
      if (generation() != observed || (snapshot != null && afterLoad == null)) { emit(null); return }
      if (windowsAfterLoad != input.windows) { emit(null); return }
      if (afterLoad != snapshot) { snapshot = afterLoad; continue }
      val enriched = withContext(dispatcher) { result.withRoutines() }
      currentCoroutineContext().ensureActive()
      if (generation() != observed || latestWindows() != input.windows) { emit(null); return }
      currentCoroutineContext().ensureActive()
      if (generation() != observed) { emit(null); return }
      emit(enriched)
      // Reconsume at an ETA boundary too: a passed estimate must never stay future-facing.
      val now = clock()
      val nextTransition = listOfNotNull(result.precipitation.arrivalAtMillis, result.precipitation.endAtMillis)
        .filter { it > now }.minOrNull()
      delay(minOf((cycleStarted + 60_000L - now), nextTransition?.minus(now) ?: 60_000L).coerceAtLeast(1L))
      val latest = latestSnapshot()
      currentCoroutineContext().ensureActive()
      if (generation() != observed || (snapshot != null && latest == null)) { emit(null); return }
      snapshot = latest
    }
  }
}
