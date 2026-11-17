package dev.pampa.fluidweather.feature.radar

import dev.pampa.fluidweather.core.weather.RadarFrames
import dev.pampa.fluidweather.core.weather.radar.RadarSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update

internal data class RadarRecoveryState(
  val frames: RadarFrames? = null,
  val loading: Boolean = false,
  val framesFailed: Boolean = false,
  val checkedAtMillis: Long = 0L,
  val mapFailed: Boolean = false,
  val mapAttempt: Int = 0,
) {
  val latestFrameMillis: Long? get() = frames?.past?.lastOrNull()?.timeMillis
  val stale: Boolean get() = latestFrameMillis?.let {
    checkedAtMillis - it > RadarSafety.MAX_FRAME_AGE_MILLIS ||
      it - checkedAtMillis > RadarSafety.FUTURE_TOLERANCE_MILLIS
  } ?: false
}

/** Owned by the visible radar's RESUMED lifetime. Failures retain the last dated catalogue. */
internal class RadarRecoveryController(
  private val load: suspend () -> RadarFrames?,
  private val clock: () -> Long = System::currentTimeMillis,
  private val pollMillis: Long = 60_000L,
) {
  private val mutable = MutableStateFlow(RadarRecoveryState())
  val state = mutable.asStateFlow()
  private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  fun mapFailed() { mutable.update { it.copy(mapFailed = true) } }

  fun retry() {
    recoverMap()
    requests.tryEmit(Unit)
  }

  private fun recoverMap() {
    mutable.update { if (it.mapFailed) it.copy(mapFailed = false, mapAttempt = it.mapAttempt + 1) else it }
  }

  /** Re-entering this collector immediately retries; cancelling it stops fetches and the timer. */
  suspend fun observe() {
    recoverMap()
    val ticks = flow {
      emit(Unit)
      while (true) { delay(pollMillis); emit(Unit) }
    }
    try {
      merge(ticks, requests).conflate().collect {
        mutable.update { it.copy(loading = true, checkedAtMillis = clock()) }
        val loaded = try { load()?.takeIf { it.past.isNotEmpty() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        mutable.update { previous -> previous.copy(
          frames = if (loaded == previous.frames) previous.frames else loaded ?: previous.frames,
          loading = false,
          framesFailed = loaded == null,
          checkedAtMillis = clock(),
        ) }
      }
    } finally {
      mutable.update { it.copy(loading = false) }
    }
  }
}
