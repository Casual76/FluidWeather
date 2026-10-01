package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.nowcast.truth.RainWindows

/**
 * Le otto variabili orarie di Open-Meteo da cui nasce il contesto del v3, con il loro nome nelle
 * risposte dell'API: e' il modo in cui banco e telefono si dicono "quale colonna" senza passarsi
 * stringhe.
 */
enum class ContextVariable(val openMeteo: String) {
  TEMPERATURE("temperature_2m"),
  RELATIVE_HUMIDITY("relative_humidity_2m"),
  DEW_POINT("dew_point_2m"),
  PRESSURE_MSL("pressure_msl"),
  PRECIPITATION("precipitation"),
  CLOUD_COVER("cloud_cover"),
  WIND_SPEED("wind_speed_10m"),
  WIND_DIRECTION("wind_direction_10m"),
}

/**
 * Da dove si legge uno slot orario: il valore di [ContextVariable] nello slot che si chiude a
 * `slotEndMillis` (lettura **esatta**, ora piena UTC), o null se non c'e'. E' un'interfaccia
 * perche' il test "il contesto non legge mai oltre il riferimento" deve poter *vedere* ogni lettura.
 */
fun interface ContextSlotLookup {
  fun value(slotEndMillis: Long, v: ContextVariable): Double?
}

/**
 * Come si legge il contesto dei provider a un istante di scarico: **una** definizione, dentro
 * `:nowcast`, che il telefono (sul pacchetto best_match con `past_hours=6`) e il banco (sulla
 * serie stitched) chiamano con lo stesso codice. Se le due strade divergessero, il modello del v3
 * imparerebbe un contesto che il telefono non ha, e nessun gate lo direbbe.
 *
 * **Solo slot chiusi.** S = l'ultimo slot orario chiuso a `fetchedAtMillis`
 * ([RainWindows.lastClosedSlotEnd]: allo scoccare dell'ora lo slot che si chiude in quell'istante).
 * Nessuna lettura oltre S, mai: niente "riga piu' vicina entro novanta minuti" (`ForecastBundle.at`),
 * che poteva pescare un valore istantaneo di mezz'ora nel futuro dello scarico — una previsione
 * spacciata per osservazione. La precondizione e' che `fetchedAtMillis` sia l'istante vero dello
 * scarico: con un orario di cache piu' recente del dato, S punterebbe a uno slot che al momento
 * del fetch era ancora nel futuro.
 *
 * **Cosa se ne legge.** Dallo slot S: umidita', punto di rugiada, temperatura, nuvole, vento,
 * pressione al mare, pioggia dell'ultima ora. Da S - 3 h: direzione del vento, pressione al mare,
 * temperatura, punto di rugiada, nuvole. Da S - k h, k = 0..6: la pioggia ([NowcastContext.rainSlotsMm]).
 * I campi che il v2 gia' leggeva si calcolano **come prima, identici** (il numero del banco v2 non
 * si muove): lo spread di rugiada e' T - Td di S se ci sono entrambi, la pioggia delle ultime tre ore
 * e' la somma degli slot S, S-1h, S-2h che ci sono (nessuno = null).
 *
 * **Il contratto del telefono (lo implementa P2, qui si congela).**
 * - `ForecastBundle.toContextAt(fetchedAtMillis)` = `contextAt(fetchedAtMillis)` con un lookup sul
 *   pacchetto best_match richiesto con `past_hours=6` (righe S-6h..S piu' le previsioni): per
 *   **timestamp esatto** (`points.associateBy { it.timestampMillis }`), mai `at()` con lo
 *   snapping a +-90 minuti. Mapping: `temperatureC`, `relativeHumidityPercent`, `dewPointC`,
 *   `pressureMslHpa`, `precipitationMm`, `cloudCoverPercent`, `windSpeedKmh`, `windDirectionDeg`
 *   sulle otto [ContextVariable]. Una riga mancante e' null, e le feature danno NaN.
 * - `fetchedAtMillis` e' l'istante **vero** dello scarico (la correzione di `UrlCache`): con
 *   l'orario di una cache piu' recente del dato, S puntera' a uno slot che al fetch era ancora nel
 *   futuro, cioe' a una previsione.
 * - Con il v3 attivo `rainLastHourMm` e' sempre la pioggia dello slot S, mai il quarto d'ora del
 *   minutely: il banco non ha il quarto d'ora, quindi il modello non puo' essere addestrato sul suo
 *   valore. I quarti d'ora possono alimentare solo la `RainObservation`.
 * - Un contesto di un altro posto e' un contesto assente (lo decide [ContextTier]); il contesto STALE
 *   si legge dall'anello dei pacchetti (P2), con il `fetchedAt` del pacchetto stesso.
 */
object ContextSlots {

  private const val HOUR = RainWindows.HOUR_MILLIS

  /** Quanti slot di pioggia porta [NowcastContext.rainSlotsMm]: S, S-1h, ..., S-6h. */
  const val RAIN_SLOTS: Int = 7

  /** Gli slot che "pioggia delle ultime 6 h" richiede tutti presenti (k = 0..5). */
  const val RAIN_6H_SLOTS: Int = 6

  /**
   * Il contesto di uno scarico fatto a [fetchedAtMillis]; null se nello slot S non c'e' niente (come
   * per un pacchetto senza la riga "adesso": si scarta il caso, non si ripiega su uno slot vicino).
   */
  fun contextAt(fetchedAtMillis: Long, lookup: ContextSlotLookup): NowcastContext? {
    val slot = RainWindows.lastClosedSlotEnd(fetchedAtMillis)
    if (ContextVariable.entries.all { lookup.value(slot, it) == null }) return null

    val threeAgo = slot - 3 * HOUR
    val temperature = lookup.value(slot, ContextVariable.TEMPERATURE)
    val dewPoint = lookup.value(slot, ContextVariable.DEW_POINT)
    val dewSpread = if (temperature != null && dewPoint != null) temperature - dewPoint else null

    val rainSlots = List(RAIN_SLOTS) { k -> lookup.value(slot - k * HOUR, ContextVariable.PRECIPITATION) }
    val rainLast3 = rainSlots.take(3).filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    return NowcastContext(
      relativeHumidityPercent = lookup.value(slot, ContextVariable.RELATIVE_HUMIDITY),
      dewPointSpreadC = dewSpread,
      cloudCoverPercent = lookup.value(slot, ContextVariable.CLOUD_COVER),
      windSpeedKmh = lookup.value(slot, ContextVariable.WIND_SPEED),
      windDirectionDeg = lookup.value(slot, ContextVariable.WIND_DIRECTION),
      windDirectionDeg3hAgo = lookup.value(threeAgo, ContextVariable.WIND_DIRECTION),
      rainLastHourMm = rainSlots[0],
      rainLast3hMm = rainLast3,
      pressureMslHpa = lookup.value(slot, ContextVariable.PRESSURE_MSL),
      pressureMsl3hAgoHpa = lookup.value(threeAgo, ContextVariable.PRESSURE_MSL),
      slotEndMillis = slot,
      temperatureC = temperature,
      temperature3hAgoC = lookup.value(threeAgo, ContextVariable.TEMPERATURE),
      dewPointC = dewPoint,
      dewPoint3hAgoC = lookup.value(threeAgo, ContextVariable.DEW_POINT),
      cloudCover3hAgoPercent = lookup.value(threeAgo, ContextVariable.CLOUD_COVER),
      rainSlotsMm = rainSlots,
    )
  }
}
