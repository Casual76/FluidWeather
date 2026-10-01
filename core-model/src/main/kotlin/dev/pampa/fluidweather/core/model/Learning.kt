package dev.pampa.fluidweather.core.model

/**
 * Un verdetto emesso e iscritto alla verifica, con le feature che l'hanno prodotto e le
 * probabilita' GREZZE del modello: e' la riga dell'archivio da cui il telefono impara (fase 16).
 * Grezze, non ricalibrate: una ricalibrazione stimata sui propri risultati insegue se stessa.
 *
 * [modelVersion] (`ModelVersions.TAG`), [tier] (`ContextTier.name`) e [roundId] dicono con quale
 * modello, in quale regime di contesto e in quale giro e' stato emesso: senza, le righe del
 * vecchio modello e quelle del nuovo finiscono nello stesso mucchio e la taratura impara da un
 * modello che non c'e' piu'. Le righe scritte prima portano [LEGACY_VERSION], nessun livello e
 * giro 0.
 */
data class NowcastIssueRecord(
  val issuedAtMillis: Long,
  val features: List<Double>,
  val rawProbability01: Double,
  val rawProbability13: Double,
  val rawProbability36: Double,
  val modelVersion: String = LEGACY_VERSION,
  val tier: String? = null,
  val roundId: Long = 0L,
) {

  /**
   * La chiave con cui si incrocia l'esito di questa riga: il giro, se c'e', altrimenti l'istante
   * d'emissione. Il verdetto "ombra" del solo barometro ([isShadow]) vive a [SHADOW_OFFSET_MILLIS]
   * dal giro perche' la chiave primaria e' l'istante, ma la verita' e' quella del giro: lo stesso
   * esito vale per entrambi, e senza questa chiave l'ombra resterebbe senza etichetta.
   */
  val outcomeKey: Long get() = if (roundId != 0L) roundId else issuedAtMillis

  /**
   * E' l'emissione ombra di un giro (il verdetto calcolato senza contesto)? Non e' una situazione
   * vissuta dall'utente ma un controfattuale: serve alla mappa "none" della ricalibrazione e
   * non agli analoghi, dove sarebbe un duplicato quasi identico dell'emissione vera.
   */
  val isShadow: Boolean get() = roundId != 0L && issuedAtMillis != roundId

  companion object {
    /** L'etichetta delle righe di prima delle versioni: e' il default della colonna in Room. */
    const val LEGACY_VERSION: String = "legacy"

    /** Di quanto l'emissione ombra segue il giro: un millisecondo, l'unica cosa che non collide con niente. */
    const val SHADOW_OFFSET_MILLIS: Long = 1L
  }
}

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

/**
 * La mappa di ricalibrazione di una variante di contesto ([variant]: "fresh", "stale", "none",
 * "enh") e di una finestra, come sta su disco, con la diagnosi di com'e' andata la stima.
 *
 * [a] e [b] possono mancare: una stima fallita (pochi casi, una sola classe) si conserva lo stesso,
 * con il suo [status], perche' la pagina deve poter dire "non ancora, e questo e' il motivo". [active]
 * e' l'unica cosa che il motore legge: una mappa c'e' e non si applica finche' il controllo sugli
 * ultimi dati ([guardDeltaBrier], [guardUpperBound] su [guardTestDays] giorni) non dice che migliora
 * il Brier fuori campione.
 */
data class PlattMapRecord(
  val variant: String,
  val window: String,
  val a: Double?,
  val b: Double?,
  val samples: Int,
  val wet: Int,
  val dry: Int,
  val status: String,
  val active: Boolean,
  val guardDeltaBrier: Double? = null,
  val guardUpperBound: Double? = null,
  val guardTestDays: Int = 0,
  /** Solo variante "none": la regola barometrica locale ha battuto il modello qui, e la finestra la usa. */
  val useRule: Boolean = false,
  val ruleDeltaBrier: Double? = null,
  val ruleUpperBound: Double? = null,
  val fittedAtMillis: Long,
)

/** Tutte le mappe salvate, la versione con cui sono state stimate e l'istante dell'ultima stima. */
data class PlattMaps(
  val version: String?,
  val lastFitMillis: Long,
  val records: List<PlattMapRecord>,
) {
  companion object {
    val EMPTY = PlattMaps(null, 0L, emptyList())
  }
}
