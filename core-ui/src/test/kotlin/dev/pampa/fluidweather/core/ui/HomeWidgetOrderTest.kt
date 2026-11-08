package dev.pampa.fluidweather.core.ui

import org.junit.Assert.*
import org.junit.Test

class HomeWidgetOrderTest {
  @Test fun `unknown and duplicate ids normalize without disturbing legitimate order`() {
    val order = HomeWidget.ordered(listOf("moon", "unknown", "pressure", "moon", "nowcast", "pressure"))
    assertEquals(listOf(HomeWidget.MOON, HomeWidget.PRESSURE, HomeWidget.NOWCAST) +
      HomeWidget.defaultOrder.filterNot { it in setOf(HomeWidget.MOON, HomeWidget.PRESSURE, HomeWidget.NOWCAST) }, order)
    assertEquals(HomeWidget.entries.size, order.distinct().size)
    assertEquals(order, HomeWidget.ordered(order.map { it.id }))
  }

  @Test fun `temporarily hidden nowcast retains its full persisted position when visible again`() {
    val stored = listOf("moon", "nowcast", "hourly", "pressure")
    val fullOrder = HomeWidget.ordered(stored).map { it.id }
    val hidden = HomeWidget.visibleIds(fullOrder, false)
    assertFalse("nowcast" in hidden)
    assertEquals(fullOrder.filterNot { it == "nowcast" }, hidden)
    assertEquals(fullOrder, HomeWidget.visibleIds(HomeWidget.ordered(fullOrder).map { it.id }, true))
    assertEquals(1, fullOrder.indexOf("nowcast"))
  }

  @Test fun `new defaults append deterministically and existing full permutation is preserved`() {
    val older = listOf("details", "daily")
    assertEquals(listOf(HomeWidget.DETAILS, HomeWidget.DAILY) +
      HomeWidget.defaultOrder.filterNot { it.id in older }, HomeWidget.ordered(older))
    val reversed = HomeWidget.defaultOrder.reversed()
    assertEquals(reversed, HomeWidget.ordered(reversed.map { it.id }))
    assertEquals(HomeWidget.defaultOrder, HomeWidget.ordered(emptyList()))
  }
}
