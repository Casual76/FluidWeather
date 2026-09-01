package dev.pampa.fluidweather.core.model

/**
 * Da dove arriva una lettura. Registrarlo per campione non e' pedanteria: il banco di prova deve
 * poter rigiocare la storia sapendo con quale cadenza e in quale contesto ogni punto e' nato.
 */
enum class SampleSource {
  /** Il giro periodico della modalita' di campionamento. */
  PERIODIC,

  /** La marcia di sorveglianza (foreground service), quando la tendenza ha superato la soglia. */
  SURVEILLANCE,

  /** La raffica manuale: 5 minuti a 1 Hz dal tasto nel nowcast (per ora dalla diagnostica). */
  MANUAL_BURST,

  /** Il monitoraggio continuo mentre l'app e' aperta (toggle). */
  CONTINUOUS,
}

/** Lo stato di attivita' al momento della lettura, per scartare ascensori/auto/aereo in pulizia. */
enum class ActivityKind {
  STILL,
  WALKING,
  RUNNING,
  ON_BICYCLE,
  IN_VEHICLE,
  UNKNOWN,
}

/**
 * Una lettura grezza del barometro con tutto il contesto che la pipeline di pulizia usera'.
 *
 * I campi facoltativi restano null quando il permesso manca o il fix non arriva in tempo: meglio
 * un campione onesto senza quota che nessun campione.
 */
data class PressureSample(
  val timestampMillis: Long,
  val pressureHpa: Double,
  val source: SampleSource,
  /** Le letture della stessa raffica condividono l'id, cosi' la mediana si ricostruisce offline. */
  val burstId: String? = null,
  val altitudeMeters: Double? = null,
  val latitude: Double? = null,
  val longitude: Double? = null,
  val activity: ActivityKind = ActivityKind.UNKNOWN,
  /** 0-100, dal riconoscimento attivita'; null quando l'attivita' e' UNKNOWN per assenza di dati. */
  val activityConfidence: Int? = null,
)
