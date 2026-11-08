package dev.pampa.fluidweather.feature.settings

import java.time.DayOfWeek
import org.junit.Assert.*
import org.junit.Test

class RoutineDraftTest {
  @Test fun `empty new draft and incomplete choices cannot be persisted`() {
    assertNull(validatedRoutine("id", "", 0, -1, -1))
    assertNull(validatedRoutine("id", "  ", 1, 60, 120))
    assertNull(validatedRoutine("id", "Walk", 0, 60, 120))
    assertNull(validatedRoutine("id", "Walk", 1, -1, 120))
    assertNull(validatedRoutine("id", "Walk", 1, 60, 60))
    assertNull(validatedRoutine("id", "Walk", 1, 60, 1440))
  }
  @Test fun `editing preserves id unicode days and overnight endpoints`() {
    val rule = requireNotNull(validatedRoutine("stable", "  Caffè ☕  ", 65, 23 * 60, 30))
    assertEquals("stable", rule.id)
    assertEquals("Caffè ☕", rule.name)
    assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), rule.daysOfWeek)
    assertTrue(rule.crossesMidnight)
    assertEquals(23, rule.start.hour)
    assertEquals(30, rule.end.minute)
  }
}
