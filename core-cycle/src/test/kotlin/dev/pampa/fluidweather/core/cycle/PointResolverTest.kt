package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dove si fa il giro in background: il fix di adesso, l'ultimo fix se il telefono non si e' mosso,
 * o la prima localita' salvata (che pero' non e' il telefono, e non conta per il barometro).
 */
class PointResolverTest {

  private val now = 1_788_220_800_000L
  private val minute = 60_000L
  private val hour = 60 * minute

  private val home = FixSample(43.832, 11.199, now)
  private val saved = Place(7, "Milano", "Lombardia", 45.46, 9.19)

  private fun fix(ageMillis: Long) = FixSample(43.832, 11.199, now - ageMillis)

  private fun resolve(
    permitted: Boolean = true,
    current: FixSample? = null,
    last: FixSample? = null,
    inTransitAt: Long? = null,
    place: Place? = saved,
  ) = PointResolver.resolve(now, permitted, current, last, inTransitAt, place)

  @Test
  fun `un fix di adesso e' il telefono`() {
    val point = resolve(current = fix(2 * minute), last = fix(2 * hour))!!

    assertEquals(PointSource.FRESH_FIX, point.source)
    assertEquals(WeatherSnapshot.GPS_KEY, point.placeKey)
    assertEquals(home.latitude, point.latitude, 0.0)
  }

  @Test
  fun `senza fix nuovo, l'ultimo di due ore fa vale se non ci si e' mossi`() {
    val point = resolve(current = null, last = FixSample(43.9, 11.3, now - 2 * hour))!!

    assertEquals(PointSource.LAST_FIX, point.source)
    assertEquals(WeatherSnapshot.GPS_KEY, point.placeKey)
    assertEquals(43.9, point.latitude, 0.0)
  }

  @Test
  fun `un viaggio in auto dopo l'ultimo fix lo rende inutile`() {
    val point = resolve(last = fix(2 * hour), inTransitAt = now - hour)!!

    assertEquals(PointSource.SAVED_PLACE, point.source)
    assertEquals(WeatherSnapshot.keyFor(7), point.placeKey)
    assertEquals(45.46, point.latitude, 0.0)
  }

  @Test
  fun `un fix di piu' di tre ore non e' piu' qui`() {
    val point = resolve(last = fix(3 * hour + minute))!!

    assertEquals(PointSource.SAVED_PLACE, point.source)
  }

  @Test
  fun `un viaggio prima dell'ultimo fix non conta`() {
    val point = resolve(last = fix(hour), inTransitAt = now - 2 * hour)!!

    assertEquals(PointSource.LAST_FIX, point.source)
  }

  @Test
  fun `senza permesso non si usa nessun fix`() {
    val point = resolve(permitted = false, current = fix(minute), last = fix(minute))!!

    assertEquals(PointSource.SAVED_PLACE, point.source)
  }

  @Test
  fun `niente fix e niente localita' salvate - niente giro`() {
    assertNull(resolve(place = null))
    assertNull(resolve(permitted = false, current = fix(minute), place = null))
    // La voce "posizione attuale" dell'elenco non e' una localita' salvata.
    assertNull(resolve(place = Place.gps()))
  }

  @Test
  fun `un fix di mezz'ora fa non e' fresco, ma vale come ultimo fix`() {
    val point = resolve(current = FixSample(43.84, 11.2, now - 30 * minute), last = fix(2 * hour))!!

    assertEquals(PointSource.LAST_FIX, point.source)
    // Il piu' recente dei due.
    assertEquals(43.84, point.latitude, 0.0)
  }

  @Test
  fun `un fix dal futuro oltre la tolleranza e' un orologio rotto`() {
    val future = FixSample(44.0, 11.0, now + PointResolver.CLOCK_SKEW_MILLIS + minute)

    assertEquals(PointSource.SAVED_PLACE, resolve(current = future)!!.source)
    // E non nasconde un ultimo fix buono.
    val point = resolve(current = future, last = fix(hour))!!
    assertEquals(PointSource.LAST_FIX, point.source)
    assertEquals(43.832, point.latitude, 0.0)
    // Dentro la tolleranza invece e' un fix di adesso.
    assertEquals(PointSource.FRESH_FIX, resolve(current = FixSample(44.0, 11.0, now + 2 * minute))!!.source)
  }
}
