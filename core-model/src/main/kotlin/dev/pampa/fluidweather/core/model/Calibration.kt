package dev.pampa.fluidweather.core.model

/**
 * La raffica di taratura del primo avvio: dieci minuti a 1 Hz, in background, senza tenere
 * l'utente inchiodato a una schermata (decisione 2026-09-02). Finche' non e' finita, cio' che
 * chiede il barometro si dichiara "non ancora disponibile".
 */
object CalibrationBurst {
  const val DURATION_SECONDS: Int = 10 * 60
}

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
) {
  fun toDeviceCalibration(): DeviceCalibration = DeviceCalibration(biasHpa = biasHpa, confidence = confidence)
}
