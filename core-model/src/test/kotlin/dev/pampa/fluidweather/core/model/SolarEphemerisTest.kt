package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarEphemerisTest {

  /** 2024-03-20T12:00:00Z: equinozio, mezzogiorno UTC. */
  private val equinoxNoonUtc = 1_710_936_000_000L

  @Test
  fun `all'equinozio, a mezzogiorno solare, l'elevazione e' circa 90 meno la latitudine`() {
    // Greenwich (lat 51.5): attesi ~38.5 gradi.
    val elevation = SolarEphemeris.elevationDegrees(equinoxNoonUtc, 51.5, 0.0)
    assertEquals(90.0 - 51.5, elevation, 1.5)
  }

  @Test
  fun `a mezzanotte il sole sta sotto l'orizzonte`() {
    val midnight = equinoxNoonUtc + 12 * 3_600_000L
    assertTrue(SolarEphemeris.elevationDegrees(midnight, 43.83, 11.2) < -20.0)
  }

  @Test
  fun `le fasi del giorno si susseguono nell'ordine giusto`() {
    // Sesto Fiorentino, equinozio: alba ~05:05 UTC, tramonto ~17:15 UTC.
    val lat = 43.83
    val lon = 11.2
    assertEquals(DayPhase.NIGHT, SolarEphemeris.phaseAt(equinoxNoonUtc - 11 * 3_600_000L, lat, lon)) // 01:00Z
    assertEquals(DayPhase.DAWN, SolarEphemeris.phaseAt(equinoxNoonUtc - 7 * 3_600_000L, lat, lon)) // 05:00Z
    assertEquals(DayPhase.DAY, SolarEphemeris.phaseAt(equinoxNoonUtc, lat, lon))
    assertEquals(DayPhase.DUSK, SolarEphemeris.phaseAt(equinoxNoonUtc + 5 * 3_600_000L + 20 * 60_000L, lat, lon)) // 17:20Z
    assertEquals(DayPhase.NIGHT, SolarEphemeris.phaseAt(equinoxNoonUtc + 10 * 3_600_000L, lat, lon)) // 22:00Z
  }

  @Test
  fun `il solstizio invernale a Tromso e' notte anche a mezzogiorno`() {
    // 2023-12-21T12:00:00Z, Tromso (69.6N): notte polare.
    val winterNoon = 1_703_160_000_000L
    val phase = SolarEphemeris.phaseAt(winterNoon, 69.6, 18.9)
    assertTrue(phase == DayPhase.NIGHT || phase == DayPhase.DUSK || phase == DayPhase.DAWN)
    assertTrue(SolarEphemeris.elevationDegrees(winterNoon, 69.6, 18.9) < 0.0)
  }

  @Test
  fun `nell'emisfero sud le stagioni si invertono`() {
    // Solstizio di dicembre: Buenos Aires in piena estate, sole alto a mezzogiorno locale (15Z).
    val decemberNoonBuenosAires = 1_703_170_800_000L
    assertTrue(SolarEphemeris.elevationDegrees(decemberNoonBuenosAires, -34.6, -58.4) > 70.0)
  }
}
