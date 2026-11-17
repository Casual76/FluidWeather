package dev.pampa.fluidweather.core.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A completed deletion invalidates this work, rather than cancelling its caller's lifetime. */
class DataWipeInvalidatedException : CancellationException("Data operation invalidated by data wipe")

/** The first read owns the token; nested stages may never renew it after deletion. */
class DataWipeEpoch(val guard: DataWipeGuard, val observed: Long) :
  AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<DataWipeEpoch>

  fun checkCurrent() {
    if (guard.generation() != observed) throw DataWipeInvalidatedException()
  }

  suspend fun <T : Any> write(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    return guard.writeIfUnchanged(observed) {
      currentCoroutineContext().ensureActive()
      block()
    } ?: throw DataWipeInvalidatedException()
  }
}

/** [observed] carries an intent captured before launching or queueing a coroutine. */
suspend fun dataWipeEpoch(guard: DataWipeGuard?, observed: Long? = null): DataWipeEpoch? {
  val inherited = currentCoroutineContext()[DataWipeEpoch]
  if (inherited != null) {
    require(guard == null || inherited.guard === guard) { "Data stages must share DataWipeGuard" }
    if (observed != null && inherited.observed != observed) throw DataWipeInvalidatedException()
    inherited.checkCurrent()
    return inherited
  }
  return guard?.let { DataWipeEpoch(it, observed ?: it.generation()).also(DataWipeEpoch::checkCurrent) }
}

/** Preserve an operation's origin through IO, queues and nested sensor/weather stages. */
suspend fun <T> withDataWipeGeneration(
  guard: DataWipeGuard?,
  observed: Long? = null,
  block: suspend () -> T,
): T {
  currentCoroutineContext().ensureActive()
  val epoch = dataWipeEpoch(guard, observed)
  return if (epoch == null) block() else withContext(epoch) { block() }
}

/** Only the commit holds the deletion mutex; never nest guarded commits. */
suspend fun <T : Any> writeCurrentData(block: suspend () -> T): T {
  currentCoroutineContext().ensureActive()
  val epoch = currentCoroutineContext()[DataWipeEpoch]
  return if (epoch == null) block() else epoch.write(block)
}
