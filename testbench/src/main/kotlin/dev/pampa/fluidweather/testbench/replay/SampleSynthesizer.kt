package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.testbench.data.HourlyRecord
import dev.pampa.fluidweather.testbench.data.StationDataset
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Come il banco finge di essere un telefono.
 *
 * IDEALE e' quello che il banco ha sempre fatto: i record orari cosi' come sono, quota esatta,
 * sempre fermo, nessun rumore. E' un mondo che non esiste, e per due anni ha dato al motore un
 * voto che il telefono non ha mai potuto confermare: le tendenze imparate li' erano tendenze di
 * una serie liscia, e la deviazione di una delle feature era zero perche' il filtro convergeva
 * ogni volta allo stesso identico sigma.
 *
 * TELEFONO e' l'altra meta' del banco: cadenza a quindici minuti, raffiche corte con il rumore
 * del sensore, la quota GPS che balla, buchi in Doze, letture secche, e ogni tanto un ascensore
 * o un viaggio in auto. Non e' un mondo perfetto, e' *quel* mondo.
 */
enum class SamplingProfile { IDEALE, TELEFONO }

/**
 * Trasforma i record orari nella cosa che il telefono avrebbe visto: pressione DI STAZIONE
 * (non ridotta — ridurla e' compito della pipeline), quota, coordinate, attivita'.
 *
 * **Il rumore e' deterministico rispetto all'istante, non alla chiamata.** Il replay fa scorrere
 * una finestra e risintetizza; se il rumore dipendesse dall'ordine delle chiamate, la stessa ora
 * apparirebbe diversa a ogni passo e nessun confronto varrebbe niente. Il seme di ogni punto
 * esce dal suo timestamp: due finestre che si sovrappongono vedono gli stessi identici campioni.
 */
