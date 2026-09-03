package dev.pampa.fluidweather.core.ui

import androidx.annotation.StringRes
import dev.pampa.fluidweather.strings.R

/**
 * Il catalogo dei widget della home, con l'ordine di default deciso dal piano. Gli id sono
 * stringhe stabili: finiscono nella preferenza dell'ordine e devono sopravvivere ai refactor.
 * Il titolo e' una risorsa (fase 17): la lingua la mette chi lo mostra.
 */
enum class HomeWidget(
  val id: String,
  @param:StringRes val titleRes: Int,
  /** 1 = mezza larghezza (compatto), 2 = tutta la riga (esteso). */
  val span: Int,
) {
  NOWCAST("nowcast", R.string.widget_nowcast, 2),
  HOURLY("hourly", R.string.widget_hourly, 2),
  DAILY("daily", R.string.widget_daily, 2),
  PRECIPITATION("precipitation", R.string.widget_precipitation, 2),
  PRESSURE("pressure", R.string.widget_pressure, 1),
  AIR_QUALITY("air-quality", R.string.widget_air, 1),
  SUN("sun", R.string.widget_sun, 2),
  MOON("moon", R.string.widget_moon, 2),
  DETAILS("details", R.string.widget_details, 2);

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

    /**
     * Gli id delle tessere da mostrare davvero, dato l'ordine salvato.
     *
     * Il nowcast viene dal barometro di QUESTO telefono: su una localita' lontana non ha niente da
     * dire, e una tessera che dicesse "in attesa di storia" parlerebbe di un altro posto. Sparisce
     * — ma **l'ordine salvato non si tocca**: chi torna sulla propria posizione la ritrova dov'era.
     */
    fun visibleIds(orderedIds: List<String>, barometerApplies: Boolean): List<String> =
      if (barometerApplies) orderedIds else orderedIds.filterNot { it == NOWCAST.id }
  }
}
