package dev.pampa.fluidweather.feature.home

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomePowerSaveChangesTest {
  @Test fun liveSystemChangesReachSameCollectorAndDisposalReleasesSubscription() = runTest {
    var enabled = false
    var listener: (() -> Unit)? = null
    var closed = false
    val seen = mutableListOf<Boolean>()
    val job = backgroundScope.launch {
      homePowerSaveChanges({ enabled }) { callback ->
        listener = callback
        val unsubscribe: () -> Unit = { closed = true; listener = null }
        unsubscribe
      }.collect(seen::add)
    }
    runCurrent(); assertEquals(listOf(false), seen)
    enabled = true; listener!!.invoke(); runCurrent()
    enabled = false; listener!!.invoke(); runCurrent()
    assertEquals(listOf(false, true, false), seen)
    job.cancelAndJoin()
    assertTrue(closed)
    assertNull(listener)
  }

  @Test fun registrationBeforeInitialReadDoesNotLoseAnAlreadyEnabledSystemSetting() = runTest {
    var enabled = false
    val seen = mutableListOf<Boolean>()
    val job = backgroundScope.launch {
      homePowerSaveChanges({ enabled }) { _ ->
        enabled = true
        val unsubscribe: () -> Unit = { }
        unsubscribe
      }.collect(seen::add)
    }
    runCurrent(); assertEquals(listOf(true), seen)
    job.cancelAndJoin()
  }
}
