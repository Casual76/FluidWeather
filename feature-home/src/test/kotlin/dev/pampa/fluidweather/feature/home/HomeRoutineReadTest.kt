package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.RoutineWindow
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRoutineReadTest {
  @Test fun `failed observation clears routine output marks the error and retries without stopping weather`() = runTest {
    var attempts = 0
    val errors = mutableListOf<Boolean>()
    val outputs = mutableListOf<List<RoutineWindow>>()
    val source = flow<List<RoutineWindow>> {
      if (attempts++ == 0) throw IOException("unreadable")
      emit(emptyList())
      awaitCancellation()
    }
    val job = backgroundScope.launch { source.recoverHomeRoutines(errors::add).collect { outputs += it } }
    runCurrent()
    assertEquals(listOf(true), errors)
    assertEquals(1, outputs.size)
    advanceTimeBy(30_001); runCurrent()
    assertEquals(2, attempts)
    assertEquals(listOf(true, false), errors)
    assertEquals(2, outputs.size)
    job.cancel()
  }
  @Test fun `latest read failure is explicit and cancellation still propagates`() = runTest {
    val errors = mutableListOf<Boolean>()
    assertTrue(readLatestHomeRoutines({ throw IOException("disk") }, errors::add).isEmpty())
    assertEquals(listOf(true), errors)
    try {
      readLatestHomeRoutines({ throw CancellationException("cancelled") }, errors::add)
      fail("cancellation must propagate")
    } catch (_: CancellationException) { }
  }
}
