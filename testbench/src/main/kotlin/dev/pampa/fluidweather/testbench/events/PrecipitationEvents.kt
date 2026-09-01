package dev.pampa.fluidweather.testbench.events

import dev.pampa.fluidweather.testbench.data.HourlyRecord

/** Una finestra di previsione: "pioggia fra [fromHours] e [toHours] ore da adesso". */
data class EventWindow(val fromHours: Int, val toHours: Int) {
  val label: String get() = "$fromHours-${toHours}h"

  companion object {
    /** Le tre finestre del piano: il widget mostra 0-3, la pagina arriva a 6. */
    val Standard = listOf(EventWindow(0, 1), EventWindow(1, 3), EventWindow(3, 6))
  }
}

/**
 * La verita' di riferimento: c'e' stata precipitazione nella finestra?
 *
 * La soglia e' 0,2 mm accumulati: sotto, gli archivi orari riportano piovaschi da tracce che
 * nessuno percepisce come "pioggia" — contarli come eventi renderebbe il banco piu' facile da
 * battere e piu' bugiardo. I record orari valgono per l'ora *precedente* al loro timestamp
 * (convenzione degli archivi): la finestra (t+a, t+b] somma i record con timestamp in
 * (t+a, t+b].
 */
class PrecipitationEvents(
  records: List<HourlyRecord>,
  private val thresholdMm: Double = 0.2,
) {

  private val ordered = records.sortedBy { it.timestampMillis }
  private val timestamps = ordered.map { it.timestampMillis }

  fun occurred(nowMillis: Long, window: EventWindow): Boolean? {
    val from = nowMillis + window.fromHours * 3_600_000L
    val to = nowMillis + window.toHours * 3_600_000L
    var index = timestamps.binarySearch(from + 1).let { if (it < 0) -it - 1 else it }
    var accumulated = 0.0
    var covered = 0
    while (index < ordered.size && timestamps[index] <= to) {
      val precip = ordered[index].precipitationMm ?: return null
      accumulated += precip
      covered++
      index++
    }
    // Finestra scoperta (fine del dataset): non si giudica cio' che non si conosce.
    if (covered < window.toHours - window.fromHours) return null
    return accumulated >= thresholdMm
  }

  /** Sta piovendo adesso? (l'ora appena conclusa) — serve al predittore di persistenza. */
  fun rainingAt(nowMillis: Long): Boolean? {
    val index = timestamps.binarySearch(nowMillis)
    if (index < 0) return null
    return (ordered[index].precipitationMm ?: return null) >= thresholdMm
  }
}
