package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.RoutineWindow
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/** A settings read failure must not take down the weather consumer or masquerade as empty setup. */
internal fun Flow<List<RoutineWindow>>.recoverHomeRoutines(onReadError: (Boolean) -> Unit): Flow<List<RoutineWindow>> =
  onEach { onReadError(false) }.retryWhen { cause, _ ->
    if (cause !is IOException) return@retryWhen false
    onReadError(true)
    emit(emptyList())
    delay(30_000L)
    true
  }

internal suspend fun readLatestHomeRoutines(
  current: suspend () -> List<RoutineWindow>, onReadError: (Boolean) -> Unit,
): List<RoutineWindow> = try { current() } catch (_: IOException) {
  onReadError(true)
  emptyList()
}
