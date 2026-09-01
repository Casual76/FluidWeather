package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.testbench.events.EventWindow

/** Tutto quello che un predittore puo' sapere all'istante della previsione. Niente futuro. */
class ReplayState(
  val cleaning: CleaningResult,
  val rainingNow: Boolean?,
  /** Il tasso base di pioggia per finestra, calcolato sull'intero dataset (dichiarato: e' il
   * riferimento minimo da battere, non un concorrente onesto — conosce il proprio futuro). */
  val climatologyRates: Map<String, Double>,
  /** Le feature dello stadio 4, quando il replay le costruisce (nowcast in classifica). */
  val rawFeatures: DoubleArray? = null,
) {
  fun rateFor(window: EventWindow): Double = climatologyRates[window.label] ?: 0.1
}

/**
 * I predittori di riferimento della fase 4: stabiliscono i numeri che il modello vero (fase 5)
 * dovra' battere. Chi non batte la persistenza sul 0-1h o la climatologia sul 3-6h non merita
 * di esistere — e ora c'e' un banco che lo dice.
 */
interface Predictor {
  val name: String
  fun probability(state: ReplayState, window: EventWindow): Double
}

/** Dice sempre il tasso base. Imbattibile in Brier da chi non sa niente, battibile da chi sa. */
class ClimatologyPredictor : Predictor {
  override val name = "climatologia"
  override fun probability(state: ReplayState, window: EventWindow): Double =
    state.rateFor(window)
}

/**
 * "Fra un'ora fara' il tempo che fa adesso." Ridicolmente semplice e notoriamente difficile da
 * battere sull'orizzonte corto: e' il vero avversario del nowcast 0-1h.
 */
class PersistencePredictor : Predictor {
  override val name = "persistenza"
  override fun probability(state: ReplayState, window: EventWindow): Double {
    val raining = state.rainingNow ?: return state.rateFor(window)
    // Non 1 e 0: la pioggia finisce e comincia, e il Brier punisce la spavalderia.
    return if (raining) 0.85 else 0.08
  }
}

/**
 * La saggezza barometrica classica ridotta a regola: tendenza pulita contro le soglie della
 * letteratura (0,53 hPa/h = 1,6/3h cambio di tempo; 1,16 hPa/h = 3,5/3h aria di tempesta).
 * Non e' il motore: e' il suo antenato, messo in classifica per misurare il progresso.
 */
class BarometricRulePredictor : Predictor {
  override val name = "regola-barometrica"
  override fun probability(state: ReplayState, window: EventWindow): Double {
    val rate = state.rateFor(window)
    val trend = state.cleaning.latest?.trendHpaPerHour ?: return rate
    return when {
      trend <= -1.16 -> 0.75
      trend <= -0.53 -> (rate * 2.5).coerceAtMost(0.6)
      trend >= 0.53 -> (rate * 0.4).coerceAtLeast(0.02)
      else -> rate
    }
  }
}

/** Il motore vero (stadi 4-5), in classifica alla pari con i suoi antenati. */
class NowcastV1Predictor(
  private val model: dev.pampa.fluidweather.nowcast.verdict.NowcastModel =
    dev.pampa.fluidweather.nowcast.verdict.NowcastModel.trained(),
) : Predictor {
  override val name = "nowcast-v1"
  override fun probability(state: ReplayState, window: EventWindow): Double {
    val features = state.rawFeatures ?: return state.rateFor(window)
    return model.verdict(features).forWindow(window.label)?.probability ?: state.rateFor(window)
  }
}
