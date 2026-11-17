package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.Place
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppWidgetUpdatesTest {
  private val a = Place(1, "A", null, 44.0, 11.0)
  private val b = Place(2, "B", null, 45.0, 12.0)

  @Test fun deletingDisplayedFallbackAndThenLastSavedPlaceInvalidatesWidgetWithoutOtherChanges() = runTest {
    val saved = MutableStateFlow(listOf(Place.gps(), a, b))
    val deliveries = mutableListOf<List<Place>>()
    val job = backgroundScope.launch {
      collectAppWidgetUpdates(MutableStateFlow(emptyMap()), MutableStateFlow(0), MutableStateFlow(0),
        savedPlaces = saved) { deliveries += saved.value }
    }
    runCurrent()
    deliveries.clear()
    saved.value = listOf(Place.gps(), b)
    runCurrent()
    assertEquals(listOf(listOf(Place.gps(), b)), deliveries)
    saved.value = listOf(Place.gps())
    runCurrent()
    assertEquals(listOf(Place.gps()), deliveries.last())
    assertEquals(2, deliveries.size)
    job.cancelAndJoin()
  }

  @Test fun failedDeliveryDoesNotDetachSavedPlaceListenerAndCancellationStopsIt() = runTest {
    val saved = MutableStateFlow(listOf(a))
    var failDelivery = true
    var delivered: List<Place>? = null
    val job = backgroundScope.launch {
      collectAppWidgetUpdates(MutableStateFlow(emptyMap()), MutableStateFlow(0), MutableStateFlow(0),
        savedPlaces = saved) {
        if (failDelivery) throw IllegalStateException("host unavailable")
        delivered = saved.value
      }
    }
    runCurrent(); assertNull(delivered)
    failDelivery = false
    saved.value = listOf(b)
    runCurrent(); assertEquals(listOf(b), delivered)
    job.cancelAndJoin()
    saved.value = emptyList()
    runCurrent(); assertEquals(listOf(b), delivered)
  }
}
