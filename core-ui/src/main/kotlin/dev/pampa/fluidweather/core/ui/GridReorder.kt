package dev.pampa.fluidweather.core.ui

/**
 * La matematica del riordino, separata dai gesti: dato dove sta il centro della tessera
 * trascinata, decide sopra chi sta passando e come cambia l'ordine. Pura, collaudata, e la
 * parte coi pointer event resta un guscio sottile.
 */
object GridReorder {

  data class CellBounds(
    val key: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
  ) {
    fun contains(x: Float, y: Float): Boolean =
      x >= left && x < left + width && y >= top && y < top + height
  }

  /** La tessera (diversa da quella trascinata) sotto il centro del trascinamento, se c'e'. */
  fun targetKey(
    cells: List<CellBounds>,
    draggedKey: String,
    centerX: Float,
    centerY: Float,
  ): String? = cells.firstOrNull { it.key != draggedKey && it.contains(centerX, centerY) }?.key

  /** Il nuovo ordine: la trascinata prende il posto della bersaglio, le altre scalano. */
  fun moved(order: List<String>, draggedKey: String, targetKey: String): List<String> {
    val from = order.indexOf(draggedKey)
    val to = order.indexOf(targetKey)
    if (from < 0 || to < 0 || from == to) return order
    val mutable = order.toMutableList()
    mutable.removeAt(from)
    mutable.add(to, draggedKey)
    return mutable
  }
}
