package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.BarometerReadiness
import dev.pampa.fluidweather.core.model.GeoPoint
import dev.pampa.fluidweather.core.model.NowcastVerdictRecord
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/** Read-only local data: no forecast evaluation, recording, GPS or provider work. */
internal class HomeLocalHydration(
  private val generation: () -> Long,
  private val cleaning: suspend (Long) -> CleaningResult?,
  private val rawPressure: suspend (Long) -> Double?,
  private val history: suspend (Long) -> List<NowcastVerdictRecord>,
  private val readiness: suspend (Long) -> BarometerReadiness,
) {
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  suspend fun consume(
    signals: Flow<Unit>,
    observed: Long,
    clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    emit: (HomeLocalReading) -> Unit,
  ) {
    val ticks = flow {
      while (true) { emit(Unit); delay(60_000L) }
    }
    merge(signals, ticks).collectLatest {
      val reading = withContext(dispatcher) {
        var result: HomeLocalReading? = null
        refresh(clock(), observed) { result = it }
        result
      }
      if (reading != null && homeReadIsCurrent(generation, observed)) emit(reading)
    }
  }

  suspend fun refresh(now: Long, observed: Long, emit: (HomeLocalReading) -> Unit) {
    if (!homeReadIsCurrent(generation, observed)) return
    val since = now - 24 * 3_600_000L
    val clean = localAttempt { cleaning(since) }
    val raw = localAttempt { rawPressure(since) }
    val records = localAttempt { history(since) }
    val ready = localAttempt { readiness(now) }
    if (homeReadIsCurrent(generation, observed)) emit(HomeLocalReading(clean, raw, records, ready))
  }
}

internal fun homeLocalApplies(place: Place, sensorAvailable: Boolean): Boolean = place.isGps && sensorAvailable

internal data class HomeLocalReading(
  val cleaning: Result<CleaningResult?>,
  val raw: Result<Double?>,
  val history: Result<List<NowcastVerdictRecord>>,
  val readiness: Result<BarometerReadiness>,
) {
  fun applyTo(state: HomeUiState): HomeUiState = state.copy(
    cleaning = cleaning.getOrNull() ?: state.cleaning,
    latestRawPressureHpa = raw.getOrElse { state.latestRawPressureHpa },
    verdictHistory = history.getOrElse { state.verdictHistory },
    readiness = readiness.getOrElse { state.readiness },
  )
}

private suspend fun <T> localAttempt(read: suspend () -> T): Result<T> = try {
  Result.success(read()).also { currentCoroutineContext().ensureActive() }
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (failure: Exception) {
  Result.failure(failure)
}

internal suspend fun homeReadIsCurrent(generation: () -> Long, observed: Long): Boolean {
  currentCoroutineContext().ensureActive()
  return generation() == observed
}

/** Check both suspension boundaries before a disk snapshot can reach state or accent callbacks. */
internal suspend fun homeReadSnapshot(
  generation: () -> Long,
  observed: Long,
  read: suspend () -> WeatherSnapshot?,
  accepts: (WeatherSnapshot) -> Boolean,
  backfill: suspend (WeatherSnapshot) -> Unit,
): WeatherSnapshot? {
  if (!homeReadIsCurrent(generation, observed)) return null
  val snapshot = read()
  if (!homeReadIsCurrent(generation, observed)) return null
  if (snapshot == null || !accepts(snapshot)) return null
  backfill(snapshot)
  return snapshot.takeIf { homeReadIsCurrent(generation, observed) }
}

/** A missing initial fix may adopt only a snapshot explicitly stored as GPS weather. */
internal fun homeSnapshotRecoveryPoint(place: Place, current: GeoPoint?, snapshot: WeatherSnapshot, wiped: Boolean): GeoPoint? =
  when {
    !place.isGps -> homePoint(place.latitude, place.longitude)
    wiped || current == null -> homePoint(snapshot.latitude, snapshot.longitude)
    else -> current
  }?.takeIf { homeSnapshotMatches(place, it, snapshot) }
