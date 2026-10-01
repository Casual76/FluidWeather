package dev.pampa.fluidweather.nowcast.climatology

import dev.pampa.fluidweather.nowcast.tide.SolarTime
import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.ln

/**
 * I conteggi di una cella: quante emissioni della storia ci sono cadute, e in quante la finestra
 * e' finita bagnata. Si conservano i conteggi e non i tassi: sono interi (niente virgola mobile
 * che cambia all'andata e al ritorno dalla memoria del telefono) e dicono anche quanto fidarsi.
 */
data class RateCell(val wet: Int, val total: Int) {
  init {
    require(total >= 0 && wet in 0..total) { "cella impossibile: $wet/$total" }
  }

  /**
   * Il tasso della cella ristretto verso [prior] con [pseudoCounts] pseudo-conteggi: con pochi
   * casi parla il prior, con molti parlano i casi. Senza casi e' esattamente il prior — anche con
   * zero pseudo-conteggi, dove la formula farebbe 0/0 e un NaN entrerebbe in un modello come feature.
   */
  fun shrunkToward(prior: Double, pseudoCounts: Int): Double {
    require(pseudoCounts >= 0) { "pseudo-conteggi negativi: $pseudoCounts" }
    if (total + pseudoCounts == 0) return prior
    return (wet + pseudoCounts * prior) / (total + pseudoCounts)
  }

  internal fun counting(wetNow: Boolean): RateCell = RateCell(wet + if (wetNow) 1 else 0, total + 1)

  internal fun encode(): String = "$wet/$total"

  internal companion object {
    val EMPTY = RateCell(0, 0)

    fun decode(text: String): RateCell? {
      val slash = text.indexOf('/')
      if (slash <= 0) return null
      val wet = text.substring(0, slash).toIntOrNull() ?: return null
      val total = text.substring(slash + 1).toIntOrNull() ?: return null
      if (total < 0 || wet !in 0..total) return null
      return RateCell(wet, total)
    }
  }
}

/**
 * La climatologia locale della pioggia, finestra per finestra: quanto spesso, *qui*, in questa
 * stagione e a quest'ora del sole, la finestra 0-1h (1-3h, 3-6h) finisce bagnata.
 *
 * E' il primo avversario del nucleo indipendente (D1: nessun livello del barometro vale qualcosa
 * se non batte la climatologia del posto) e la sua prima feature: un modello globale che sa quanto
 * piove a Sesto in un pomeriggio d'estate non deve spendere pesi per impararlo da capo in ogni
 * posto. Prima la "climatologia" del banco era il tasso sull'intero dataset — anche sul futuro
 * che doveva prevedere — e sul telefono non esisteva.
 *
 * **Come si costruisce.** Da una serie oraria di verita' (fine dello slot -> mm, di norma la
 * mediana del [dev.pampa.fluidweather.nowcast.truth.TruthPanel]) si simula un'emissione a ogni ora
 * piena della storia e la si giudica con [RainWindows] — la stessa definizione dell'evento che
 * giudichera' il verdetto, non una sua parente. Entrano solo le emissioni con la finestra completa:
 * una finestra coi buchi non e' ne' bagnata ne' asciutta. Per un fit fuori campione basta passare
 * solo la storia di addestramento: le finestre che sconfinano restano incomplete e non entrano, e
 * nessun millimetro del periodo di prova arriva nei conteggi.
 *
 * **Le celle.** Stagione meteorologica (DJF/MAM/JJA/SON, dal mese UTC) per sei ore di tempo
 * solare medio (UTC + longitudine/15): sedici celle per finestra. La stagione perche' il regime
 * delle piogge cambia coi mesi; l'ora solare e non quella del fuso perche' la convezione del
 * pomeriggio segue il sole. Sei ore e non una: con due anni di storia una cella oraria per
 * stagione avrebbe meno di duecento casi, e per una finestra che si bagna una volta su dieci meno di
 * venti eventi — un tasso che balla; sei ore bastano a separare il pomeriggio dalla notte.
 * La cella si sceglie dall'ancora della finestra (l'emissione arrotondata per eccesso), cioe' dallo
 * stesso istante che la storia ha usato.
 *
 * **Il restringimento.** Ogni cella e' tirata verso il tasso complessivo della sua finestra con
 * [SHRINKAGE_PSEUDO_COUNTS] pseudo-conteggi: una cella con pochi casi (una stagione secca in un
 * posto piovoso, la notte d'inverno con due anni di storia) racconta soprattutto il tasso
 * complessivo, una cella con centinaia di casi racconta se stessa. Quaranta e' meno di un decimo dei
 * casi che una cella raccoglie in un anno (circa 550): il prior pesa solo quando la cella e'
 * davvero magra.
 *
 * **Sul telefono.** Si costruisce da una richiesta historical-forecast del pannello per la cella
 * di casa e si conserva con [encode]: una riga di testo con i conteggi e la longitudine.
 */
