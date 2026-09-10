package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.DeviceCalibration
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.nowcast.acquisition.AggregatedPoint
import dev.pampa.fluidweather.nowcast.acquisition.BurstAggregator
import dev.pampa.fluidweather.nowcast.acquisition.DiscardedBurst
import dev.pampa.fluidweather.nowcast.tide.TidalModel
import dev.pampa.fluidweather.nowcast.tide.TidalSummary
import dev.pampa.fluidweather.nowcast.tide.TideEstimator
import dev.pampa.fluidweather.nowcast.tide.ZeroTide
import kotlin.math.abs
import kotlin.math.max

/** Un punto sopravvissuto alla pulizia, nelle due valute: alla stazione e al livello del mare. */
data class CleanPoint(
  val timestampMillis: Long,
  /** Dopo la correzione del bias del dispositivo. */
  val stationPressureHpa: Double,
  /** Ridotta con la quota *stabilizzata* del punto: e' la serie su cui ragiona tutto il resto. */
  val seaLevelPressureHpa: Double,
  val noiseSigmaHpa: Double,
  /** La quota grezza del campione (diagnostica); la riduzione usa [reductionAltitudeMeters]. */
  val altitudeMeters: Double?,
  /** La quota che la riduzione ha davvero usato, dopo mediana mobile e isteresi. */
  val reductionAltitudeMeters: Double = 0.0,
  /** Le coordinate servono allo stadio 3: la marea e' agganciata al sole del posto. */
  val latitude: Double?,
  val longitude: Double?,
  /**
   * Qui la serie ha cambiato livello per una ragione non meteorologica (un piano diverso, un
   * trasloco, la quota di riduzione che si e' spostata). Il filtro lo sa e riapre la sua
   * incertezza invece di leggere il gradino come una tendenza.
   */
  val levelStep: Boolean = false,
)

data class CleaningResult(
  val cleaned: List<CleanPoint>,
  val filtered: List<FilteredPoint>,
  val rejected: List<RejectedPoint>,
  val discardedBursts: List<DiscardedBurst>,
  /** Cosa lo stadio 3 ha sottratto, e da quale modello: la diagnostica lo dichiara. */
  val tide: TidalSummary,
  /** La quota con cui la riduzione ha lavorato in fondo alla serie: la Diagnostica la mostra. */
  val reductionAltitudeMeters: Double = 0.0,
) {
  /** L'ultima stima disponibile: livello e tendenza con le loro incertezze. */
  val latest: FilteredPoint? get() = filtered.lastOrNull()

  /**
   * L'ampiezza in ore della serie pulita: sono le "13 ore di storia" della barra del barometro.
   *
   * Vive qui perche' la stessa espressione era scritta in tre punti diversi (il caso d'uso, la
   * schermata del motore, la tessera) — tre copie di un numero che l'utente legge come uno solo, e
   * che quando resta a zero fa sembrare l'app rotta.
   *
   * Zero con un punto solo non e' un ripiego: **e' la verita'**. I seicento campioni di una
   * raffica condividono un `burstId` e diventano un punto aggregato, quindi subito dopo la
   * taratura l'ampiezza e' davvero nulla. Chi lo mostra deve dirlo con parole, non con uno zero.
   */
  val historyHours: Double
    get() = filtered.takeIf { it.size >= 2 }
      ?.let { (it.last().timestampMillis - it.first().timestampMillis) / 3_600_000.0 }
      ?: 0.0

  /** Quanti punti ha mangiato ogni stadio: e' lo "stato di ogni stadio" della diagnostica. */
  fun rejectionCounts(): Map<RejectionReason, Int> {
    val counts = rejected.groupingBy { it.reason }.eachCount().toMutableMap()
    if (discardedBursts.isNotEmpty()) {
      counts[RejectionReason.ANOMALOUS_VARIANCE] =
        (counts[RejectionReason.ANOMALOUS_VARIANCE] ?: 0) + discardedBursts.size
    }
    return counts
  }
}

