package dev.pampa.fluidweather.core.ai.radar

import dev.pampa.fluidweather.core.weather.RainObservations
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import kotlin.math.pow

/**
 * Il radar come osservazione per il verdetto, non solo come immagine per la schermata.
 *
 * RainViewer l'app lo scarica gia' per il radar e per l'assistente; qui la stessa lettura serve
 * a rispondere alla domanda piu' semplice che ci sia — sta piovendo *qui*, adesso? — che il
 * nowcast barometrico, da solo, non puo' porsi.
 *
 * Costa tile scaricate, quindi non si chiama a ogni giro: chi lo usa decide quando (vedi
 * `NowcastUseCase.worthLookingAtRadar`).
 */
object RadarObservations {

  suspend fun of(sampler: RadarSampler, latitude: Double, longitude: Double): RainObservation? {
    val sample = runCatching { sampler.sample(latitude, longitude) }.getOrNull() ?: return null
    val reading = (sample as? RadarSample.Ok)?.reading ?: return null
    // Un radar con poca copertura o con un fotogramma vecchio non e' un'osservazione: e'
    // un'ipotesi con una bella grafica. Meglio tacere che alzare un verdetto su un'immagine.
    if (reading.confidence < MIN_CONFIDENCE) return null
    if (!reading.rainingNow) return RainObservation.dry(RainObservations.SOURCE_RADAR)
    return RainObservation.raining(millimetresPerHour(reading.atPointDbz), RainObservations.SOURCE_RADAR)
  }

  /**
   * Da dBZ a millimetri all'ora con la relazione Z-R di Marshall-Palmer (Z = 200 R^1,6), quella
   * che i radar meteorologici usano per default:
   *
   *     R = (10^(dBZ/10) / 200)^(1/1,6)
   *
   * E' una stima, non una misura: la stessa riflettivita' puo' venire da pioggia fitta e fine o
   * da gocce rade e grosse. Serve a distinguere la pioggerella dal rovescio, non a misurare.
   */
  fun millimetresPerHour(dbz: Int): Double = (10.0.pow(dbz / 10.0) / 200.0).pow(1.0 / 1.6)

  /** Sotto questa confidenza il campionamento del radar non ha visto abbastanza per parlare. */
  const val MIN_CONFIDENCE = 0.4
}
