package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.data.HourlySeries
import dev.pampa.fluidweather.testbench.data.StationDataset

/**
 * Con quale verita' si giudica. [PANEL] e' la primaria: la mediana dei tre giudici del
 * [TruthPanel], la stessa domanda per ogni riga e la stessa che giudichera' il telefono. [ERA5]
 * e' la secondaria, solo da riportare: e' la "pioggia vera" della rianalisi, piu' bagnata del
 * pannello, e le baseline (tarate sul pannello) ci arrivano fuori taratura per costruzione.
 */
enum class TruthKind(val label: String) {
  PANEL("PANNELLO"),
  ERA5("ERA5"),
}

/**
 * Una serie di verita' (fine dello slot -> mm) con il modo di giudicare una finestra: sempre
 * attraverso [RainWindows], la definizione unica dell'evento. Un buco rende la finestra
 * ingiudicabile (null), mai "asciutta".
 */
class TruthSeries(val kind: TruthKind, private val slots: Map<Long, Double>) {

  private val lookup: (Long) -> Double? = { slots[it] }

  val size: Int get() = slots.size

  /** Il millimetraggio dello slot che si chiude a [slotEndMillis], o null. */
  fun slotMm(slotEndMillis: Long): Double? = slots[slotEndMillis]

  /** L'esito della finestra di un'emissione a [issueMillis] (ancora = emissione per eccesso); null se ingiudicabile. */
  fun outcome(issueMillis: Long, window: RainWindow): Boolean? = RainWindows.outcome(issueMillis, window, lookup)

  /** Gli slot con fine in [fromMillis, untilMillis]: e' la storia che si da' alle baseline, mai un millimetro dopo. */
  fun slice(fromMillis: Long, untilMillis: Long): Map<Long, Double> {
    val part = HashMap<Long, Double>()
    for ((end, mm) in slots) if (end in fromMillis..untilMillis) part[end] = mm
    return part
  }

  /** Tutti gli slot: per chi deve ricomporre la serie. */
  fun asMap(): Map<Long, Double> = slots

  companion object {

    /** La verita' del pannello dalle serie dei giudici (colonna `precipitation`): quorum = tutti, come [TruthPanel.combine]. */
    fun panel(membersByModel: Map<String, HourlySeries>): TruthSeries {
      val perModel = membersByModel.mapValues { (_, series) -> precipitationOf(series) }
      return TruthSeries(TruthKind.PANEL, TruthPanel.combine(perModel))
    }

    /** La pioggia di ERA5 del dataset del banco (un record per ora, chiude lo slot); le ore senza valore restano buchi. */
    fun era5(dataset: StationDataset): TruthSeries {
      val slots = HashMap<Long, Double>(dataset.records.size * 2)
      for (record in dataset.records) record.precipitationMm?.let { slots[record.timestampMillis] = it }
      return TruthSeries(TruthKind.ERA5, slots)
    }

    /** La colonna `precipitation` di una serie come mappa istante -> mm, senza i vuoti. */
    fun precipitationOf(series: HourlySeries): Map<Long, Double> = columnMap(series, "precipitation")

    /** Una colonna come mappa istante -> valore, senza i vuoti; mappa vuota se la serie non ha la colonna. */
    fun columnMap(series: HourlySeries, variable: String): Map<Long, Double> {
      if (variable !in series.variables) return emptyMap()
      val column = series.column(variable)
      val map = HashMap<Long, Double>(series.size * 2)
      for (i in 0 until series.size) if (!column[i].isNaN()) map[series.timeAt(i)] = column[i]
      return map
    }
  }
}
