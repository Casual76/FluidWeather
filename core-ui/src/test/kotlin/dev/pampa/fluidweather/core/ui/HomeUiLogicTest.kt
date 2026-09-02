package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.graphics.luminance
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiLogicTest {

  // ------------------------------------------------------------------------------ SkyPalette

  @Test
  fun `il giorno sereno e' azzurro, la notte e' buia`() {
    val day = SkyPalette.sky(DayPhase.DAY, WeatherKind.CLEAR, 5.0)
    val night = SkyPalette.sky(DayPhase.NIGHT, WeatherKind.CLEAR, 5.0)

    assertTrue(day.gradient[0].blue > day.gradient[0].red)
    assertTrue(night.gradient[0].luminance() < 0.05f)
    assertTrue(day.gradient[0].luminance() > night.gradient[0].luminance())
  }

  @Test
  fun `la pioggia incupisce, la neve sbianca`() {
    val clear = SkyPalette.sky(DayPhase.DAY, WeatherKind.CLEAR, 10.0)
    val rain = SkyPalette.sky(DayPhase.DAY, WeatherKind.HEAVY_RAIN, 95.0)
    val snow = SkyPalette.sky(DayPhase.DAY, WeatherKind.SNOW, 95.0)

    assertTrue(rain.gradient[1].luminance() < clear.gradient[1].luminance())
    assertTrue(snow.gradient[1].luminance() > rain.gradient[1].luminance())
    assertTrue(rain.gloom > 0.7f)
  }

  @Test
  fun `le stelle escono solo di notte e con cielo pulito`() {
    assertTrue(SkyPalette.sky(DayPhase.NIGHT, WeatherKind.CLEAR, 5.0).starAlpha > 0.3f)
    assertEquals(0f, SkyPalette.sky(DayPhase.DAY, WeatherKind.CLEAR, 5.0).starAlpha, 1e-6f)
    assertEquals(0f, SkyPalette.sky(DayPhase.NIGHT, WeatherKind.CLOUDY, 95.0).starAlpha, 1e-6f)
  }

  // ------------------------------------------------------------------------------ HomeWidget

  @Test
  fun `senza preferenza l'ordine e' quello del piano`() {
    val order = HomeWidget.ordered(emptyList())
    assertEquals(HomeWidget.defaultOrder, order)
    assertEquals(HomeWidget.NOWCAST, order.first())
  }

  @Test
  fun `l'ordine salvato comanda, gli ignoti si scartano, i nuovi si accodano`() {
    val order = HomeWidget.ordered(listOf("moon", "widget-che-non-esiste", "nowcast"))
    assertEquals(HomeWidget.MOON, order[0])
    assertEquals(HomeWidget.NOWCAST, order[1])
    assertEquals(HomeWidget.entries.size, order.size)
    assertTrue(HomeWidget.DETAILS in order)
  }

  // ------------------------------------------------------------------------------ GridReorder

  private val cells = listOf(
    GridReorder.CellBounds("a", 0f, 0f, 100f, 100f),
    GridReorder.CellBounds("b", 100f, 0f, 100f, 100f),
    GridReorder.CellBounds("c", 0f, 100f, 200f, 100f),
  )

  @Test
  fun `il bersaglio e' la tessera sotto il centro, mai se stessi`() {
    assertEquals("b", GridReorder.targetKey(cells, "a", 150f, 50f))
    assertEquals("c", GridReorder.targetKey(cells, "a", 50f, 150f))
    assertEquals(null, GridReorder.targetKey(cells, "a", 50f, 50f)) // sopra se stessa
    assertEquals(null, GridReorder.targetKey(cells, "a", 500f, 500f)) // nel vuoto
  }

  @Test
  fun `lo spostamento scala gli altri senza perdere nessuno`() {
    val order = listOf("a", "b", "c", "d")
    assertEquals(listOf("b", "c", "a", "d"), GridReorder.moved(order, "a", "c"))
    assertEquals(listOf("d", "a", "b", "c"), GridReorder.moved(order, "d", "a"))
    assertEquals(order, GridReorder.moved(order, "a", "a"))
    assertEquals(order, GridReorder.moved(order, "x", "b"))
  }

  // --------------------------------------------------------------------------- HeaderCollapse

  @Test
  fun `la testata ha due case e lo snap va alla piu' vicina`() {
    assertEquals(0f, HeaderCollapse.snapTarget(scrolledPx = 100f, travelPx = 400f), 1e-6f)
    assertEquals(400f, HeaderCollapse.snapTarget(scrolledPx = 200f, travelPx = 400f), 1e-6f)
    assertEquals(400f, HeaderCollapse.snapTarget(scrolledPx = 390f, travelPx = 400f), 1e-6f)
  }

  @Test
  fun `il delta dello snap e' zero quando non c'e' niente da correggere`() {
    // A riposo in cima, o gia' oltre la testata: nessuna correzione.
    assertEquals(0f, HeaderCollapse.snapDelta(0, 0f, 400f), 1e-6f)
    assertEquals(0f, HeaderCollapse.snapDelta(0, 400f, 400f), 1e-6f)
    assertEquals(0f, HeaderCollapse.snapDelta(1, 80f, 400f), 1e-6f)
    // A meta' strada, la correzione e' firmata verso la casa piu' vicina.
    assertEquals(-120f, HeaderCollapse.snapDelta(0, 120f, 400f), 1e-6f)
    assertEquals(150f, HeaderCollapse.snapDelta(0, 250f, 400f), 1e-6f)
  }

  @Test
  fun `l'avanzamento e' limitato e la compatta compare a meta' viaggio`() {
    assertEquals(0f, HeaderCollapse.progress(-20f, 400f), 1e-6f)
    assertEquals(0.5f, HeaderCollapse.progress(200f, 400f), 1e-6f)
    assertEquals(1f, HeaderCollapse.progress(Float.POSITIVE_INFINITY, 400f), 1e-6f)
    assertTrue(HeaderCollapse.compactVisible(HeaderCollapse.progress(200f, 400f)))
    assertTrue(!HeaderCollapse.compactVisible(HeaderCollapse.progress(199f, 400f)))
    // Una testata non ancora misurata (viaggio nullo) non lascia mai la compatta a meta'.
    assertEquals(1f, HeaderCollapse.progress(1f, 0f), 1e-6f)
  }
}
