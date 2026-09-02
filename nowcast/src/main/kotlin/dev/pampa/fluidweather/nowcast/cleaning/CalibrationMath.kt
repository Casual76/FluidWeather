package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.CalibrationBurst
import dev.pampa.fluidweather.core.model.CalibrationRecord

/**
 * La stima del bias del dispositivo (Mass & Madaus; JTECH 2018): la mediana della raffica di
 * taratura, ridotta al livello del mare con la quota e la temperatura vere, confrontata con la
 * pressione al mare dei provider nello stesso istante. La differenza e' il bias.
 *
 * La fiducia dichiara i limiti: senza quota GPS la riduzione e' cieca (10 m valgono ~1,2 hPa),
 * con poche letture la mediana e' fragile. Cresce con le verifiche successive (fase 16).
 */
object CalibrationMath {

  fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
  }

  fun estimate(
    stationPressures: List<Double>,
    altitudeMeters: Double?,
    temperatureCelsius: Double?,
    referenceMslHpa: Double,
    nowMillis: Long,
  ): CalibrationRecord? {
    val stationMedian = median(stationPressures) ?: return null
    val localMsl = SeaLevel.reduce(
      stationPressureHpa = stationMedian,
      altitudeMeters = altitudeMeters ?: 0.0,
      temperatureCelsius = temperatureCelsius ?: SeaLevel.STANDARD_TEMPERATURE_CELSIUS,
    )
    val completeness = (stationPressures.size.toDouble() / CalibrationBurst.DURATION_SECONDS).coerceIn(0.1, 1.0)
    val base = if (altitudeMeters != null) CONFIDENCE_WITH_ALTITUDE else CONFIDENCE_WITHOUT_ALTITUDE
    return CalibrationRecord(
      biasHpa = localMsl - referenceMslHpa,
      confidence = base * completeness,
      calibratedAtMillis = nowMillis,
      sampleCount = stationPressures.size,
      localMslHpa = localMsl,
      referenceMslHpa = referenceMslHpa,
      altitudeMeters = altitudeMeters,
    )
  }

  /**
   * Un confronto in piu' fra la lettura locale (gia' corretta col bias corrente e ridotta al
   * mare) e il riferimento dei provider: il residuo e' l'errore che resta, e il bias lo insegue
   * piano (media mobile esponenziale); la fiducia sale di un passo a confronto, fino a un tetto.
   * E' "la fiducia che cresce con le verifiche" promessa dal piano.
   */
  fun refine(record: CalibrationRecord, localMslHpa: Double, referenceMslHpa: Double, nowMillis: Long): CalibrationRecord {
    val residual = localMslHpa - referenceMslHpa
    return record.copy(
      biasHpa = record.biasHpa + REFINE_RATE * residual,
      confidence = (record.confidence + CONFIDENCE_STEP).coerceAtMost(MAX_CONFIDENCE),
      calibratedAtMillis = nowMillis,
      localMslHpa = localMslHpa,
      referenceMslHpa = referenceMslHpa,
    )
  }

  /** Un ventesimo del residuo per confronto: venti ore per assorbire un errore, nessun inseguimento del rumore. */
  const val REFINE_RATE: Double = 0.05

  const val CONFIDENCE_STEP: Double = 0.01

  const val MAX_CONFIDENCE: Double = 0.9

  /** Una sola raffica contro un solo riferimento: un terzo di fiducia, il resto lo danno i giorni. */
  const val CONFIDENCE_WITH_ALTITUDE: Double = 0.35

  /** Quota ignota: la stima puo' sbagliare di hPa interi, e lo si dichiara. */
  const val CONFIDENCE_WITHOUT_ALTITUDE: Double = 0.12
}