/**
 * Stadi 1-2 della pipeline: dai campioni grezzi a una serie pulita, ridotta al livello del mare
 * e filtrata, con ogni scarto motivato. L'ordine degli stadi non e' decorativo:
 *
 *  1. aggregazione (mediana + MAD per raffica, scarto varianza anomala);
 *  2. screening su attivita' e quota GPS — *prima* della riduzione, perche' sono fatti sul
 *     contesto del punto, non sul suo valore;
 *  3. bias del dispositivo e riduzione al livello del mare con la quota **stabilizzata**;
 *  4. screening dei salti non-meteo sulla serie *ridotta*, con dichiarazione dei gradini veri;
 *  5. de-tidalizzazione S1/S2 (stadio 3): la marea atmosferica si sottrae *dopo* lo screening —
 *     varia al piu' di ~0,6 hPa/h, ben sotto la soglia dei salti — e *prima* del filtro, cosi'
 *     la tendenza stimata e' quella sinottica, non il calo pomeridiano fisiologico;
 *  6. filtro di Kalman su livello + tendenza della serie de-tidalizzata.
 *
 * **La quota, e perche' non e' piu' quella del singolo campione.** La GPS e' ellissoidica e balla
 * di piu' o meno dieci metri; a 0,12 hPa/m sono 1,2 hPa iniettati punto per punto nella serie
 * ridotta — piu' grandi del segnale sinottico che stiamo cercando di leggere, e troppo piccoli
 * perche' lo screening dei salti li veda a cadenza di quindici minuti. Ridurre ogni punto con la
 * *sua* quota voleva dire credere al rumore del GPS. Adesso la quota di riduzione e' una traccia:
 * mediana mobile piu' isteresi, cosi' il ballonzolamento sparisce e un piano diverso — o un
 * trasloco vero — viene comunque seguito, dichiarando un gradino invece di far finta di niente.
 * Un offset costante rispetto alla quota vera resta innocuo: al nowcast interessa la tendenza, e
 * il bias assoluto si stima contro le stazioni di riferimento.
 */
