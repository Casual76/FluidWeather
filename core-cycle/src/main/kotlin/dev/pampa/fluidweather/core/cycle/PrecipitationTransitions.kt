package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.model.precipitationAt
import dev.pampa.fluidweather.core.model.precipitationStartingAt

enum class TransitionKind { ONSET, END }

/** Un cambio di stato imminente: quando (l'ora fusa in cui avviene) e di che precipitazione. */
data class PrecipitationTransition(
  val kind: TransitionKind,
  val atMillis: Long,
  val weatherKind: WeatherKind?,
)

/**
 * "Inizio/fine precipitazione" letto dalle ore fuse: un'ora e' BAGNATA se la probabilita' e'
 * alta o se c'e' accumulo previsto; il passaggio asciutto->bagnato entro l'anticipo e' un
 * inizio, bagnato->asciutto una fine. Pura: la politica delle notifiche la chiama con la
 * memoria di cio' che ha gia' detto.
 */
object PrecipitationTransitions {

  /** Sopra questa probabilita' l'ora conta come pioggia anche senza accumulo dichiarato. */
  const val WET_PROBABILITY_PERCENT: Double = 60.0

  /** Sotto 0,2 mm/h e' umidita', non pioggia: e' la soglia "misurabile" dei pluviometri. */
  const val WET_AMOUNT_MM: Double = 0.2

  /** Quanto avanti si guarda: un'ora e un quarto, l'ora prossima piu' il margine dell'ora in corso. */
  const val LEAD_MILLIS: Long = 75 * 60_000L

  fun isWet(hour: FusedHour): Boolean {
    return state(hour) == State.WET
  }

  private enum class State { DRY, WET, UNKNOWN }

  private fun state(hour: FusedHour): State {
    val probability = hour.values[FusionVariables.PRECIP_PROBABILITY]?.value
      ?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val amount = hour.values[FusionVariables.PRECIPITATION]?.value
      ?.takeIf { it.isFinite() && it >= 0.0 }
    return when {
      probability == null && amount == null -> State.UNKNOWN
      probability != null && probability >= WET_PROBABILITY_PERCENT ||
        amount != null && amount >= WET_AMOUNT_MM -> State.WET
      else -> State.DRY
    }
  }

  fun next(hours: List<FusedHour>, nowMillis: Long): PrecipitationTransition? {
    // Rain is an accumulation over an interval. Instantaneous temperature timestamps cannot
    // stand in for its start; normalized provider products are anchored at the interval end.
    var previous = hours.precipitationAt(nowMillis) ?: return null
    var previousState = state(previous)
    if (previousState == State.UNKNOWN) return null
    // Missing hours and missing fields are not dry weather. Never bridge either gap.
    while (previous.precipitationInterval!!.endMillis - nowMillis <= LEAD_MILLIS) {
      val next = hours.precipitationStartingAt(previous.precipitationInterval!!.endMillis) ?: return null
      val interval = next.precipitationInterval!!
      if (interval.startMillis - nowMillis > LEAD_MILLIS) break
      if (interval.startMillis != previous.precipitationInterval!!.endMillis) return null
      val nextState = state(next)
      if (nextState == State.UNKNOWN) return null
      if (nextState != previousState) return PrecipitationTransition(
        kind = if (previousState == State.WET) TransitionKind.END else TransitionKind.ONSET,
        atMillis = interval.startMillis,
        weatherKind = if (previousState == State.WET) previous.kind else next.kind,
      )
      previous = next
      previousState = nextState
    }
    return null
  }

}
