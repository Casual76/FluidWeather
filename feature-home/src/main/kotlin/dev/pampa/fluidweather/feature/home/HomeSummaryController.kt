package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HomeSummaryPhase { DEFAULT, LOADING, READY, CANCELLED, NOT_CONFIGURED, FAILED, INVALIDATED }

data class HomeSummaryState(
  val document: WeatherSummaryDocument,
  val phase: HomeSummaryPhase = HomeSummaryPhase.DEFAULT,
  val ids: List<String>? = null,
) {
  fun render(now: Long): String? = if (now < document.validUntilMillis) document.render(ids ?: document.defaultFactIds) else null
}

/** Main-thread owned. No request originates from update, resume or expiry. */
class HomeSummaryController(
  private val gateway: WeatherSummaryGateway,
  private val scope: CoroutineScope,
  private val clock: () -> Long,
  private val generation: () -> Long,
  private val document: (now: Long, wiped: Boolean) -> WeatherSummaryDocument,
) {
  private val initialGeneration = generation()
  val wiped: Boolean get() = generation() != initialGeneration
  private var serial = 0L
  private var active = false
  private var request: Job? = null
  private val mutable = MutableStateFlow(HomeSummaryState(document(clock(), false)))
  val state = mutable.asStateFlow()

  fun resume() { active = true; refresh() }
  fun pause() { active = false; cancel(HomeSummaryPhase.DEFAULT) }

  fun refresh() {
    val old = mutable.value
    val wiped = generation() != initialGeneration
    val fresh = document(clock(), wiped)
    if ((wiped && old.phase != HomeSummaryPhase.INVALIDATED) || fresh.contextKey != old.document.contextKey || clock() >= old.document.validUntilMillis) {
      cancel(if (wiped) HomeSummaryPhase.INVALIDATED else HomeSummaryPhase.DEFAULT)
      mutable.value = HomeSummaryState(fresh, if (wiped) HomeSummaryPhase.INVALIDATED else HomeSummaryPhase.DEFAULT)
    } else if (fresh.validUntilMillis < old.document.validUntilMillis) {
      mutable.value = old.copy(document = fresh)
    }
  }

  fun cancel(phase: HomeSummaryPhase = HomeSummaryPhase.CANCELLED) {
    serial++
    request?.cancel()
    request = null
    mutable.value = mutable.value.copy(phase = phase, ids = null)
  }

  fun request(configured: Boolean) {
    refresh()
    if (!active || generation() != initialGeneration) return
    cancel()
    if (!configured) {
      mutable.value = mutable.value.copy(phase = HomeSummaryPhase.NOT_CONFIGURED)
      return
    }
    val input = mutable.value.document
    if (clock() >= input.validUntilMillis) return
    val ticket = serial
    mutable.value = mutable.value.copy(phase = HomeSummaryPhase.LOADING)
    request = scope.launch {
      val result = try { gateway.summarize(input) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { WeatherSummaryResult(input.contextKey, WeatherSummaryStatus.FAILED) }
      // Refresh before publication also catches expiry and noncooperative completion after wipe.
      if (ticket != serial || !active) return@launch
      refresh()
      if (ticket != serial || !active) return@launch
      val current = mutable.value.document
      if (current.contextKey != input.contextKey || result.contextKey != current.contextKey ||
        clock() >= input.validUntilMillis || clock() >= current.validUntilMillis) {
        mutable.value = HomeSummaryState(current, HomeSummaryPhase.INVALIDATED)
        return@launch
      }
      val phase = when (result.status) {
        WeatherSummaryStatus.READY -> if (current.accepts(result.factIds)) HomeSummaryPhase.READY else HomeSummaryPhase.FAILED
        WeatherSummaryStatus.NOT_CONFIGURED -> HomeSummaryPhase.NOT_CONFIGURED
        WeatherSummaryStatus.FAILED -> HomeSummaryPhase.FAILED
        WeatherSummaryStatus.INVALIDATED -> HomeSummaryPhase.INVALIDATED
      }
      mutable.value = HomeSummaryState(current, phase, result.factIds.takeIf { phase == HomeSummaryPhase.READY })
    }
  }
}
