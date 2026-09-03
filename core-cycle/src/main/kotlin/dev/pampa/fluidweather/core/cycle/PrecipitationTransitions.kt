package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.model.hourAround
import kotlin.math.abs

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
    val probability = hour.values[FusionVariables.PRECIP_PROBABILITY]?.value
    val amount = hour.values[FusionVariables.PRECIPITATION]?.value
    return (probability != null && probability >= WET_PROBABILITY_PERCENT) ||
      (amount != null && amount >= WET_AMOUNT_MM)
  }

  fun next(hours: List<FusedHour>, nowMillis: Long): PrecipitationTransition? {
    val sorted = hours.sortedBy { it.timestampMillis }
    val current = sorted.firstOrNull { it.timestampMillis <= nowMillis && nowMillis - it.timestampMillis < HOUR_MILLIS }
      ?: sorted.hourAround(nowMillis)
      ?: return null
    val currentlyWet = isWet(current)
    val upcoming = sorted.filter { it.timestampMillis > current.timestampMillis && it.timestampMillis - nowMillis <= LEAD_MILLIS }
    val turn = upcoming.firstOrNull { isWet(it) != currentlyWet } ?: return null
    return PrecipitationTransition(
      kind = if (currentlyWet) TransitionKind.END else TransitionKind.ONSET,
      atMillis = turn.timestampMillis,
      weatherKind = if (currentlyWet) current.kind else turn.kind,
    )
  }

  private const val HOUR_MILLIS = 3_600_000L
}
