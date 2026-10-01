package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows

/**
 * Quale ora copre la probabilita' di pioggia di un provider, rispetto all'istante con cui la
 * etichetta. Non e' un dettaglio: leggere l'ora sbagliata vuol dire dare al provider il merito (o
 * la colpa) di un'ora che non ha previsto.
 */
enum class PopConvention {
  /** Il punto delle T copre (T-1h, T], come gli slot della verita': la famiglia Open-Meteo. */
  HOUR_ENDING,

  /** Il periodo che *comincia* alle T copre (T, T+1h]: NWS etichetta per inizio del periodo. */
  HOUR_STARTING,

  /** Passi di tre ore che si chiudono all'istante del punto: OpenWeatherMap (`rain.3h` = ultime 3 ore). */
  THREE_HOUR_ENDING,

  /** Nessuna probabilita' di pioggia: MET Norway e Meteosource non la danno, e non si inventa. */
  NONE,
}

/**
 * La probabilita' di pioggia di un provider su una finestra dell'evento.
 *
 * La convenzione delle app meteo per "la probabilita' nel periodo" e' il massimo delle orarie, e la
 * si dichiara. Prima pero' le ore erano "adesso + 1..b ore" pescate con `ForecastBundle.at`, che
 * accetta il punto piu' vicino entro novanta minuti: la finestra del provider scivolava di mezz'ora
 * rispetto a quella con cui veniva giudicato. Qui si leggono **esattamente** gli slot di
 * [RainWindows] — la stessa ancora (emissione arrotondata per eccesso), le stesse fini — e se uno
 * slot manca non c'e' probabilita': un massimo su tre ore di cui una e' un buco non e' il massimo
 * sulla finestra.
 */
object ProviderWindowPop {

  /**
   * I giudici del [dev.pampa.fluidweather.nowcast.truth.TruthPanel] che sono anche provider
   * dell'app: AROME e' `meteofrance_seamless`. Mai in classifica pioggia — un giudice non gareggia.
   */
  val JUDGE_PROVIDER_IDS: Set<String> = setOf(ProviderRegistry.OPEN_METEO_AROME)

  fun conventionOf(providerId: String): PopConvention = when {
    providerId.startsWith(ProviderRegistry.OPEN_METEO) -> PopConvention.HOUR_ENDING
    providerId == ProviderRegistry.NWS -> PopConvention.HOUR_STARTING
    providerId == ProviderRegistry.OPENWEATHERMAP -> PopConvention.THREE_HOUR_ENDING
    else -> PopConvention.NONE
  }

  /**
   * Il massimo di PoP/100 sugli slot esatti della finestra di un'emissione a [issuedAtMillis], in
   * [0, 1]. Null se la convenzione e' [PopConvention.NONE] o se anche un solo slot non ha PoP.
   */
  fun of(bundle: ForecastBundle, convention: PopConvention, issuedAtMillis: Long, window: RainWindow): Double? {
    if (convention == PopConvention.NONE) return null
    var highest = Double.NEGATIVE_INFINITY
    for (slotEnd in RainWindows.slotEnds(RainWindows.anchorOf(issuedAtMillis), window)) {
      val pop = pointFor(bundle, convention, slotEnd)
        ?.precipitationProbabilityPercent
        ?.takeUnless { it.isNaN() }
        ?: return null
      highest = maxOf(highest, pop)
    }
    return (highest / 100.0).coerceIn(0.0, 1.0)
  }

  /** Il punto del bundle che copre lo slot (fine-1h, fine]. */
  private fun pointFor(bundle: ForecastBundle, convention: PopConvention, slotEnd: Long): HourlyPoint? = when (convention) {
    PopConvention.HOUR_ENDING -> bundle.hourly.firstOrNull { it.timestampMillis == slotEnd }
    PopConvention.HOUR_STARTING -> bundle.hourly.firstOrNull { it.timestampMillis == slotEnd - RainWindows.HOUR_MILLIS }
    // Il passo di tre ore che si chiude fra la fine dello slot e due ore dopo contiene lo slot.
    PopConvention.THREE_HOUR_ENDING -> bundle.hourly
      .filter { it.timestampMillis in slotEnd..slotEnd + 2 * RainWindows.HOUR_MILLIS }
      .minByOrNull { it.timestampMillis }
    PopConvention.NONE -> null
  }
}
