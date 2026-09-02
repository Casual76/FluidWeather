package dev.pampa.fluidweather.core.ui

/**
 * La matematica del collasso della testata: quanto e' scorsa, dove deve fermarsi.
 *
 * La testata ha due case — grande in cima alla pagina, compatta sopra la griglia — e nessun
 * indirizzo in mezzo: uno scorrimento lasciato a meta' la congela sbiadita a una taglia
 * qualsiasi, che e' lo stato piu' visibilmente incompiuto che una schermata possa avere (visto
 * sul telefono, 2026-09-02). Lo snap si applica DENTRO il fling, come fa l'engine per i titoli.
 *
 * Pura: si verifica sul computer con tre numeri.
 */
object HeaderCollapse {

  /** Finche' la testata non e' stata misurata: circa la sua altezza a densita' media. */
  const val DEFAULT_TRAVEL_PX: Float = 420f

  /** Oltre questa frazione del viaggio la testata compatta e' quella che si vedra' a riposo. */
  const val COMPACT_THRESHOLD: Float = 0.5f

  /** 0 = testata grande a riposo, 1 = del tutto scorsa via. */
  fun progress(scrolledPx: Float, travelPx: Float): Float {
    if (!travelPx.isFinite() || travelPx <= 1f) return if (scrolledPx > 0f) 1f else 0f
    return (scrolledPx / travelPx).coerceIn(0f, 1f)
  }

  /** Dove deve atterrare lo scorrimento: la casa piu' vicina fra le due. */
  fun snapTarget(scrolledPx: Float, travelPx: Float): Float =
    if (scrolledPx >= travelPx * COMPACT_THRESHOLD) travelPx else 0f

  /**
   * Quanto manca alla casa piu' vicina, in px firmati; 0 quando non c'e' niente da correggere
   * (la testata e' gia' a riposo, o la pagina e' scorsa oltre la testata).
   */
  fun snapDelta(firstVisibleIndex: Int, scrolledPx: Float, travelPx: Float): Float {
    if (!travelPx.isFinite() || travelPx <= 1f) return 0f
    if (firstVisibleIndex != 0) return 0f
    if (scrolledPx <= 0f || scrolledPx >= travelPx) return 0f
    return snapTarget(scrolledPx, travelPx) - scrolledPx
  }

  /** Se e' la versione compatta a dover essere in scena, dato l'avanzamento. */
  fun compactVisible(progress: Float): Boolean = progress >= COMPACT_THRESHOLD
}