class CleaningPipeline(
  private val aggregator: BurstAggregator = BurstAggregator(),
  private val screener: SampleScreener = SampleScreener(),
  private val kalman: PressureKalmanFilter = PressureKalmanFilter(),
  /**
   * Il meteo vero piu' violento (groppi, derecho) resta sotto ~6 hPa/h su scale di decine di
   * minuti; le soglie classiche di allerta sono ~1,3 hPa/h (3-4 hPa su 3 h). Un ascensore di
   * tre piani fa ~1 hPa in 20 secondi, cioe' ~180 hPa/h. Dieci hPa/h passa il meteo estremo e
   * boccia la meccanica.
   */
  private val maxWeatherRateHpaPerHour: Double = 10.0,
  /**
   * La soglia assoluta che protegge il campionamento fitto: fra due letture a 10 s, 0,05 hPa di
   * rumore sono gia' 18 hPa/h — un tasso enorme su un salto insignificante. Sotto mezzo hPa non
   * e' un salto, e' rumore.
   */
  private val minJumpHpa: Double = 0.5,
  /**
   * Il tetto assoluto, che il solo tasso non sa mettere: due hPa fra due punti dentro la mezz'ora
   * sono ~17 metri di dislivello o un guasto, mai un fronte. A cadenza BILANCIATA il tasso da
   * solo lascerebbe passare fino a 2,5 hPa.
   */
  private val maxStepHpa: Double = 2.0,
  private val maxStepWindowMillis: Long = 30 * 60_000L,
  /**
   * Un gradino vero **dura**. Tre punti d'accordo fra loro distribuiti su almeno dieci minuti
   * sono un piano diverso; gli stessi tre punti dentro quaranta secondi sono un ascensore, e
   * restano scartati.
   */
  private val stepPointsToConfirm: Int = 3,
  private val stepPersistenceMillis: Long = 10 * 60_000L,
  private val stepAgreementHpa: Double = 0.6,
  /** La quota di riduzione si sposta solo quando la mediana mobile si sposta *davvero*. */
  private val altitudeStepMeters: Double = 15.0,
  private val altitudeMedianWindowMillis: Long = 45 * 60_000L,
  /**
   * Lo stadio 3. Null spegne la de-tidalizzazione (utile nei test e nei confronti A/B del
   * banco di prova); il default e' la cascata fit locale -> prior climatologico -> zero.
   */
  private val tideEstimator: TideEstimator? = TideEstimator(),
) {

  fun process(
    samples: List<PressureSample>,
    calibration: DeviceCalibration = DeviceCalibration(),
    /** La temperatura reale alla stazione, quando i provider (fase 6) la portano. */
    temperatureCelsius: Double? = null,
    /**
     * La quota di casa, quella imparata dalla taratura e tenuta da parte fra un giro e l'altro.
     * Null = si ripiega sulla mediana della finestra, che pero' cambia quando la finestra scorre:
     * passarla e' quello che rende la serie *la stessa* fra un refresh e il successivo.
     */
    referenceAltitudeMeters: Double? = null,
    /**
     * Le coordinate del posto, per quando i campioni non ne hanno.
     *
     * Lo stadio 3 aggancia la marea atmosferica al sole del luogo: senza coordinate ripiegava su
     * "nessuna marea", e passare da nessuna marea al prior climatologico — o viceversa, quando il
     * permesso di posizione va e viene — sposta la serie di un hPa e mezzo di colpo. L'app le
     * coordinate ce l'ha comunque, nell'istantanea del meteo: non c'e' motivo di fingere di no.
     */
    latitude: Double? = null,
    longitude: Double? = null,
  ): CleaningResult {
    val aggregation = aggregator.aggregate(samples)
    val screening = screener.screen(aggregation.points)

    val temperature = temperatureCelsius ?: SeaLevel.STANDARD_TEMPERATURE_CELSIUS
    val fallbackAltitude = referenceAltitudeMeters ?: medianAltitude(screening.accepted)
    val track = altitudeTrack(screening.accepted, referenceAltitudeMeters, fallbackAltitude)

    val reduced = screening.accepted.mapIndexed { index, point ->
      val altitude = track[index]
      val station = point.pressureHpa - calibration.biasHpa
      point to CleanPoint(
        timestampMillis = point.timestampMillis,
        stationPressureHpa = station,
        seaLevelPressureHpa = SeaLevel.reduce(station, altitude, temperature),
        noiseSigmaHpa = measurementSigma(point),
        altitudeMeters = point.altitudeMeters,
        reductionAltitudeMeters = altitude,
        latitude = point.latitude,
        longitude = point.longitude,
        // La quota di riduzione che si sposta e' un gradino annunciato: il filtro non deve
        // leggerlo come tendenza, e non c'e' bisogno che lo indovini.
        levelStep = index > 0 && track[index] != track[index - 1],
      )
    }

    val rejected = screening.rejected.toMutableList()
    val cleaned = screenJumps(reduced, rejected)

    val located = if (latitude == null || longitude == null) {
      cleaned
    } else {
      cleaned.map { if (it.latitude == null || it.longitude == null) it.copy(latitude = latitude, longitude = longitude) else it }
    }
    val tide: TidalModel =
      if (tideEstimator != null && located.isNotEmpty()) tideEstimator.estimate(located) else ZeroTide

    val filtered = kalman.filter(
      cleaned.map {
        Measurement(
          it.timestampMillis,
          it.seaLevelPressureHpa - tide.tideAt(it.timestampMillis),
          it.noiseSigmaHpa,
          levelStep = it.levelStep,
        )
      },
    )

    return CleaningResult(
      cleaned = cleaned,
      filtered = filtered,
      rejected = rejected,
      discardedBursts = aggregation.discardedBursts,
      tide = TidalSummary(
        source = tide.source,
        s1AmplitudeHpa = tide.s1AmplitudeHpa,
        s2AmplitudeHpa = tide.s2AmplitudeHpa,
        tideAtLatestHpa = cleaned.lastOrNull()?.let { tide.tideAt(it.timestampMillis) } ?: 0.0,
      ),
      reductionAltitudeMeters = cleaned.lastOrNull()?.reductionAltitudeMeters ?: fallbackAltitude,
    )
  }

  /**
   * L'incertezza da consegnare al filtro, che non e' il MAD.
   *
   * Il MAD e' ~0,675 sigma per rumore gaussiano: passarlo cosi' com'e' faceva credere al Kalman
   * di essere il cinquanta per cento piu' preciso di quanto fosse. E una lettura secca —
   * sorveglianza, monitor continuo, modalita' MINIMA — non ha nessuna mediana dietro: entrava
   * con spread 0,0, cioe' come la misura piu' affidabile della serie, che e' l'esatto contrario
   * del vero.
   */
  private fun measurementSigma(point: AggregatedPoint): Double {
    val floor = if (point.sampleCount >= MIN_BURST_SAMPLES) BURST_SIGMA_FLOOR_HPA else SINGLE_SIGMA_FLOOR_HPA
    return max(MAD_TO_SIGMA * point.spreadHpa, floor)
  }

  /**
   * La quota di riduzione punto per punto: mediana mobile centrata piu' isteresi.
   *
   * La mediana toglie il ballonzolamento del GPS senza inseguirlo; l'isteresi fa in modo che la
   * quota si sposti solo quando si e' spostata davvero, cosi' due giri consecutivi riducono la
   * stessa serie allo stesso modo. I punti senza quota ereditano quella corrente: erano il caso
   * peggiore, perche' prima prendevano la mediana dell'intera finestra scorrevole e quindi
   * cambiavano valore a ogni refresh, traslando tutta la curva.
   */
  private fun altitudeTrack(
    points: List<AggregatedPoint>,
    reference: Double?,
    fallback: Double,
  ): DoubleArray {
    if (points.isEmpty()) return DoubleArray(0)
    val track = DoubleArray(points.size)
    // Il seme e la quota di casa quando c'e' (persistita, quindi identica fra un giro e l'altro),
    // altrimenti la mediana del primo intorno. Mai la mediana dell'intera finestra: quella
    // e' una media fra posti diversi, e puo' cadere a meta' strada fra due quote vere,
    // bloccando l'isteresi esattamente sul confine.
    var current = reference ?: windowMedianAltitude(points, 0) ?: fallback
    for (index in points.indices) {
      val local = windowMedianAltitude(points, index)
      if (local != null && abs(local - current) > altitudeStepMeters) current = local
      track[index] = current
    }
    return track
  }

  private fun windowMedianAltitude(points: List<AggregatedPoint>, index: Int): Double? {
    val centre = points[index].timestampMillis
    val altitudes = mutableListOf<Double>()
    var i = index
    while (i >= 0 && centre - points[i].timestampMillis <= altitudeMedianWindowMillis) {
      points[i].altitudeMeters?.let { altitudes += it }
      i--
    }
    i = index + 1
    while (i < points.size && points[i].timestampMillis - centre <= altitudeMedianWindowMillis) {
      points[i].altitudeMeters?.let { altitudes += it }
      i++
    }
    return median(altitudes)
  }

  /**
   * Il confronto e' sempre con l'ultimo punto *tenuto*. Un punto incoerente col meteo viene messo
   * da parte; ma se i punti messi da parte si mettono d'accordo fra loro **e durano**
   * ([stepPointsToConfirm] punti distribuiti su almeno [stepPersistenceMillis]), allora non e' un
   * guasto: e' la serie che vive a un livello nuovo. Si dichiara il gradino e si riparte da li',
   * invece di aspettare che il tempo trascorso riporti il tasso sotto soglia — che per otto hPa
   * voleva dire quasi un'ora di serie buttata.
   *
   * L'ascensore non passa da questa porta: tre letture identiche dentro quaranta secondi sono
   * d'accordo fra loro ma non durano, e restano scartate.
   */
  private fun screenJumps(
    reduced: List<Pair<AggregatedPoint, CleanPoint>>,
    rejected: MutableList<RejectedPoint>,
  ): List<CleanPoint> {
    val kept = mutableListOf<CleanPoint>()
    val pending = mutableListOf<Pair<AggregatedPoint, CleanPoint>>()

    fun dropPending() {
      pending.forEach { rejected += RejectedPoint(it.first, RejectionReason.NON_WEATHER_JUMP) }
      pending.clear()
    }

    for (candidate in reduced) {
      val (_, clean) = candidate
      val previous = kept.lastOrNull()
      if (previous == null) {
        kept += clean
        continue
      }
      val dtHours = (clean.timestampMillis - previous.timestampMillis) / 3_600_000.0
      if (dtHours <= 0.0) continue // duplicato temporale: non porta informazione
      val delta = abs(clean.seaLevelPressureHpa - previous.seaLevelPressureHpa)
      val tooFast = delta >= minJumpHpa && delta / dtHours > maxWeatherRateHpaPerHour
      val tooBig = delta > maxStepHpa &&
        clean.timestampMillis - previous.timestampMillis <= maxStepWindowMillis

      // Un gradino gia' annunciato dalla quota di riduzione non e' una sorpresa da giudicare.
      if (clean.levelStep || !(tooFast || tooBig)) {
        // Riagganciarsi lontano dall'ultimo punto tenuto, dopo aver scartato qualcosa, e' un
        // gradino anche quando il tempo trascorso ha rimesso il tasso sotto soglia: la serie
        // ha traslato, e il filtro deve saperlo invece di leggere il dislivello come tendenza.
        val relatched = pending.isNotEmpty() && delta >= minJumpHpa
        dropPending()
        kept += if (relatched) clean.copy(levelStep = true) else clean
        continue
      }

      pending += candidate
      val persisted = pending.last().second.timestampMillis -
        pending.first().second.timestampMillis >= stepPersistenceMillis
      if (pending.size >= stepPointsToConfirm && persisted && agree(pending.map { it.second.seaLevelPressureHpa })) {
        // I punti che hanno costruito la prova restano scartati (erano in transizione); quello
        // che chiude la prova entra, e porta con se' il cartello "qui la serie ha traslato".
        val last = pending.removeAt(pending.size - 1)
        dropPending()
        kept += last.second.copy(levelStep = true)
      }
    }
    dropPending()
    return kept
  }

  private fun agree(values: List<Double>): Boolean =
    values.isNotEmpty() && (values.max() - values.min()) <= stepAgreementHpa

  private fun medianAltitude(points: List<AggregatedPoint>): Double =
    median(points.mapNotNull { it.altitudeMeters }) ?: 0.0

  private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
      sorted[middle]
    } else {
      (sorted[middle - 1] + sorted[middle]) / 2.0
    }
  }

  private companion object {
    /** MAD -> sigma per rumore gaussiano. */
    const val MAD_TO_SIGMA = 1.4826

    /** Sotto quattro campioni il MAD non e' stato nemmeno calcolato (vedi BurstAggregator). */
    const val MIN_BURST_SAMPLES = 4

    /** Il rumore tipico di un barometro MEMS su una mediana da decine di campioni. */
    const val BURST_SIGMA_FLOOR_HPA = 0.05

    /** Una lettura sola: nessuna mediana la difende, e vale tre volte meno. */
    const val SINGLE_SIGMA_FLOOR_HPA = 0.15
  }
}
