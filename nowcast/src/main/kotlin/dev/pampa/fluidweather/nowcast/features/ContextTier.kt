package dev.pampa.fluidweather.nowcast.features

/**
 * Quanto contesto ha il verdetto: il livello decide quale modello parla e su quale mappa di
 * ricalibrazione.
 *
 * Il nucleo indipendente lavora in regimi davvero diversi: col contesto dei provider appena
 * scaricato, con un contesto di ore fa, col solo barometro — e, al primo avvio senza rete, senza
 * nemmeno sapere quanto piove di solito nel posto. Il modello v1 ne vedeva uno solo in
 * addestramento (contesto sempre fresco) e gestiva gli altri imputando i NaN alla media: un regime
 * mai visto spacciato per neutro. Con un livello esplicito ogni regime ha il suo modello, la sua
 * taratura e la sua riga di verifica, e la garanzia D1 (batte la migliore baseline) si misura
 * livello per livello invece che in media, dove un livello forte nasconderebbe uno debole.
 *
 * - [FRESH]: contesto di al piu' [FRESH_MAX_AGE_MILLIS] e dello stesso posto — la riga oraria del
 *   provider e' vecchia fino a novanta minuti per costruzione, oltre e' un'altra ora.
 * - [STALE]: contesto di al piu' [STALE_MAX_AGE_MILLIS], stesso posto. Dodici ore sono mezzo ciclo
 *   diurno: oltre, umidita' e nuvole di allora descrivono un altro tempo, e trattarle da presente
 *   farebbe piu' danno che ignorarle.
 * - [NONE]: niente contesto utilizzabile, ma la climatologia locale e' nota.
 * - [NONE_NOCLIMA]: niente contesto e niente climatologia — il telefono offline al primo avvio.
 *
 * "Stesso posto" lo decide chi chiama (entro [SAME_PLACE_RADIUS_METERS]): un contesto fresco di
 * un altro posto non e' un contesto, e' un altro tempo.
 */
enum class ContextTier {
  FRESH,
  STALE,
  NONE,
  NONE_NOCLIMA,
  ;

  /** Il contesto dei provider entra nel verdetto? */
  val hasContext: Boolean get() = this == FRESH || this == STALE

  companion object {
    const val FRESH_MAX_AGE_MILLIS: Long = 90 * 60_000L
    const val STALE_MAX_AGE_MILLIS: Long = 12 * 3_600_000L

    /** Entro quanto il contesto e' "dello stesso posto". */
    const val SAME_PLACE_RADIUS_METERS: Double = 3_000.0

    /**
     * Quanto un contesto puo' sembrare dal futuro ed essere ancora buono: l'orologio del telefono
     * si corregge a salti (rete, NTP), e un contesto scaricato un attimo prima di un salto
     * all'indietro avrebbe eta' negativa. Oltre, e' un orologio di cui non fidarsi.
     */
    const val CLOCK_SKEW_TOLERANCE_MILLIS: Long = 5 * 60_000L

    /**
     * Il livello di un verdetto. [contextAgeMillis] e' adesso meno l'istante del contesto (null se
     * non c'e'); [samePlace] se il contesto e' del posto del verdetto; [hasClimatology] se la
     * climatologia locale e' nota.
     */
    fun of(contextAgeMillis: Long?, samePlace: Boolean, hasClimatology: Boolean): ContextTier {
      val usableAge = contextAgeMillis
        ?.takeIf { samePlace && it >= -CLOCK_SKEW_TOLERANCE_MILLIS }
        ?.coerceAtLeast(0L)
      return when {
        usableAge != null && usableAge <= FRESH_MAX_AGE_MILLIS -> FRESH
        usableAge != null && usableAge <= STALE_MAX_AGE_MILLIS -> STALE
        hasClimatology -> NONE
        else -> NONE_NOCLIMA
      }
    }
  }
}
