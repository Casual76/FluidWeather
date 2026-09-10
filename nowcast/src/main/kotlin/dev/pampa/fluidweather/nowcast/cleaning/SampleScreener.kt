package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.nowcast.acquisition.AggregatedPoint
import kotlin.math.abs

/** Perche' un punto e' stato scartato: la diagnostica mostra lo stato di ogni stadio. */
enum class RejectionReason {
  /** Raffica con varianza anomala (stadio 1). */
  ANOMALOUS_VARIANCE,

  /** In auto, in bici, in aereo: cabine, salite continue, correnti — non e' meteo. */
  VEHICLE,

  /** La quota GPS e' cambiata troppo in fretta: scale, ascensore, dislivello in movimento. */
  ALTITUDE_CHANGE,

  /** Salto di pressione incoerente col meteo: nessun fronte fa cosi' in cosi' poco. */
  NON_WEATHER_JUMP,
}

data class RejectedPoint(val point: AggregatedPoint, val reason: RejectionReason)

data class ScreeningResult(
  val accepted: List<AggregatedPoint>,
  val rejected: List<RejectedPoint>,
)

/**
 * Lo screening pre-riduzione: attivita' e quota. (Il controllo sui salti di pressione vive nella
 * pipeline, *dopo* la riduzione al livello del mare: un cambio di quota con GPS buono viene
 * compensato dalla riduzione e non deve essere scartato; e' quello col GPS cieco che sopravvive
 * come salto, ed e' li' che lo si becca.)
 */
class SampleScreener(
  /**
   * Il rumore verticale del GPS e' ~±10 m (peggio al chiuso). Venticinque metri fra due punti
   * vicini nel tempo non sono rumore: sono piani di un palazzo o tornanti di una strada.
   */
  private val maxAltitudeDeltaMeters: Double = 25.0,
  /**
   * Oltre questa distanza nel tempo un dislivello non dice piu' niente sul singolo punto:
   * l'utente puo' essersi spostato legittimamente, e ci pensa la riduzione a compensare.
   */
  private val altitudeWindowMillis: Long = 10 * 60_000L,
  /** Sotto questa confidenza il riconoscimento attivita' e' un'ipotesi, non un fatto. */
  private val minActivityConfidence: Int = 50,
) {

  fun screen(points: List<AggregatedPoint>): ScreeningResult {
    val accepted = mutableListOf<AggregatedPoint>()
    val rejected = mutableListOf<RejectedPoint>()

    for (point in points) {
      val reason = when {
        point.isInTransit() -> RejectionReason.VEHICLE
        point.movedVertically(accepted.lastOrNull()) -> RejectionReason.ALTITUDE_CHANGE
        else -> null
      }
      if (reason == null) accepted += point else rejected += RejectedPoint(point, reason)
    }
    return ScreeningResult(accepted, rejected)
  }

  private fun AggregatedPoint.isInTransit(): Boolean =
    TransitRule.isInTransit(activity, activityConfidence, minActivityConfidence)

  private fun AggregatedPoint.movedVertically(previous: AggregatedPoint?): Boolean {
    val here = altitudeMeters ?: return false
    val there = previous?.altitudeMeters ?: return false
    val recent = timestampMillis - previous.timestampMillis <= altitudeWindowMillis
    return recent && abs(here - there) > maxAltitudeDeltaMeters
  }
}

/**
 * "Questa lettura e' nata mentre il telefono viaggiava."
 *
 * Vive fuori da [SampleScreener] perche' la stessa domanda serve alla taratura, che lavora sui
 * campioni grezzi invece che sui punti aggregati: due copie della stessa soglia sono due soglie
 * che prima o poi divergono.
 */
object TransitRule {

  /** Sotto questa confidenza il riconoscimento attivita' e' un'ipotesi, non un fatto. */
  const val MIN_ACTIVITY_CONFIDENCE: Int = 50

  fun isInTransit(
    activity: ActivityKind,
    activityConfidence: Int?,
    minConfidence: Int = MIN_ACTIVITY_CONFIDENCE,
  ): Boolean {
    val confident = (activityConfidence ?: 0) >= minConfidence
    return confident && (activity == ActivityKind.IN_VEHICLE || activity == ActivityKind.ON_BICYCLE)
  }
}
