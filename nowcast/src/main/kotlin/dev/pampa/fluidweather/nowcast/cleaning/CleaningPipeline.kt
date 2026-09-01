package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.DeviceCalibration
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.nowcast.acquisition.AggregatedPoint
import dev.pampa.fluidweather.nowcast.acquisition.BurstAggregator
import dev.pampa.fluidweather.nowcast.acquisition.DiscardedBurst
import kotlin.math.abs

/** Un punto sopravvissuto alla pulizia, nelle due valute: alla stazione e al livello del mare. */
data class CleanPoint(
  val timestampMillis: Long,
  /** Dopo la correzione del bias del dispositivo. */
  val stationPressureHpa: Double,
  /** Ridotta con la quota effettiva del punto: e' la serie su cui ragiona tutto il resto. */
  val seaLevelPressureHpa: Double,
  val noiseSigmaHpa: Double,
  val altitudeMeters: Double?,
)

data class CleaningResult(
  val cleaned: List<CleanPoint>,
  val filtered: List<FilteredPoint>,
  val rejected: List<RejectedPoint>,
  val discardedBursts: List<DiscardedBurst>,
) {
  /** L'ultima stima disponibile: livello e tendenza con le loro incertezze. */
  val latest: FilteredPoint? get() = filtered.lastOrNull()

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
 *  3. bias del dispositivo e riduzione al livello del mare con la quota effettiva — un cambio di
 *     piano con GPS onesto viene *compensato* qui, non scartato;
 *  4. screening dei salti non-meteo sulla serie *ridotta* — quello che ancora salta dopo la
 *     compensazione (l'ascensore col GPS cieco) non e' meteo per definizione;
 *  5. filtro di Kalman su livello + tendenza.
 *
 * Nota sulla quota: la GPS e' ellissoidica e balla di ±10 m; i punti senza quota usano la
 * mediana delle quote della serie, cosi' la riduzione resta *coerente* punto per punto. Un
 * offset costante rispetto alla quota vera si scarica su un offset costante di pressione, che
 * per il nowcast e' innocuo: conta la tendenza, e il bias assoluto si stima contro le stazioni
 * di riferimento (fase 6-7).
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
) {

  fun process(
    samples: List<PressureSample>,
    calibration: DeviceCalibration = DeviceCalibration(),
    /** La temperatura reale alla stazione, quando i provider (fase 6) la porteranno. */
    temperatureCelsius: Double? = null,
  ): CleaningResult {
    val aggregation = aggregator.aggregate(samples)
    val screening = screener.screen(aggregation.points)

    val temperature = temperatureCelsius ?: SeaLevel.STANDARD_TEMPERATURE_CELSIUS
    val referenceAltitude = medianAltitude(screening.accepted)

    val reduced = screening.accepted.map { point ->
      val effectiveAltitude = point.altitudeMeters ?: referenceAltitude
      val station = point.pressureHpa - calibration.biasHpa
      point to CleanPoint(
        timestampMillis = point.timestampMillis,
        stationPressureHpa = station,
        seaLevelPressureHpa = SeaLevel.reduce(station, effectiveAltitude, temperature),
        noiseSigmaHpa = point.spreadHpa,
        altitudeMeters = point.altitudeMeters,
      )
    }

    val rejected = screening.rejected.toMutableList()
    val cleaned = screenJumps(reduced, rejected)

    val filtered = kalman.filter(
      cleaned.map { Measurement(it.timestampMillis, it.seaLevelPressureHpa, it.noiseSigmaHpa) },
    )

    return CleaningResult(
      cleaned = cleaned,
      filtered = filtered,
      rejected = rejected,
      discardedBursts = aggregation.discardedBursts,
    )
  }

  /**
   * Il confronto e' sempre con l'ultimo punto *tenuto*: dopo un salto vero (trasloco a un piano
   * diverso, GPS cieco) i punti al nuovo livello vengono rifiutati finche' il tempo trascorso
   * non riporta il tasso sotto soglia — per 1,2 hPa bastano ~7 minuti — e da li' la serie si
   * riaggancia da sola al nuovo livello. Nessuno stato nascosto da resettare.
   */
  private fun screenJumps(
    reduced: List<Pair<AggregatedPoint, CleanPoint>>,
    rejected: MutableList<RejectedPoint>,
  ): List<CleanPoint> {
    val kept = mutableListOf<CleanPoint>()
    for ((aggregated, clean) in reduced) {
      val previous = kept.lastOrNull()
      if (previous != null) {
        val dtHours = (clean.timestampMillis - previous.timestampMillis) / 3_600_000.0
        if (dtHours <= 0.0) continue // duplicato temporale: non porta informazione
        val delta = abs(clean.seaLevelPressureHpa - previous.seaLevelPressureHpa)
        if (delta >= minJumpHpa && delta / dtHours > maxWeatherRateHpaPerHour) {
          rejected += RejectedPoint(aggregated, RejectionReason.NON_WEATHER_JUMP)
          continue
        }
      }
      kept += clean
    }
    return kept
  }

  private fun medianAltitude(points: List<AggregatedPoint>): Double {
    val altitudes = points.mapNotNull { it.altitudeMeters }.sorted()
    if (altitudes.isEmpty()) return 0.0
    val middle = altitudes.size / 2
    return if (altitudes.size % 2 == 1) {
      altitudes[middle]
    } else {
      (altitudes[middle - 1] + altitudes[middle]) / 2.0
    }
  }
}
