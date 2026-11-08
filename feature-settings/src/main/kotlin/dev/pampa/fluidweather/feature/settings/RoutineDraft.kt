package dev.pampa.fluidweather.feature.settings

import dev.pampa.fluidweather.core.model.RoutineWindow
import java.time.DayOfWeek
import java.time.LocalTime

/** Minute precision is an editor choice, never an inferred forecast duration. */
internal fun validatedRoutine(id: String, name: String, dayMask: Int, startMinute: Int, endMinute: Int): RoutineWindow? {
  if (id.isBlank() || name.isBlank() || startMinute !in 0..1439 || endMinute !in 0..1439 || startMinute == endMinute) return null
  val days = DayOfWeek.entries.filter { dayMask and (1 shl (it.value - 1)) != 0 }.toSet()
  if (days.isEmpty()) return null
  return RoutineWindow(id, name.trim(), days, LocalTime.of(startMinute / 60, startMinute % 60), LocalTime.of(endMinute / 60, endMinute % 60))
}
