package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonAndSunTest {

  @Test
  fun `la luna piena del 25 dicembre 2023 e' piena anche per noi`() {
    // Plenilunio noto: 2023-12-27 circa. Il 27 dicembre l'illuminazione deve essere ~1.
    val fullMoon = 1_703_662_800_000L // 2023-12-27T09:00Z
    assertTrue(Moon.illuminatedFraction(fullMoon) > 0.97)
    assertEquals(MoonPhase.FULL, Moon.phase(fullMoon))
  }

  @Test
  fun `l'11 gennaio 2024 e' novilunio`() {
    val newMoon = 1_704_974_400_000L // 2024-01-11T12:00Z
    assertTrue(Moon.illuminatedFraction(newMoon) < 0.03)
    assertEquals(MoonPhase.NEW, Moon.phase(newMoon))
  }

  @Test
  fun `la prossima luna piena sta sempre entro un mese sinodico, davanti a noi`() {
    val now = 1_700_000_000_000L
    val next = Moon.nextFullMoonMillis(now)
    assertTrue(next > now)
    assertTrue(next - now < (Moon.SYNODIC_MONTH_DAYS * 86_400_000).toLong())
    assertTrue(Moon.illuminatedFraction(next) > 0.97)
  }

  @Test
  fun `alba e tramonto di un equinozio a Sesto Fiorentino`() {
    // 2024-03-20: alba ~05:20Z, tramonto ~17:30Z (lat 43.83, lon 11.2).
    val dayStart = 1_710_892_800_000L
    val times = SunTimes.forDay(dayStart, 43.83, 11.2)
    val sunriseHourUtc = (times.sunriseMillis!! - dayStart) / 3_600_000.0
    val sunsetHourUtc = (times.sunsetMillis!! - dayStart) / 3_600_000.0
    assertEquals(5.3, sunriseHourUtc, 0.4)
    assertEquals(17.5, sunsetHourUtc, 0.4)
    // All'equinozio il giorno dura ~12 ore, ovunque.
    assertEquals(12.0, sunsetHourUtc - sunriseHourUtc, 0.4)
  }

  @Test
  fun `nella notte polare non c'e' alba, e lo si dice con null`() {
    // Tromso, 21 dicembre.
    val dayStart = 1_703_116_800_000L
    val times = SunTimes.forDay(dayStart, 69.6, 18.9)
    assertNull(times.sunriseMillis)
    assertNull(times.sunsetMillis)
  }

  @Test
  fun `la percepita scende col vento e sale con l'afa`() {
    val calm = ApparentTemperature.celsius(30.0, 70.0, 0.0)
    val windy = ApparentTemperature.celsius(30.0, 70.0, 40.0)
    val dry = ApparentTemperature.celsius(30.0, 20.0, 0.0)

    assertTrue(windy < calm)
    assertTrue(calm > dry)
    // Con 30 gradi e afa al 70%, la percepita supera la reale.
    assertTrue(calm > 30.0)
  }
}
