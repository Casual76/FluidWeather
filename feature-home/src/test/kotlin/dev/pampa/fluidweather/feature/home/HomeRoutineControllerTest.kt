package dev.pampa.fluidweather.feature.home

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.LocalPrecipitationEvaluator
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRoutineControllerTest {
  private val now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
  private val place = Place(1, "Rome", null, 44.0, 11.0, zoneId = "Europe/Rome")
  private val point = GeoPoint(44.0, 11.0)
  private fun rule(id: String) = RoutineWindow(id, id, DayOfWeek.entries.toSet(), LocalTime.of(18, 0), LocalTime.of(19, 0))

  @Test fun `config changes during service cannot restore old assessments even before flow emission`() = runTest {
    var windows = listOf(rule("old"))
    val emitted = mutableListOf<WeatherBrief?>()
    val controller = HomeBriefController({ p, g, s -> delay(500); LocalPrecipitationEvaluator().evaluate(p, g, s, null, now) },
      { now }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, null, windows), { null }, { windows }, emitted::add) }
    runCurrent()
    assertEquals("old", emitted.single()!!.routines.single().occurrence.routine.id)
    windows = emptyList()
    emitted.clear()
    advanceTimeBy(501); runCurrent()
    assertTrue(emitted.isNotEmpty())
    assertTrue(emitted.all { it == null || it.routines.isEmpty() })
    job.cancelAndJoin()
  }

  @Test fun `location and config replacement cancel old work and request every rule`() = runTest {
    val emitted = mutableListOf<WeatherBrief?>()
    val inputs = MutableStateFlow(HomeBriefInput(place, point, null, listOf(rule("old"))))
    val controller = HomeBriefController({ p, g, s -> delay(500); LocalPrecipitationEvaluator().evaluate(p, g, s, null, now) },
      { now + testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { inputs.collectLatest { controller.consume(it, { null }, emitted::add) } }
    runCurrent(); emitted.clear()
    val other = place.copy(id = 2, name = "Tokyo", zoneId = "Asia/Tokyo")
    inputs.value = HomeBriefInput(other, point, null, listOf(rule("one"), rule("two"), rule("three")))
    runCurrent(); advanceTimeBy(501); runCurrent()
    assertTrue(emitted.isNotEmpty())
    assertTrue(emitted.all { it!!.precipitation.place?.id == 2L && it.routines.size == 3 &&
      it.routines.all { a -> a.occurrence.zone.zoneId.id == "Asia/Tokyo" && a.occurrence.routine.id != "old" } })
    job.cancelAndJoin()
  }

  @Test fun `minute refresh advances occurrence after end without a new snapshot`() = runTest {
    val emitted = mutableListOf<WeatherBrief?>()
    val startNow = Instant.parse("2026-11-07T17:59:30Z").toEpochMilli()
    val controller = HomeBriefController({ p, g, s -> LocalPrecipitationEvaluator().evaluate(p, g, s, null, startNow + testScheduler.currentTime) },
      { startNow + testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
    val job = backgroundScope.launch { controller.consume(HomeBriefInput(place, point, null, listOf(rule("walk"))), { null }, emitted::add) }
    runCurrent()
    val first = emitted.last()!!.routines.single().occurrence.interval.startMillis
    advanceTimeBy(60_001); runCurrent()
    assertTrue(emitted.last()!!.routines.single().occurrence.interval.startMillis > first)
    job.cancelAndJoin()
  }
}