class WindowClimatology internal constructor(
  /** La longitudine del posto: serve all'ora solare, quindi viaggia coi conteggi. */
  val longitudeDeg: Double,
  val pseudoCounts: Int,
  /** etichetta della finestra -> [CELLS] celle, indice = stagione * [SOLAR_BINS] + fascia solare. */
  private val cells: Map<String, List<RateCell>>,
) {

  /** Le finestre che questa climatologia conosce, nell'ordine in cui sono state costruite. */
  val windowLabels: List<String> get() = cells.keys.toList()

  /** Quante emissioni giudicate conta la finestra. */
  fun samples(windowLabel: String): Int = cells[windowLabel]?.sumOf { it.total } ?: 0

  /** Il tasso complessivo della finestra (senza stagione ne' ora); null se non ci sono casi. */
  fun overallRate(windowLabel: String): Double? {
    val row = cells[windowLabel] ?: return null
    val total = row.sumOf { it.total }
    if (total == 0) return null
    return row.sumOf { it.wet }.toDouble() / total
  }

  /**
   * I conteggi complessivi della finestra (bagnate / giudicate, sommando tutte le celle); null se
   * la finestra non e' nota o non ha casi. E' quello che serve a sommare piu' localita' senza
   * passare da un tasso arrotondato ([PooledPriors]).
   */
  fun overallCounts(windowLabel: String): RateCell? {
    val row = cells[windowLabel] ?: return null
    val total = row.sumOf { it.total }
    if (total == 0) return null
    return RateCell(row.sumOf { it.wet }, total)
  }

  /** I conteggi grezzi della cella di un'emissione: per diagnosi e per i test. */
  fun cellCounts(windowLabel: String, issuedAtMillis: Long): RateCell? =
    cells[windowLabel]?.get(cellOf(issuedAtMillis, longitudeDeg))

  /**
   * La probabilita' climatologica che la finestra di questa emissione finisca bagnata: la cella
   * della stagione e dell'ora solare, ristretta verso il tasso complessivo. Null se la finestra
   * non e' nota o non ha casi.
   */
  fun rate(windowLabel: String, issuedAtMillis: Long): Double? {
    val overall = overallRate(windowLabel) ?: return null
    val cell = cells.getValue(windowLabel)[cellOf(issuedAtMillis, longitudeDeg)]
    return cell.shrunkToward(overall, pseudoCounts)
  }

  /** Lo stesso tasso in log-odds, come lo vuole un modello logistico: ritagliato ai bordi. */
  fun logit(windowLabel: String, issuedAtMillis: Long): Double? =
    rate(windowLabel, issuedAtMillis)?.let(::logitOf)

  /**
   * Una riga di testo, versionata: `WC1|longitudine|k|0-1h:w/n,...|1-3h:...|3-6h:...`.
   * Interi e un solo double scritto da [Double.toString] (che non dipende dalla lingua del
   * telefono e torna identico): il giro memoria-andata-ritorno non cambia nessun tasso.
   */
  fun encode(): String = buildString {
    append(FORMAT).append(SEPARATOR).append(longitudeDeg).append(SEPARATOR).append(pseudoCounts)
    for ((label, row) in cells) {
      append(SEPARATOR).append(label).append(':')
      row.joinTo(this, ",") { it.encode() }
    }
  }

  companion object {
    const val SHRINKAGE_PSEUDO_COUNTS: Int = 40
    const val SEASONS: Int = 4
    const val SOLAR_BIN_HOURS: Int = 6
    const val SOLAR_BINS: Int = 24 / SOLAR_BIN_HOURS
    const val CELLS: Int = SEASONS * SOLAR_BINS

    /**
     * Il ritaglio del logit: una cella mai bagnata in un posto secchissimo non deve diventare
     * meno infinito dentro un modello. 1e-4 tiene il logit in circa ±9,2.
     */
    const val LOGIT_CLIP: Double = 1e-4

    /** Il prefisso del formato: se cambia la struttura, cambia il numero e il vecchio si ricostruisce. */
    const val FORMAT: String = "WC1"
    internal const val SEPARATOR = '|'

    val SEASON_NAMES: List<String> = listOf("DJF", "MAM", "JJA", "SON")

    /**
     * Costruisce la climatologia dalla storia. [truthMm]: fine dello slot (ore piene UTC, come gli
     * archivi Open-Meteo) -> mm. Null se nessuna finestra ha nemmeno un'emissione giudicabile:
     * una climatologia vuota non e' una climatologia, e chi la usa deve saperlo (livello
     * `NONE_NOCLIMA`).
     */
    fun build(
      truthMm: Map<Long, Double>,
      longitudeDeg: Double,
      windows: List<RainWindow> = RainWindows.ALL,
      pseudoCounts: Int = SHRINKAGE_PSEUDO_COUNTS,
    ): WindowClimatology? {
      val rows = windows.associate { it.label to Array(CELLS) { RateCell.EMPTY } }
      forEachJudgedIssue(truthMm, windows) { issuedAt, window, wet ->
        val row = rows.getValue(window.label)
        val cell = cellOf(issuedAt, longitudeDeg)
        row[cell] = row[cell].counting(wet)
      }
      if (rows.values.all { row -> row.all { it.total == 0 } }) return null
      return WindowClimatology(longitudeDeg, pseudoCounts, rows.mapValues { it.value.toList() })
    }

    /** L'inverso di [encode]; null su qualunque riga malformata o di un formato diverso. */
    fun decode(text: String): WindowClimatology? {
      val parts = text.split(SEPARATOR)
      if (parts.size < 4 || parts[0] != FORMAT) return null
      val longitude = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
      val pseudoCounts = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
      val rows = LinkedHashMap<String, List<RateCell>>()
      for (part in parts.drop(3)) {
        val (label, row) = decodeRow(part, CELLS) ?: return null
        if (label in rows) return null
        rows[label] = row
      }
      return WindowClimatology(longitude, pseudoCounts, rows)
    }

    /** La stagione meteorologica dal mese UTC: 0 = DJF, 1 = MAM, 2 = JJA, 3 = SON. */
    fun seasonOf(timestampMillis: Long): Int {
      val month = Instant.ofEpochMilli(timestampMillis).atOffset(ZoneOffset.UTC).monthValue
      return (month % 12) / 3
    }

    /** La fascia di sei ore di tempo solare medio: 0 = notte (0-6), 1 = mattina, 2 = pomeriggio, 3 = sera. */
    fun solarBinOf(timestampMillis: Long, longitudeDeg: Double): Int =
      (SolarTime.solarHours(timestampMillis, longitudeDeg) / SOLAR_BIN_HOURS).toInt().coerceIn(0, SOLAR_BINS - 1)

    /** La cella di un'emissione, scelta dall'ancora della sua finestra. */
    fun cellOf(issuedAtMillis: Long, longitudeDeg: Double): Int {
      val anchor = RainWindows.anchorOf(issuedAtMillis)
      return seasonOf(anchor) * SOLAR_BINS + solarBinOf(anchor, longitudeDeg)
    }

    /** Logit ritagliato a [LOGIT_CLIP]. */
    fun logitOf(probability: Double): Double {
      val p = probability.coerceIn(LOGIT_CLIP, 1 - LOGIT_CLIP)
      return ln(p / (1 - p))
    }
  }
}

