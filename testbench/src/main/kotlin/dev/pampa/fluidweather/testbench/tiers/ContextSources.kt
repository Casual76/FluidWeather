package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.features.ContextSlots
import dev.pampa.fluidweather.nowcast.features.NowcastContext
import dev.pampa.fluidweather.testbench.data.HourlySeries

/**
 * Da dove si legge uno slot orario: il valore di [variable] nello slot che si chiude a
 * [slotEndMillis], o null se non c'e'. E' un'interfaccia e non la serie nuda perche' il test
 * "il contesto non legge mai oltre il riferimento" deve poter *vedere* ogni lettura.
 */
fun interface SlotReader {
  fun value(slotEndMillis: Long, variable: String): Double?
}

/** Lo [SlotReader] di una serie oraria normalizzata: lettura esatta, una variabile assente e' "niente". */
class HourlySeriesReader(private val series: HourlySeries) : SlotReader {
  override fun value(slotEndMillis: Long, variable: String): Double? =
    if (variable in series.variables) series.at(slotEndMillis, variable) else null
}

/**
 * Il contesto letto "com'era al fetch", piu' lo slot da cui viene: serve a dirlo nei test e nella
 * diagnosi, ed e' lo slot che la persistenza legge per calcolare il ritardo.
 */
class AsOfContext(
  val context: NowcastContext,
  /** La fine dello slot "adesso" del contesto: l'ultimo chiuso a `ref`. */
  val slotEndMillis: Long,
)

/**
 * Il contesto sinottico com'era all'istante di un fetch, dalla serie stitched di best_match.
 *
 * E' la stessa forma di `ForecastBundle.toContext` del telefono (adesso, tre ore fa, pioggia
 * recente), con una differenza che e' tutto il punto: **solo slot chiusi**. `toContext` legge
 * `at(now)`, cioe' la riga piu' vicina entro novanta minuti, e puo' pescare un valore istantaneo
 * di mezz'ora nel futuro del fetch — una previsione spacciata per osservazione. Qui "adesso" e'
 * l'ultimo slot orario che si e' chiuso a `ref` o prima
 * ([dev.pampa.fluidweather.nowcast.truth.RainWindows.lastClosedSlotEnd]); nessun valore di uno
 * slot che si chiude dopo `ref` viene mai letto.
 *
 * **La definizione sta in `:nowcast`.** Il mapping (quali slot, quali campi) e' di
 * [ContextSlots.contextAt], la stessa funzione che il telefono chiama sul suo pacchetto best_match
 * con `past_hours=6`: qui ci si limita a dirle dove leggere. Cosi' le feature 20-26 del v3 sono
 * calcolate dallo stesso codice sul banco e sul telefono, e il v2 legge gli stessi campi di sempre.
 *
 * Il mapping, slot `S` = ultimo chiuso a `ref`:
 * - umidita', punto di rugiada, nuvole, vento, pressione al mare: la riga `S` (valori istantanei
 *   all'ora `S`, che e' <= `ref`);
 * - pioggia dell'ultima ora: lo slot `S` (il millimetraggio di (S-1h, S]); pioggia delle ultime tre
 *   ore: la somma degli slot `S`, `S-1h`, `S-2h` che ci sono (nessuno = nulla), come `toContext`;
 * - tre ore fa: la riga `S-3h` (direzione del vento, pressione al mare, temperatura, punto di
 *   rugiada, nuvole);
 * - le sette piogge `S..S-6h` del v3.
 *
 * Senza la riga `S` il contesto non c'e' (null), come per `toContext` quando `at(now)` e' vuoto:
 * il banco scarta il caso invece di ripiegare su ERA5, che sul telefono non esiste.
 */
object ContextSources {

  /** Il contesto di un fetch fatto a [refMillis]; null se lo slot "adesso" non ha dati. */
  fun contextAsOf(reader: SlotReader, refMillis: Long): AsOfContext? {
    val context = ContextSlots.contextAt(refMillis) { slotEndMillis, variable ->
      reader.value(slotEndMillis, variable.openMeteo)
    } ?: return null
    return AsOfContext(context, context.slotEndMillis!!)
  }

  /** Il contesto di un fetch fatto [ageMillis] prima dell'emissione [issueMillis]. */
  fun contextAtAge(reader: SlotReader, issueMillis: Long, ageMillis: Long): AsOfContext? =
    contextAsOf(reader, issueMillis - ageMillis)
}
