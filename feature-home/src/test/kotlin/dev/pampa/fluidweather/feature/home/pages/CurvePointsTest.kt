package dev.pampa.fluidweather.feature.home.pages

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Curva ed etichette devono raccontare le stesse ore.
 *
 * Il baco che questi test tengono chiuso: i valori venivano filtrati (`mapNotNull`) e le etichette
 * prese da TUTTE le ore, quindi con un'ora mancante ai provider la prima, la mediana e l'ultima
 * etichetta finivano su ore che la curva non contiene — e chi guardava leggeva il punto sbagliato.
 */
class CurvePointsTest {

  private val hourMillis = 3_600_000L

  private fun ore(vararg valori: Double?): List<FusedHour> = valori.mapIndexed { index, valore ->
    FusedHour(
      timestampMillis = index * hourMillis,
      values = valore?.let { mapOf(VARIABILE to FusedValue(it, emptyList())) } ?: emptyMap(),
      kind = null,
    )
  }

  @Test
  fun `con tutte le ore i punti sono tutti`() {
    val (valori, etichette) = curvePoints(ore(1.0, 2.0, 3.0, 4.0), VARIABILE)

    assertEquals(listOf(1.0, 2.0, 3.0, 4.0), valori)
    assertEquals(3, etichette.size)
  }

  @Test
  fun `un'ora senza la variabile non sposta le etichette`() {
    // La variabile manca nell'ultima ora: l'ultima etichetta deve essere quella della penultima,
    // non quella di un'ora che nella curva non c'e'.
    val piene = curvePoints(ore(1.0, 2.0, 3.0), VARIABILE)
    val bucata = curvePoints(ore(1.0, 2.0, 3.0, null), VARIABILE)

    assertEquals(piene.first, bucata.first)
    assertEquals(piene.second, bucata.second)
  }

  @Test
  fun `il buco in mezzo cambia la mediana insieme ai valori`() {
    // Cinque ore, la terza vuota: restano quattro valori e la mediana e' la terza ORA TENUTA
    // (indice 1 su 4 -> 4/2 = 2, cioe' la quarta ora reale), non la terza ora del calendario.
    val (valori, etichette) = curvePoints(ore(10.0, 11.0, null, 13.0, 14.0), VARIABILE)

    assertEquals(listOf(10.0, 11.0, 13.0, 14.0), valori)
    assertEquals(3, etichette.size)
    // La mediana e' l'etichetta dell'ora del valore 13.0, cioe' l'indice 2 dei tenuti.
    val soloTenuti = curvePoints(
      listOf(
        FusedHour(0 * hourMillis, mapOf(VARIABILE to FusedValue(10.0, emptyList())), null),
        FusedHour(1 * hourMillis, mapOf(VARIABILE to FusedValue(11.0, emptyList())), null),
        FusedHour(3 * hourMillis, mapOf(VARIABILE to FusedValue(13.0, emptyList())), null),
        FusedHour(4 * hourMillis, mapOf(VARIABILE to FusedValue(14.0, emptyList())), null),
      ),
      VARIABILE,
    )
    assertEquals(soloTenuti.second, etichette)
  }

  @Test
  fun `niente variabile, niente curva`() {
    val (valori, etichette) = curvePoints(ore(null, null), VARIABILE)

    assertTrue(valori.isEmpty())
    assertTrue(etichette.isEmpty())
  }

  @Test
  fun `una sola ora non ha una mediana da sbagliare`() {
    val (valori, etichette) = curvePoints(ore(7.0), VARIABILE)

    assertEquals(listOf(7.0), valori)
    assertEquals(1, etichette.size)
  }

  private companion object {
    const val VARIABILE = "temperature_2m"
  }
}
