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
   * Il bias da **piu' tratti fermi**, ciascuno col proprio riferimento.
   *
   * La vecchia [estimate] riduceva al mare la mediana di tutta la raffica con una quota sola — la
   * mediana delle quote — e la confrontava con un unico riferimento chiesto alla fine. Chi durante
   * i dieci minuti passava per tre paesi a quote diverse otteneva un numero che non descriveva
   * nessuno dei tre; e nessuno se ne accorgeva, perche' un bias sbagliato di un hPa somiglia
   * moltissimo a un bias giusto.
   *
   * Qui ogni tratto si riduce con la **propria** quota, si confronta col riferimento piu' vicino
   * nel tempo e nello spazio, e i residui votano una mediana pesata. Tre tratti a tre quote che
   * dicono lo stesso numero non sono solo una stima corretta: sono una **verifica**, ed e' per
   * questo che la fiducia sale con la concordanza invece che col numero di campioni.
   *
   * Restituisce null quando non c'e' niente su cui votare: nessun tratto, o nessun riferimento.
   */
  fun estimateSegments(
    segments: List<CalibrationSegment>,
    references: List<CalibrationReferenceSample>,
    nowMillis: Long,
    minStableSeconds: Int = CalibrationBurst.MIN_STABLE_SECONDS,
  ): CalibrationRecord? {
    if (segments.isEmpty() || references.isEmpty()) return null

    val votes = segments.mapNotNull { segment ->
      val median = median(segment.pressures) ?: return@mapNotNull null
      val reference = references.maxByOrNull { it.weightFor(segment) } ?: return@mapNotNull null
      val localMsl = SeaLevel.reduce(
        stationPressureHpa = median,
        altitudeMeters = segment.altitudeMeters ?: 0.0,
        temperatureCelsius = reference.temperatureCelsius ?: SeaLevel.STANDARD_TEMPERATURE_CELSIUS,
      )
      Vote(
        residual = localMsl - reference.mslHpa,
        localMslHpa = localMsl,
        referenceMslHpa = reference.mslHpa,
        altitudeMeters = segment.altitudeMeters,
        // Tre fattori, e ognuno risponde a una domanda diversa: quanto e' durato il tratto, se la
        // sua quota e' nota (senza, la riduzione e' cieca e puo' sbagliare di hPa interi), e
        // quanto il riferimento parla davvero di quel posto in quel momento.
        weight = segment.durationSeconds.toDouble() *
          (if (segment.altitudeMeters != null) 1.0 else BLIND_ALTITUDE_WEIGHT) *
          reference.weightFor(segment),
      )
    }
    if (votes.isEmpty()) return null

    val bias = weightedMedian(votes.map { it.residual to it.weight }) ?: return null
    val spread = if (votes.size > 1) meanAbsoluteDeviation(votes.map { it.residual }, bias) else 0.0
    val chosen = votes.minByOrNull { kotlin.math.abs(it.residual - bias) } ?: votes.first()
    val stableSeconds = segments.sumOf { it.durationSeconds }
    val completeness = (stableSeconds.toDouble() / minStableSeconds).coerceIn(0.1, 1.0)
    val base = if (votes.any { it.altitudeMeters != null }) CONFIDENCE_WITH_ALTITUDE else CONFIDENCE_WITHOUT_ALTITUDE

    return CalibrationRecord(
      biasHpa = bias,
      confidence = (base * completeness * agreementFactor(votes.size, spread)).coerceAtMost(MAX_CONFIDENCE),
      calibratedAtMillis = nowMillis,
      sampleCount = segments.sumOf { it.pressures.size },
      // Il tratto che ha vinto la mediana: e' quello di cui il bias racconta la storia, ed e'
      // quello che va mostrato accanto al numero quando qualcuno chiede da dove viene.
      localMslHpa = chosen.localMslHpa,
      referenceMslHpa = chosen.referenceMslHpa,
      altitudeMeters = chosen.altitudeMeters,
      segmentCount = votes.size,
      spreadHpa = spread,
    )
  }

  /**
   * Quanto la concordanza fra i tratti fa salire la fiducia: fino a [AGREEMENT_BONUS] in piu' se
   * i residui coincidono, niente se sono sparpagliati.
   *
   * Con un tratto solo non c'e' concordanza da misurare — e' la vecchia taratura, e vale quello
   * che valeva. Sopra [SPREAD_WORTHLESS_HPA] di dispersione i tratti si stanno contraddicendo: la
   * mediana pesata regge, ma non c'e' niente da premiare.
   */
  internal fun agreementFactor(segmentCount: Int, spreadHpa: Double): Double {
    if (segmentCount <= 1) return 1.0
    val agreement = (1.0 - (spreadHpa - SPREAD_PERFECT_HPA) / (SPREAD_WORTHLESS_HPA - SPREAD_PERFECT_HPA))
      .coerceIn(0.0, 1.0)
    return 1.0 + AGREEMENT_BONUS * agreement
  }

  /**
   * La mediana pesata: il valore in cui la meta' del peso sta sotto e meta' sopra.
   *
   * Pesata e non semplice perche' i tratti non valgono uguale (durata, quota, riferimento), e
   * mediana e non media perche' un tratto che ha preso una lettura sbagliata non deve poter
   * spostare il bias di tutti gli altri.
   */
  internal fun weightedMedian(weighted: List<Pair<Double, Double>>): Double? {
    val usable = weighted.filter { it.second > 0.0 }.sortedBy { it.first }
    if (usable.isEmpty()) return median(weighted.map { it.first })
    val half = usable.sumOf { it.second } / 2.0
    var running = 0.0
    for ((value, weight) in usable) {
      running += weight
      if (running >= half) return value
    }
    return usable.last().first
  }

  private data class Vote(
    val residual: Double,
    val localMslHpa: Double,
    val referenceMslHpa: Double,
    val altitudeMeters: Double?,
    val weight: Double,
  )

  /**
   * Quanto i tratti sono in disaccordo con il bias scelto: la deviazione assoluta **media**.
   *
   * Media e non mediana, ed e' l'opposto della scelta fatta per il bias, di proposito. La mediana
   * serve a stimare **nonostante** un tratto sbagliato; qui invece quel tratto e' esattamente cio'
   * che si vuole vedere — con tre tratti e uno fuori di dieci hPa la deviazione mediana assoluta
   * vale zero, cioe' dichiarerebbe accordo perfetto proprio nel caso in cui non c'e'.
   */
  internal fun meanAbsoluteDeviation(values: List<Double>, center: Double): Double {
    if (values.isEmpty()) return 0.0
    return values.sumOf { kotlin.math.abs(it - center) } / values.size
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

  /** Quanto pesa un tratto senza quota rispetto a uno che ce l'ha: poco, ma non zero. */
  const val BLIND_ALTITUDE_WEIGHT: Double = 0.3

  /** Sotto questa dispersione i tratti dicono la stessa cosa. */
  const val SPREAD_PERFECT_HPA: Double = 0.2

  /** Sopra questa si stanno contraddicendo: nessun premio alla concordanza. */
  const val SPREAD_WORTHLESS_HPA: Double = 1.5

  /** Quanto puo' crescere la fiducia quando piu' tratti indipendenti concordano. */
  const val AGREEMENT_BONUS: Double = 0.5
}