/**
 * Tutte le emissioni orarie della storia, giudicate: per ogni ora piena da un'ora prima del primo
 * slot all'ultimo slot, per ogni finestra, l'esito di [RainWindows] — solo dove la finestra e'
 * completa. E' il ciclo comune di climatologia e baseline: lo stesso insieme di casi per entrambe,
 * cosi' una baseline e il prior verso cui si restringe parlano delle stesse emissioni.
 */
internal inline fun forEachJudgedIssue(
  truthMm: Map<Long, Double>,
  windows: List<RainWindow>,
  action: (issuedAtMillis: Long, window: RainWindow, wet: Boolean) -> Unit,
) {
  if (truthMm.isEmpty()) return
  val firstSlot = truthMm.keys.min()
  val lastSlot = truthMm.keys.max()
  val lookup: (Long) -> Double? = { truthMm[it] }
  var issuedAt = RainWindows.anchorOf(firstSlot) - RainWindows.HOUR_MILLIS
  while (issuedAt < lastSlot) {
    for (window in windows) {
      val wet = RainWindows.outcomeFromAnchor(issuedAt, window, lookup) ?: continue
      action(issuedAt, window, wet)
    }
    issuedAt += RainWindows.HOUR_MILLIS
  }
}

/** Una riga `etichetta:w/n,w/n,...` con esattamente [expectedCells] celle. */
internal fun decodeRow(part: String, expectedCells: Int): Pair<String, List<RateCell>>? {
  val colon = part.lastIndexOf(':')
  if (colon <= 0) return null
  val label = part.substring(0, colon)
  val cells = part.substring(colon + 1).split(',').map { RateCell.decode(it) ?: return null }
  if (cells.size != expectedCells) return null
  return label to cells
}
