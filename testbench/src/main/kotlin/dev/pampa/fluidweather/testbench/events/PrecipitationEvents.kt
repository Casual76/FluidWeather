package dev.pampa.fluidweather.testbench.events

import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.data.HourlyRecord

/** Una finestra di previsione: "pioggia fra [fromHours] e [toHours] ore da adesso". */
data class EventWindow(val fromHours: Int, val toHours: Int) {
  val label: String get() = "$fromHours-${toHours}h"

  /** La stessa finestra nel contratto condiviso col telefono. */
  fun toRainWindow(): RainWindow = RainWindow(fromHours, toHours)

  companion object {
    /** Le tre finestre del piano, prese dal contratto condiviso: il widget mostra 0-3, la pagina arriva a 6. */
    val Standard = RainWindows.ALL.map { EventWindow(it.fromHours, it.toHours) }
  }
}

/**
 * La verita' di riferimento del banco: c'e' stata precipitazione nella finestra?
 *
 * Non e' piu' una definizione propria: delega a [RainWindows], la definizione unica dell'evento
 * che usa anche il telefono (soglia 0,2 mm sull'accumulo di finestra, slot orari (T-1h, T],
 * finestra ingiudicabile se manca anche un solo slot). I record orari valgono per l'ora
 * *precedente* al loro timestamp (convenzione degli archivi): la finestra (a, b) da t somma i record
 * che si chiudono da t+(a+1)h a t+b h.
 *
 * L'unica differenza e' l'ancora: [RainWindows.evaluate] arrotonda l'emissione per eccesso all'ora
 * piena, qui si ancora all'istante dato ([RainWindows.outcomeFromAnchor]). Il banco emette sempre
 * sugli istanti dei suoi record, che negli archivi Open-Meteo sono ore piene: li' le due cose
 * coincidono, e un'emissione allo scoccare dell'ora da' lo stesso esito sul banco e sul telefono.
 * Ancorare all'istante tiene in piedi anche le serie su griglie orarie sfasate (i test).
 */
class PrecipitationEvents(
  records: List<HourlyRecord>,
  private val thresholdMm: Double = RainWindows.WET_THRESHOLD_MM,
) {

  /** Fine dello slot -> mm (null = record presente ma senza precipitazione: un buco come un altro). */
  private val precipitationBySlotEnd: Map<Long, Double?> =
    records.associate { it.timestampMillis to it.precipitationMm }

  private val slotMm: (Long) -> Double? = { precipitationBySlotEnd[it] }

  fun occurred(nowMillis: Long, window: EventWindow): Boolean? =
    RainWindows.outcomeFromAnchor(nowMillis, window.toRainWindow(), slotMm, thresholdMm = thresholdMm)

  /**
   * Sta piovendo adesso? L'ora appena conclusa, cioe' lo slot che si chiude a [nowMillis], con la
   * stessa soglia dell'evento — serve al predittore di persistenza. Fra due record non si inventa.
   */
  fun rainingAt(nowMillis: Long): Boolean? =
    slotMm(nowMillis)?.let { RainWindows.isWet(it, thresholdMm) }
}
