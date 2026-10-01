package dev.pampa.fluidweather.nowcast.learning

/**
 * Se e come gli analoghi storici spostano il verdetto.
 *
 * Gli analoghi fondono la probabilita' del modello con la frequenza delle situazioni "simili"
 * trovate nell'archivio, con peso N/(N+[priorStrength]). Ma la somiglianza e' misurata sulle
 * feature barometriche, che da sole non hanno abilita' predittiva sulla pioggia: i "vicini"
 * sono vicini a caso, e la loro frequenza e' in pratica il tasso base dell'archivio. Fondere
 * con quello trascina ogni verdetto verso la media, cioe' toglie risoluzione a un modello che
 * ne ha gia' poca. L'app quindi li tiene spenti ([OFF]); la classe resta per il banco di prova
 * ([LEGACY]) e per il giorno in cui le feature avranno qualcosa da dire.
 */
data class AnalogPolicy(
  val enabled: Boolean,
  val priorStrength: Double = Analogs.PRIOR_STRENGTH,
  val neighbours: Int = Analogs.DEFAULT_NEIGHBOURS,
) {
  companion object {
    /** Il comportamento di sempre, quello che usa il banco: acceso, peso N/(N+40). */
    val LEGACY = AnalogPolicy(true, 40.0)

    /** Quello che spedisce l'app: niente analoghi, nemmeno letti dal disco. */
    val OFF = AnalogPolicy(false, 200.0)
  }
}
