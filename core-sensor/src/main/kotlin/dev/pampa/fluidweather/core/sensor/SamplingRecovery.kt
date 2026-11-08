package dev.pampa.fluidweather.core.sensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Supported hardware returning no samples must not acknowledge a scheduled pass. */
class NoPressureSamplesException : IllegalStateException("Pressure sensor produced no samples")

internal fun requireSamples(stored: Int) {
  if (stored == 0) throw NoPressureSamplesException()
}

internal suspend fun scheduledSensorPass(
  available: Boolean,
  collect: suspend () -> Int,
  completed: suspend () -> Unit,
) {
  if (!available) return
  requireSamples(collect())
  completed()
}

/** Context failure cannot discard local pressure history; caller cancellation still owns it. */
internal suspend fun <T> optionalContext(read: suspend () -> T): T? = try {
  read()
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (_: Exception) {
  null
}

/** One loop per service. Completion and failure both release ownership for a later start. */
internal class SurveillanceLoop(private val scope: CoroutineScope) {
  private var job: Job? = null

  @Synchronized
  fun start(onFinished: () -> Unit, watch: suspend () -> Unit) {
    if (job?.isActive == true) return
    val next = scope.launch(start = CoroutineStart.LAZY) {
      try {
        watch()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // End this foreground session. A subsequent scheduled pass may start a new one.
      } finally {
        onFinished()
      }
    }
    job = next
    next.start()
  }
}
