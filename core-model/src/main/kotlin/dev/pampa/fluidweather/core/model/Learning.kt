package dev.pampa.fluidweather.core.model

/**
 * Un verdetto emesso e iscritto alla verifica, con le feature che l'hanno prodotto e le
 * probabilita' GREZZE del modello: e' la riga dell'archivio da cui il telefono impara (fase 16).
 * Grezze, non ricalibrate: una ricalibrazione stimata sui propri risultati insegue se stessa.
 */
data class NowcastIssueRecord(
  val issuedAtMillis: Long,
  val features: List<Double>,
  val rawProbability01: Double,
  val rawProbability13: Double,
  val rawProbability36: Double,
)

/** Com'e' finita una finestra di un verdetto iscritto: la verita' della verifica. */
data class NowcastOutcomeRecord(
  val issuedAtMillis: Long,
  /** L'etichetta della finestra del verdetto: "0-1h", "1-3h", "3-6h". */
  val window: String,
  val rained: Boolean,
)

/** La mappa di ricalibrazione di una finestra, come sta su disco. */
data class PlattParamsRecord(
  val window: String,
  val a: Double,
  val b: Double,
  val samples: Int,
  val fittedAtMillis: Long,
)