class SampleSynthesizer(
  private val dataset: StationDataset,
  private val profile: SamplingProfile = SamplingProfile.IDEALE,
  private val seed: Long = 20260910L,
  /** BILANCIATA e' il default dell'app: un giro ogni quarto d'ora. */
  private val cadenceMinutes: Int = 15,
  /**
   * L'errore verticale del fused provider. Metterlo a zero produce lo stesso telefono con un GPS
   * perfetto: e' il termine di paragone con cui il banco misura quanto la quota, da sola, stava
   * sporcando il segnale.
   */
  private val altitudeSigmaMeters: Double = ALTITUDE_SIGMA_METERS,
) {

  private val ordered = dataset.records.sortedBy { it.timestampMillis }
  private val cadenceMillis = cadenceMinutes * 60_000L

  fun samplesBetween(fromMillis: Long, toMillis: Long): List<PressureSample> = when (profile) {
    SamplingProfile.IDEALE -> ordered
      .asSequence()
      .filter { it.timestampMillis in fromMillis..toMillis }
      .mapNotNull { it.toIdealSample() }
      .toList()

    SamplingProfile.TELEFONO -> phoneSamples(fromMillis, toMillis)
  }

  private fun HourlyRecord.toIdealSample(): PressureSample? {
    val station = surfacePressureHpa ?: return null
    return PressureSample(
      timestampMillis = timestampMillis,
      pressureHpa = station,
      source = SampleSource.PERIODIC,
      altitudeMeters = dataset.elevationMeters,
      latitude = dataset.location.latitude,
      longitude = dataset.location.longitude,
      activity = ActivityKind.STILL,
      activityConfidence = 100,
    )
  }

  private fun phoneSamples(fromMillis: Long, toMillis: Long): List<PressureSample> {
    val samples = mutableListOf<PressureSample>()
    var slot = (fromMillis / cadenceMillis) * cadenceMillis
    if (slot < fromMillis) slot += cadenceMillis
    while (slot <= toMillis) {
      appendPass(slot, samples)
      slot += cadenceMillis
    }
    return samples
  }

  /** Un giro di campionamento: o non avviene (Doze), o e' una raffica, o e' una lettura secca. */
  private fun appendPass(slotMillis: Long, into: MutableList<PressureSample>) {
    val truth = interpolate(slotMillis) ?: return
    val random = Random(seed * 1_000_003L + slotMillis / 1_000L)

    if (random.nextDouble() < DOZE_SKIP_PROBABILITY) return
    // Un buco lungo al giorno, agganciato al giorno solare: e' il Doze profondo della notte.
    val minuteOfDay = Math.floorMod(slotMillis, 86_400_000L) / 60_000L
    if (minuteOfDay in DEEP_DOZE_FROM_MINUTE until DEEP_DOZE_TO_MINUTE) return

    val artefact = random.nextDouble()
    val inVehicle = artefact < VEHICLE_PROBABILITY
    val inElevator = !inVehicle && artefact < VEHICLE_PROBABILITY + ELEVATOR_PROBABILITY
    val single = !inVehicle && !inElevator && random.nextDouble() < SINGLE_READING_PROBABILITY

    // La deriva termica del sensore: il telefono si scalda e si raffredda, e il barometro la
    // sente. L'ampiezza e' un'ipotesi dichiarata, non una misura — lo sweep del banco puo'
    // rivederla, ma zero sarebbe una bugia comoda.
    val drift = THERMAL_DRIFT_HPA * sin(2 * PI * slotMillis / THERMAL_PERIOD_MILLIS.toDouble())
    val offset = when {
      inElevator -> ELEVATOR_OFFSET_HPA
      inVehicle -> random.nextGaussian() * 3.0
      else -> 0.0
    }
    val centre = truth + drift + offset

    // La quota che il GPS dichiara: qualche punto non ce l'ha per niente (al chiuso, permesso
    // negato), gli altri sbagliano di metri. L'ascensore, invece, il GPS non lo vede proprio:
    // e' il caso peggiore, quello che la riduzione non puo' compensare nemmeno volendo.
    val altitude = when {
      random.nextDouble() < NO_ALTITUDE_PROBABILITY -> null
      inElevator -> dataset.elevationMeters
      else -> dataset.elevationMeters + random.nextGaussian() * altitudeSigmaMeters
    }
    val activity = if (inVehicle) ActivityKind.IN_VEHICLE else ActivityKind.STILL
    val source = if (inVehicle || inElevator) SampleSource.CONTINUOUS else SampleSource.PERIODIC

    if (single) {
      into += sample(slotMillis, centre + random.nextGaussian() * SENSOR_SIGMA_HPA, null, altitude, activity, source)
      return
    }
    val burstId = "b-$slotMillis"
    for (i in 0 until BURST_SAMPLES) {
      into += sample(
        slotMillis + i * 1_000L,
        centre + random.nextGaussian() * SENSOR_SIGMA_HPA,
        burstId,
        altitude,
        activity,
        source,
      )
    }
  }

  private fun sample(
    timestampMillis: Long,
    pressureHpa: Double,
    burstId: String?,
    altitudeMeters: Double?,
    activity: ActivityKind,
    source: SampleSource,
  ) = PressureSample(
    timestampMillis = timestampMillis,
    pressureHpa = pressureHpa,
    source = source,
    burstId = burstId,
    altitudeMeters = altitudeMeters,
    latitude = dataset.location.latitude,
    longitude = dataset.location.longitude,
    activity = activity,
    activityConfidence = 90,
  )

  /**
   * La pressione di stazione fra un'ora e l'altra. Lineare: l'archivio non sa cosa sia successo
   * nei cinquantanove minuti in mezzo, e inventarci una curva vorrebbe dire fingere di saperlo.
   */
  private fun interpolate(timestampMillis: Long): Double? {
    val index = indexAtOrAfter(timestampMillis)
    if (index >= ordered.size) return null
    val after = ordered[index]
    if (after.timestampMillis == timestampMillis) return after.surfacePressureHpa
    if (index == 0) return null
    val before = ordered[index - 1]
    val a = before.surfacePressureHpa ?: return null
    val b = after.surfacePressureHpa ?: return null
    val span = (after.timestampMillis - before.timestampMillis).toDouble()
    if (span <= 0.0) return a
    val position = (timestampMillis - before.timestampMillis) / span
    return a + (b - a) * position
  }

  private fun indexAtOrAfter(timestampMillis: Long): Int {
    var low = 0
    var high = ordered.size
    while (low < high) {
      val mid = (low + high) / 2
      if (ordered[mid].timestampMillis < timestampMillis) low = mid + 1 else high = mid
    }
    return low
  }

  companion object {
    /** Rumore tipico di un barometro MEMS da telefono su una singola lettura. */
    const val SENSOR_SIGMA_HPA = 0.03

    /** Cinque campioni: il minimo perche' il MAD della raffica esista (ne servono quattro). */
    const val BURST_SAMPLES = 5

    /** L'errore verticale del fused provider: la ragione per cui la quota va stabilizzata. */
    const val ALTITUDE_SIGMA_METERS = 6.0

    const val NO_ALTITUDE_PROBABILITY = 0.04
    const val SINGLE_READING_PROBABILITY = 0.10
    const val VEHICLE_PROBABILITY = 0.004
    const val ELEVATOR_PROBABILITY = 0.004
    const val ELEVATOR_OFFSET_HPA = -1.2

    /** Un giro su otto non avviene: WorkManager non e' un orologio. */
    const val DOZE_SKIP_PROBABILITY = 0.12

    /** E una volta al giorno il sistema dorme sul serio, per due ore. */
    const val DEEP_DOZE_FROM_MINUTE = 3 * 60L
    const val DEEP_DOZE_TO_MINUTE = 5 * 60L

    const val THERMAL_DRIFT_HPA = 0.05
    const val THERMAL_PERIOD_MILLIS = 8 * 3_600_000L
  }
}
