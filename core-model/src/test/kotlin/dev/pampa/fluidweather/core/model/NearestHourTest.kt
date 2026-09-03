package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "L'ora piu' vicina a adesso", che prima era scritta a mano in otto punti con guardie diverse.
 *
 * Il baco che questi test tengono chiuso: tre di quegli otto scartavano oltre 90 minuti e cinque
 * no, quindi la stessa schermata poteva mostrare "—" al posto della temperatura e "Sereno" appena
 * sotto, leggendo la stessa identica istantanea.
 */
class NearestHourTest {

  private val adesso = 1_781_517_600_000L
  private val ora = 3_600_000L

  private fun ore(vararg offsets: Long) = offsets.map {
    FusedHour(timestampMillis = adesso + it, values = emptyMap(), kind = null)
  }

  @Test
  fun `senza ore non c'e' niente da trovare`() {
    assertNull(emptyList<FusedHour>().nearestHour(adesso))
    assertNull(emptyList<FusedHour>().hourAround(adesso))
  }

  @Test
  fun `l'ora esatta dista zero`() {
    val (hour, distance) = ore(-ora, 0L, ora).nearestHour(adesso)!!

    assertEquals(adesso, hour.timestampMillis)
    assertEquals(0L, distance)
  }

  @Test
  fun `si prende la piu' vicina, anche se e' passata`() {
    val (hour, distance) = ore(-20 * 60_000L, 40 * 60_000L).nearestHour(adesso)!!

    assertEquals(adesso - 20 * 60_000L, hour.timestampMillis)
    assertEquals(20 * 60_000L, distance)
  }

  @Test
  fun `la distanza torna col valore, cosi' chi guarda decide`() {
    // E' il punto della firma: `nearestHour` non decide se e' attuale, lo dice e basta.
    val (_, distance) = ore(-3 * ora).nearestHour(adesso)!!

    assertEquals(3 * ora, distance)
  }

  @Test
  fun `il tetto taglia, e taglia proprio dove dice`() {
    assertNotNull(ore(-NOW_WINDOW_MILLIS).hourAround(adesso))
    assertNull(ore(-NOW_WINDOW_MILLIS - 1).hourAround(adesso))
  }

  @Test
  fun `senza tetto l'ora di ieri si trova lo stesso`() {
    // Che e' esattamente cio' che serve alla testata: mostrarla, e dire che e' di ieri.
    val (hour, distance) = ore(-26 * ora).nearestHour(adesso)!!

    assertEquals(adesso - 26 * ora, hour.timestampMillis)
    assertEquals(26 * ora, distance)
    assertNull("ma chi decide una notifica non deve vederla", ore(-26 * ora).hourAround(adesso))
  }

  @Test
  fun `la previsione fusa e la lista si comportano uguale`() {
    val hours = ore(-ora, 10 * 60_000L)
    val forecast = FusedForecast(hours, emptyMap())

    assertEquals(hours.nearestHour(adesso)?.first, forecast.nearestHour(adesso)?.first)
    assertEquals(hours.hourAround(adesso), forecast.hourAround(adesso))
  }
}
