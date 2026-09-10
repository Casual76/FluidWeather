package dev.pampa.fluidweather.core.model

/**
 * La raffica di taratura del primo avvio: dieci minuti a 1 Hz, in background, senza tenere
 * l'utente inchiodato a una schermata (decisione 2026-09-02). Finche' non e' finita, cio' che
 * chiede il barometro si dichiara "non ancora disponibile".
 *
 * I dieci minuti sono il minimo, non la durata: contano i minuti **fermi**, e chi si muove ne
 * regala pochi. Vedi [MIN_STABLE_SECONDS] e [MAX_DURATION_SECONDS].
 */
object CalibrationBurst {
  const val DURATION_SECONDS: Int = 10 * 60

  /**
   * Quanto tempo utile (cioe' fermo, a quota stabile) serve perche' la stima valga qualcosa.
   *
   * Meno dei dieci minuti nominali di proposito: chi sta fermo li raggiunge subito e la taratura
   * finisce nei tempi di sempre; chi e' in giro invece continua a campionare finche' non li mette
   * insieme, invece di produrre una stima costruita su un tratto in autostrada.
   */
  const val MIN_STABLE_SECONDS: Int = 5 * 60

  /**
   * Il tetto oltre il quale si smette di aspettare e si salva il meglio che si ha, dichiarando
   * poca fiducia. Mezz'ora di foreground service e' gia' molto da chiedere a una batteria.
   */
  const val MAX_DURATION_SECONDS: Int = 30 * 60

  /**
   * Ogni quanto, dentro una raffica lunga, si rileggono posizione e attivita'.
   *
   * Fuori dalla taratura il contesto resta uno per raffica ed e' giusto cosi': venti secondi non
   * cambiano paese. Dieci minuti si', e un campione registrato alla quota di partenza mentre il
   * telefono e' duecento metri piu' in alto e' un dato **falso**, non impreciso.
   */
  const val CONTEXT_REFRESH_SECONDS: Int = 45
}

/**
 * Com'e' andata l'ultima raffica. E' un enum e non una frase perche' deve **sopravvivere al
 * riavvio**: prima l'esito viveva in un `MutableStateFlow` in memoria e chi riapriva l'app non
 * poteva piu' sapere perche' la taratura non c'era. Le parole le mette core-strings.
 */
enum class CalibrationOutcome {
  OK,
  NO_BAROMETER,
  START_FAILED,
  NO_READINGS,

  /** I provider non hanno dato un riferimento: la raffica resta in archivio e si ritenta. */
  NO_REFERENCE,
  TOO_FEW,

  /** Troppo movimento: nessun tratto fermo abbastanza lungo da valere una stima. */
  TOO_MUCH_MOVEMENT,
}

/**
 * Una raffica gia' in archivio che aspetta ancora la sua stima.
 *
 * Serve perche' la stima si possa ritentare **senza rifare i dieci minuti**: la stringa che
 * l'utente legge quando manca il riferimento promette da sempre "la raffica e' in archivio,
 * riprova la stima", e fino a ora quel ritentativo non esisteva.
 */
data class PendingCalibrationBurst(
  val burstId: String,
  val startedAtMillis: Long,
  val endedAtMillis: Long,
)

/**
 * L'esito di una taratura: il bias stimato e come ci si e' arrivati, tutto in chiaro. Un
 * numero senza la sua provenienza non si puo' discutere, e la taratura va discussa.
 */
data class CalibrationRecord(
  /** Da sottrarre alle letture: lettura_vera = lettura_sensore - bias. */
  val biasHpa: Double,
  /** 0..1: quanto fidarsi. Cresce con le verifiche (fase 16). */
  val confidence: Double,
  val calibratedAtMillis: Long,
  val sampleCount: Int,
  /** La lettura locale (mediana della raffica) ridotta al livello del mare. */
  val localMslHpa: Double,
  /** La pressione al mare dei provider nello stesso istante: il riferimento. */
  val referenceMslHpa: Double,
  /** La quota usata per la riduzione; null = ignota (e la fiducia scende). */
  val altitudeMeters: Double?,
  /**
   * Quanti tratti fermi indipendenti hanno votato questo bias.
   *
   * Uno solo e' la vecchia taratura: dieci minuti in salotto. Tre a tre quote diverse che dicono
   * lo stesso numero sono una prova molto piu' forte, e [spreadHpa] dice quanto sono d'accordo.
   */
  val segmentCount: Int = 1,
  /** La dispersione fra i residui dei segmenti, in hPa. 0 quando il segmento e' uno solo. */
  val spreadHpa: Double = 0.0,
) {
  fun toDeviceCalibration(): DeviceCalibration = DeviceCalibration(biasHpa = biasHpa, confidence = confidence)
}
