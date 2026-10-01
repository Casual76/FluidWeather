package dev.pampa.fluidweather.nowcast.truth

import java.util.TreeMap

/**
 * Il pannello dei giudici: da dove viene la verita' della pioggia, per tutti.
 *
 * **Perche' un pannello fisso.** La verita' di prima era la mediana delle precipitazioni degli
 * stessi provider in classifica, presa dai fetch del giro corrente: ogni provider sedeva nella
 * giuria che lo giudicava (spesso con la stessa corsa che aveva prodotto la sua PoP), e bastava
 * togliere un votante per spostare il tasso base di circa il 30%. Due righe della classifica non
 * erano giudicate sulla stessa domanda, e il barometro — che in giuria non c'e' — partiva
 * sconfitto. Qui la giuria e' scritta nel codice, e' la stessa per ogni riga, e **nessun
 * provider in classifica vota mai**: provider, barometro, climatologia e "sempre 0%" sono
 * giudicati dallo stesso numero. L'unica voce che puo' correggere il pannello e' l'occhio
 * dell'utente (vedi [RainWindows]), che e' una misura e non una previsione.
 *
 * **Perche' questi tre modelli.** Météo-France (ARPEGE/AROME), UK Met Office (UM) ed Environment
 * Canada (GEM): tre centri indipendenti, nessuno delle famiglie ICON, ECMWF o GFS che fanno la
 * classifica (e che dentro best_match si ritrovano mescolate) — errori correlati coi concorrenti
 * sarebbero un giudice che tifa. Nessuno dei tre deve avere una riga nella classifica pioggia
 * (AROME e' fra i fornitori dell'app per le altre variabili, ma la classifica pioggia vive di
 * PoP, e le PoP vengono dagli ensemble delle famiglie in gara). Tutti e
 * tre sono globali (il banco giudica anche Singapore, Denver, Buenos Aires e Tokyo) e tutti e
 * tre hanno precipitazione oraria non nulla nell'archivio historical-forecast dal 2022-11-24:
 * una data d'inizio comune, [AVAILABLE_FROM_MILLIS], invece di un pannello che cambia
 * composizione negli anni. Alla pre-verifica di Sesto (anno di test 2025-09 .. 2026-08) la loro
 * mediana dava tassi base 0,089/0,119/0,135 contro ERA5 0,114/0,158/0,187, d'accordo con ERA5
 * nel 92% delle finestre 1-3h e con CSI 0,54 contro lo 0,44 del vecchio consenso — che era molto
 * piu' asciutto (0,059/0,082/0,098), perche' ICON e best_match lo sono. Resta piu' asciutto della
 * rianalisi, ed e' dichiarato: non e' "la pioggia vera", e' la stessa domanda per tutti.
 *
 * Se un giorno uno dei giudici entrasse in classifica (per esempio AROME con una PoP), o fosse
 * usato come ingresso di una riga in classifica, il pannello va cambiato e [VERSION] alzata:
 * un giudice non gareggia.
 *
 * **Perche' la mediana, e perche' tutti presenti.** La mediana di tre ignora il modello che da
 * solo inventa un temporale (o se lo perde). Il quorum e' l'intero pannello: una mediana di due e'
 * una media, e un giudice che cambia natura slot per slot secondo chi ha risposto e' un giudice
 * diverso senza che nessuno lo sappia. Uno slot senza tutti i giudici resta senza verita', e la
 * finestra che lo contiene resta ingiudicata. (Conseguenza nota: fuori dall'Europa
 * meteofrance_seamless ha buchi fino al 2023-12-27, e quelle ore a banco non si giudicano.) GEM e'
 * triorario nativo e Open-Meteo spalma l'accumulo sulle tre ore: la mediana e la somma di
 * finestra ne attenuano l'effetto, ed e' uno dei motivi per cui la soglia sta sulla finestra.
 *
 * **Perche' 24 ore di finalita'.** L'archivio historical-forecast incolla le prime ore di ogni
 * corsa, e fino a quando la corsa successiva non e' pubblicata le ore recenti sono ancora
 * previsioni: UKMO corre ogni 6 ore con circa 9 ore di ritardo, GEM ogni 12, ARPEGE ogni 6 con
 * circa 4 ore e mezza. Giudicare prima vorrebbe dire giudicare con una previsione — e magari con
 * la stessa corsa che un provider ha appena usato. [FINALITY_MILLIS] dalla fine dell'ultimo slot
 * della finestra copre il ritardo del piu' lento con margine; la sonda di stabilita' del banco lo
 * misura, e se il margine risultasse stretto si alza qui, per tutti.
 *
 * Dati: Weather data by Open-Meteo.com (CC BY 4.0).
 */
object TruthPanel {

  /** La versione delle regole di verita': finisce nell'etichetta di ogni verifica. */
  const val VERSION: String = "panel-1"

  /** I giudici, per nome del modello Open-Meteo. L'ordine non conta per la mediana. */
  val MODELS: List<String> = listOf("meteofrance_seamless", "ukmo_seamless", "gem_seamless")

  /** Quanti giudici servono per uno slot: tutti. */
  val QUORUM: Int = MODELS.size

  /** Il parametro `models=` di una richiesta Open-Meteo per l'intero pannello. */
  val MODELS_PARAMETER: String = MODELS.joinToString(",")

  /** Da quando i tre giudici hanno insieme la precipitazione oraria: 2022-11-24T00:00Z. */
  const val AVAILABLE_FROM_MILLIS: Long = 1_669_248_000_000L

  /** Quanto aspettare, dalla fine dell'ultimo slot di una finestra, prima di giudicarla. */
  const val FINALITY_MILLIS: Long = 24 * 3_600_000L

  const val ATTRIBUTION: String = "Weather data by Open-Meteo.com (CC BY 4.0)"

  /**
   * Il valore di verita' di uno slot dalle opinioni dei modelli (mm): la mediana del pannello se
   * tutti i giudici ci sono, altrimenti null. Le voci di modelli fuori dal pannello si ignorano:
   * chi passa una risposta con piu' modelli non allarga la giuria per sbaglio.
   */
  fun slotValue(valuesByModel: Map<String, Double?>): Double? {
    val values = MODELS.map { model ->
      valuesByModel[model]?.takeUnless { it.isNaN() } ?: return null
    }
    return median(values)
  }

  /**
   * La serie di verita' (fine dello slot -> mm) dalle serie dei singoli giudici: contiene solo gli
   * slot in cui ha risposto tutto il pannello. Ordinata nel tempo.
   */
  fun combine(seriesByModel: Map<String, Map<Long, Double>>): Map<Long, Double> {
    val members = MODELS.map { seriesByModel[it] ?: return emptyMap() }
    val truth = TreeMap<Long, Double>()
    for ((slotEnd, _) in members.first()) {
      val value = slotValue(MODELS.indices.associate { MODELS[it] to members[it][slotEnd] })
      if (value != null) truth[slotEnd] = value
    }
    return truth
  }

  /** La finestra che si chiude a [windowLastSlotEnd] si puo' giudicare adesso? */
  fun isFinal(windowLastSlotEnd: Long, nowMillis: Long): Boolean =
    RainWindows.isFinal(windowLastSlotEnd, nowMillis, FINALITY_MILLIS)

  /** Mediana classica: il valore di mezzo, o la media dei due di mezzo. NaN su lista vuota. */
  fun median(values: List<Double>): Double {
    if (values.isEmpty()) return Double.NaN
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
  }
}
