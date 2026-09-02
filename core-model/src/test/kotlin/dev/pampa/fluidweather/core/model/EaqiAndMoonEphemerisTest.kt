package dev.pampa.fluidweather.core.model

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EaqiAndMoonEphemerisTest {

  // ------------------------------------------------------------------------------- EAQI

  @Test
  fun `il sotto-indice interpola fra le soglie della scala di ogni inquinante`() {
    assertEquals(20.0, Eaqi.subIndex("PM2.5", 10.0)!!, 1e-9)
    assertEquals(30.0, Eaqi.subIndex("PM2.5", 15.0)!!, 1e-9)
    assertEquals(80.0, Eaqi.subIndex("PM10", 100.0)!!, 1e-9)
    assertEquals(0.0, Eaqi.subIndex("NO2", 0.0)!!, 1e-9)
    assertTrue(Eaqi.subIndex("NO2", 5000.0)!! > 100.0)
    assertNull(Eaqi.subIndex("CO", 1.0))
    assertEquals(AqiBand.FAIR, Eaqi.bandOf("Ozono", 60.0))
    assertEquals(AqiBand.VERY_POOR, Eaqi.bandOf("PM2.5", 60.0))
  }

  // ---------------------------------------------------------------------- effemeridi lunari

  private val florenceLat = 43.83
  private val florenceLon = 11.2

  /** L'elongazione luna-sole in gradi [0, 360): 0 al novilunio, 180 al plenilunio. */
  private fun elongation(millis: Long): Double {
    val moon = MoonEphemeris.position(millis).eclipticLongitudeDeg
    val sun = SolarEphemeris.eclipticLongitudeDegrees(millis)
    return ((moon - sun) % 360 + 360) % 360
  }

  /** Il primo istante dopo [from] in cui l'elongazione attraversa [target], a passi di un'ora. */
  private fun nextElongation(from: Long, target: Double): Long {
    var t = from
    var previous = elongation(t)
    repeat(24 * 40) {
      t += 3_600_000L
      val current = elongation(t)
      val crossed = if (target == 0.0) current < previous else previous < target && current >= target
      if (crossed) return t
      previous = current
    }
    error("nessun attraversamento")
  }

  private fun dayStartOf(millis: Long): Long = millis - Math.floorMod(millis, 86_400_000L)

  @Test
  fun `al plenilunio la luna sorge quando il sole tramonta`() {
    val fullMoon = nextElongation(1_700_000_000_000L, 180.0)
    val dayStart = dayStartOf(fullMoon)
    val sunset = SunTimes.forDay(dayStart, florenceLat, florenceLon).sunsetMillis!!
    val moonrise = MoonEphemeris.riseSet(dayStart, florenceLat, florenceLon).riseMillis
    assertNotNull(moonrise)
    assertTrue("moonrise-sunset ${(moonrise!! - sunset) / 60_000} min", abs(moonrise - sunset) < 75 * 60_000L)
    // E la fase del modello medio e' d'accordo con l'effemeride vera, a meno di qualche ora.
    assertTrue(Moon.illuminatedFraction(fullMoon) > 0.97)
  }

  @Test
  fun `al novilunio la luna sorge col sole`() {
    val newMoon = nextElongation(1_700_000_000_000L, 0.0)
    val dayStart = dayStartOf(newMoon)
    val sunrise = SunTimes.forDay(dayStart, florenceLat, florenceLon).sunriseMillis!!
    val moonrise = MoonEphemeris.riseSet(dayStart, florenceLat, florenceLon).riseMillis
    assertNotNull(moonrise)
    assertTrue("moonrise-sunrise ${(moonrise!! - sunrise) / 60_000} min", abs(moonrise - sunrise) < 75 * 60_000L)
    assertTrue(Moon.illuminatedFraction(newMoon) < 0.03)
  }

  @Test
  fun `la distanza oscilla fra perigeo e apogeo, la declinazione resta entro i limiti`() {
    val from = 1_700_000_000_000L
    val samples = (0 until 24 * 60).map { hour -> MoonEphemeris.position(from + hour * 3_600_000L) }
    val distances = samples.map { it.distanceKm }
    assertTrue("min ${distances.min()}", distances.min() in 356_000.0..372_000.0)
    assertTrue("max ${distances.max()}", distances.max() in 398_000.0..407_000.0)
    assertTrue(samples.all { abs(it.declinationDeg) <= 29.0 })
    assertTrue(samples.all { abs(it.eclipticLatitudeDeg) <= 5.5 })
  }

  @Test
  fun `la luna guadagna circa cinquanta minuti al giorno sul sorgere`() {
    val dayStart = dayStartOf(1_700_000_000_000L)
    val first = MoonEphemeris.riseSet(dayStart, florenceLat, florenceLon).riseMillis!!
    val next = MoonEphemeris.riseSet(dayStart + 86_400_000L, florenceLat, florenceLon).riseMillis!!
    val gainMinutes = (next - first - 86_400_000L) / 60_000.0
    assertTrue("guadagno $gainMinutes min", gainMinutes in 20.0..80.0)
  }

  // ---------------------------------------------------------------------- crepuscoli e anno

  @Test
  fun `i crepuscoli si allungano in ordine e il giorno piu' lungo e' a giugno`() {
    // 2024-03-20 a Sesto Fiorentino.
    val dayStart = 1_710_892_800_000L
    val sun = SunTimes.forDay(dayStart, florenceLat, florenceLon)
    val civil = SunTimes.forDay(dayStart, florenceLat, florenceLon, Twilight.CIVIL)
    val nautical = SunTimes.forDay(dayStart, florenceLat, florenceLon, Twilight.NAUTICAL)
    val astronomical = SunTimes.forDay(dayStart, florenceLat, florenceLon, Twilight.ASTRONOMICAL)
    assertTrue(astronomical.sunriseMillis!! < nautical.sunriseMillis!!)
    assertTrue(nautical.sunriseMillis!! < civil.sunriseMillis!!)
    assertTrue(civil.sunriseMillis!! < sun.sunriseMillis!!)
    assertTrue(sun.sunsetMillis!! < civil.sunsetMillis!!)

    val (noonMillis, elevation) = SunTimes.solarNoon(dayStart, florenceLat, florenceLon)
    assertTrue(noonMillis in sun.sunriseMillis!!..sun.sunsetMillis!!)
    // All'equinozio l'altezza a mezzogiorno vale 90 - latitudine.
    assertEquals(90.0 - florenceLat, elevation, 1.0)

    val year = SunCalendar.year(2024, florenceLat, florenceLon)
    assertEquals(366, year.size)
    val longest = year.maxByOrNull { it.lengthMillis ?: 0L }!!
    val shortest = year.filter { it.lengthMillis != null }.minByOrNull { it.lengthMillis!! }!!
    assertEquals(6, longest.date.monthValue)
    assertEquals(12, shortest.date.monthValue)
    assertTrue(longest.lengthMillis!! > 15 * 3_600_000L)
    assertTrue(shortest.lengthMillis!! < 9 * 3_600_000L)
  }
}
