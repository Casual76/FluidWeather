package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.MinutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sta piovendo?" e' una domanda a cui una riga oraria, per come e' fatta, non sa rispondere: la
 * sua precipitazione copre un'ora intera, e finche' l'ora non e' finita non c'e'. Il quarto d'ora
 * risponde. E' l'unico motivo per cui e' stato aggiunto alla richiesta.
 */
class MinutelyRainTest {

  private val now = 1_781_517_600_000L // un'ora tonda
  private val quarter = 15 * 60_000L

  private fun bundle(vararg minutes: Pair<Long, Double>) = ForecastBundle(
    providerId = "open-meteo",
    fetchedAtMillis = now,
    latitude = 43.83,
    longitude = 11.20,
    hourly = listOf(HourlyPoint(now, precipitationMm = 0.0, pressureMslHpa = 1013.0)),
    minutely = minutes.map { (offset, mm) -> MinutePoint(now + offset, precipitationMm = mm) },
  )

  @Test
  fun `si prende l'ultimo quarto d'ora gia' chiuso, non quello che deve ancora succedere`() {
    val b = bundle(-quarter to 0.9, quarter to 5.0)

    assertEquals(0.9, b.rainNowMm(now)!!, 1e-9)
  }

  @Test
  fun `un quarto d'ora vecchio non e' adesso`() {
    assertNull(bundle(-4 * quarter to 2.0).rainNowMm(now))
  }

  @Test
  fun `senza blocco a quindici minuti non si inventa niente`() {
    assertNull(bundle().rainNowMm(now))
  }

  @Test
  fun `la pioggia in arrivo si somma solo sul futuro`() {
    val b = bundle(-quarter to 3.0, quarter to 0.4, 2 * quarter to 0.6)

    assertEquals(1.0, b.rainSoonMm(now, 3_600_000L)!!, 1e-9)
  }

  @Test
  fun `mezzo millimetro in un quarto d'ora sono due all'ora, ed e' pioggia`() {
    val observation = RainObservations.fromMinutely(bundle(-quarter to 0.5), now)!!

    assertTrue(observation.rainingNow)
    assertEquals(2.0, observation.intensityMmPerHour!!, 1e-9)
    assertEquals(0.65, observation.floorFor("0-1h"), 1e-9)
  }

  @Test
  fun `una traccia non e' pioggia`() {
    val observation = RainObservations.fromMinutely(bundle(-quarter to 0.02), now)!!

    assertFalse(observation.rainingNow)
    assertEquals(0.0, observation.floorFor("0-1h"), 1e-9)
  }

  @Test
  fun `fra due fonti vince quella che la pioggia la vede`() {
    val minutely = RainObservations.fromMinutely(bundle(-quarter to 0.0), now)
    val radar = dev.pampa.fluidweather.nowcast.learning.RainObservation.raining(4.0, "radar")

    val merged = RainObservations.merge(minutely, radar)!!

    assertTrue(merged.rainingNow)
    assertEquals(4.0, merged.intensityMmPerHour!!, 1e-9)
  }

  @Test
  fun `l'ora dai quarti d'ora e' un accumulo, non un tasso`() {
    val b = bundle(-4 * quarter to 9.9, -3 * quarter to 0.1, -2 * quarter to 0.2, -quarter to 0.3, 0L to 0.4)

    // I quattro quarti dentro l'ora appena passata, e non uno di piu': il -4 e' fuori.
    assertEquals(1.0, b.rainLastHourMm(now)!!, 1e-9)
  }

  @Test
  fun `un'ora scoperta non si somma a meta'`() {
    assertNull(bundle(-quarter to 0.5).rainLastHourMm(now))
  }

  @Test
  fun `il contesto del nowcast prende la pioggia dai quarti d'ora quando coprono l'ora`() {
    val b = bundle(-3 * quarter to 0.1, -2 * quarter to 0.2, -quarter to 0.3, 0L to 0.4)
    val context = b.toContext(now)!!

    assertEquals(1.0, context.rainLastHourMm!!, 1e-9)
    assertEquals(1013.0, context.pressureMslHpa!!, 1e-9)
  }

  @Test
  fun `senza quarti d'ora sufficienti si torna alla riga oraria`() {
    val context = bundle(-quarter to 0.5).toContext(now)!!

    assertEquals(0.0, context.rainLastHourMm!!, 1e-9)
  }
}
