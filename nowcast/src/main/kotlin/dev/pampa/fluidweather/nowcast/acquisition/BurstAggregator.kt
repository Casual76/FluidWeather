package dev.pampa.fluidweather.nowcast.acquisition

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource

/**
 * Un punto barico: una raffica compressa in un valore robusto, o una lettura secca cosi' com'e'.
 *
 * [spreadHpa] e' il MAD della raffica (0 per le letture singole): serve sia da filtro qui — una
 * varianza anomala racconta un sensore in movimento o difettoso, non il meteo — sia da rumore di
 * misura per il filtro di Kalman a valle, che cosi' si fida meno dei punti nati male.
 */
data class AggregatedPoint(
  val timestampMillis: Long,
  val pressureHpa: Double,
  val spreadHpa: Double,
  val sampleCount: Int,
  val altitudeMeters: Double?,
  val latitude: Double?,
  val longitude: Double?,
  val activity: ActivityKind,
  val activityConfidence: Int?,
  val source: SampleSource,
)

data class AggregationResult(
  val points: List<AggregatedPoint>,
  /** Le raffiche buttate per varianza anomala, con il loro MAD: la diagnostica le conta. */
  val discardedBursts: List<DiscardedBurst>,
)

data class DiscardedBurst(val burstId: String, val sampleCount: Int, val madHpa: Double)

/**
 * Stadio 1 della pipeline: mediana + MAD per raffica, scarto delle raffiche con varianza anomala.
 *
 * Mediana e MAD invece di media e deviazione standard perche' il guasto tipico non e' rumore
 * gaussiano piu' largo: e' *qualche* campione impazzito (una portiera che sbatte, un tocco sul
 * telefono, un glitch del sensore) in mezzo a campioni buoni — ed e' esattamente il caso in cui
 * la mediana non si muove e la media si.
 */
class BurstAggregator(
  /**
   * Il rumore tipico del sensore e' 0,02-0,05 hPa, quindi il MAD di una raffica ferma sta sotto
   * 0,05. Un ascensore preso a meta' raffica sposta le letture di ~1 hPa e porta il MAD sopra
   * 0,3. Tre volte il rumore peggiore separa i due mondi con margine da entrambi i lati.
   */
  private val maxMadHpa: Double = 0.15,
) {

  fun aggregate(samples: List<PressureSample>): AggregationResult {
    if (samples.isEmpty()) return AggregationResult(emptyList(), emptyList())
    val ordered = samples.sortedBy { it.timestampMillis }

    val points = mutableListOf<AggregatedPoint>()
    val discarded = mutableListOf<DiscardedBurst>()

    // Le letture singole passano cosi' come sono; le raffiche si raggruppano per id, non per
    // vicinanza temporale: due raffiche back-to-back (fine giro periodico + inizio sorveglianza)
    // devono restare due punti.
    val singles = ordered.filter { it.burstId == null }
    val bursts = ordered.filter { it.burstId != null }.groupBy { it.burstId!! }

    singles.mapTo(points) { it.toPoint(pressure = it.pressureHpa, spread = 0.0, count = 1) }

    for ((burstId, burstSamples) in bursts) {
      val pressures = burstSamples.map { it.pressureHpa }
      val center = median(pressures)
      val mad = median(pressures.map { kotlin.math.abs(it - center) })
      if (burstSamples.size >= MIN_SAMPLES_FOR_MAD && mad > maxMadHpa) {
        discarded += DiscardedBurst(burstId, burstSamples.size, mad)
        continue
      }
      val reference = burstSamples[burstSamples.size / 2]
      points += reference.toPoint(pressure = center, spread = mad, count = burstSamples.size)
    }

    points.sortBy { it.timestampMillis }
    return AggregationResult(points, discarded)
  }

  private fun PressureSample.toPoint(pressure: Double, spread: Double, count: Int) =
    AggregatedPoint(
      timestampMillis = timestampMillis,
      pressureHpa = pressure,
      spreadHpa = spread,
      sampleCount = count,
      altitudeMeters = altitudeMeters,
      latitude = latitude,
      longitude = longitude,
      activity = activity,
      activityConfidence = activityConfidence,
      source = source,
    )

  private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
  }

  private companion object {
    /** Sotto quattro campioni il MAD non distingue niente: la raffica passa senza filtro. */
    const val MIN_SAMPLES_FOR_MAD = 4
  }
}
