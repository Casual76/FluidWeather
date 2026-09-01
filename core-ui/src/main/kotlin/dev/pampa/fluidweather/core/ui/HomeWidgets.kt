package dev.pampa.fluidweather.core.ui

/**
 * Il catalogo dei widget della home, con l'ordine di default deciso dal piano. Gli id sono
 * stringhe stabili: finiscono nella preferenza dell'ordine e devono sopravvivere ai refactor.
 */
enum class HomeWidget(
  val id: String,
  val title: String,
  /** 1 = mezza larghezza (compatto), 2 = tutta la riga (esteso). */
  val span: Int,
) {
  NOWCAST("nowcast", "Nowcast", 2),
  HOURLY("hourly", "Orario", 2),
  DAILY("daily", "Giornaliero", 2),
  PRECIPITATION("precipitation", "Precipitazioni", 2),
  PRESSURE("pressure", "Pressione", 1),
  AIR_QUALITY("air-quality", "Qualita' aria", 1),
  SUN("sun", "Sole", 2),
  MOON("moon", "Luna", 2),
  DETAILS("details", "Dettagli", 2);

  companion object {

    val defaultOrder: List<HomeWidget> = listOf(
      NOWCAST, HOURLY, DAILY, PRECIPITATION, PRESSURE, AIR_QUALITY, SUN, MOON, DETAILS,
    )

    /**
     * L'ordine salvato applicato al catalogo: gli id ignoti si scartano, i widget nuovi (non
     * ancora nella preferenza) si accodano al loro posto di default. Nessuna migrazione.
     */
    fun ordered(storedIds: List<String>): List<HomeWidget> {
      val byId = entries.associateBy { it.id }
      val stored = storedIds.mapNotNull { byId[it] }
      val missing = defaultOrder.filter { it !in stored }
      return stored + missing
    }
  }
}
