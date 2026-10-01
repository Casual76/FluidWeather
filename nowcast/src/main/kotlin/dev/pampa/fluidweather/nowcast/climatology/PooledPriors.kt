package dev.pampa.fluidweather.nowcast.climatology

import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows

/**
 * Il riferimento "per tutti i posti": cio' che si sa della pioggia quando non si sa dove si e'.
 *
 * Serve in due casi. Il primo e' `NONE_NOCLIMA`, il telefono offline al primo avvio, che non ha
 * ancora scaricato la climatologia del suo posto. Il secondo e' il ripiego di ogni altro livello
 * quando le tabelle locali mancano o non si leggono (il v3 le porta sempre con se', come costante
 * del modello: `TrainedNowcastV3.POOLED`). Una sola definizione, dentro `:nowcast`: il banco la
 * costruisce sommando le sue dieci localita', il telefono la legge dall'artefatto, e le baseline
 * del gate e le feature del modello (vedi [FeatureExtractorV3]) la consultano con lo stesso codice.
 *
 * **Cosa contiene.** Conteggi interi, sommati sulle localita' che hanno storia (mai tassi
 * arrotondati): il tasso costante di ogni finestra e' bagnate/giudicate sulla somma; la
 * persistenza (per fascia di ritardo e classe di pioggia di adesso) e la regola barometrica (per
 * classe di tendenza) sono le frequenze sui conteggi sommati, ristrette verso il **tasso costante**
 * con gli stessi pseudo-conteggi delle baseline locali ([LocalBaselines.SHRINKAGE_PSEUDO_COUNTS]).
 * Non c'e' stagione ne' ora solare: non si sa il posto, quindi non si sa nemmeno il sole.
 *
 * **Il formato.** Una riga di testo, versionata, con i soli conteggi:
 * `PP1|k|C<finestra>:w/n|...|P<finestra>@<fascia>:w/n,...(4)|...|T<finestra>:w/n,...(6)|...`.
 */
class PooledPriors internal constructor(
  val pseudoCounts: Int,
  /** etichetta -> bagnate/giudicate sulla somma delle localita'. */
  private val constants: Map<String, RateCell>,
  /** etichetta -> [LocalBaselines.LAG_BINS] fasce x [LocalBaselines.RAIN_NOW_CLASSES] celle. */
  private val persistenceRows: Map<String, List<List<RateCell>>>,
  /** etichetta -> [LocalBaselines.TREND_CLASSES] celle. */
  private val barometricRows: Map<String, List<RateCell>>,
) {

  /** Il tasso bagnato di una finestra su tutte le localita'; null se la storia e' vuota. */
  fun constantRate(windowLabel: String): Double? =
    constants[windowLabel]?.let { it.wet.toDouble() / it.total }

  /** I conteggi della finestra sommati sulle localita'. */
  fun constantCounts(windowLabel: String): RateCell? = constants[windowLabel]

  /** Il riferimento conosce tutte queste finestre? */
  fun covers(windows: List<RainWindow> = RainWindows.ALL): Boolean = windows.all { it.label in constants }

  /**
   * P(bagnata | fascia di ritardo, classe della pioggia di adesso) sulla storia di tutte le
   * localita', ristretta verso il tasso costante; null se la finestra non ha storia.
   */
  fun persistence(windowLabel: String, lagBin: Int, rainNowClass: Int): Double? {
    val prior = constantRate(windowLabel) ?: return null
    val cell = persistenceRows[windowLabel]?.getOrNull(lagBin)?.getOrNull(rainNowClass) ?: return null
    return cell.shrunkToward(prior, pseudoCounts)
  }

  /** P(bagnata | classe della tendenza a 3 ore) sulla storia di tutte le localita', ristretta verso il tasso costante. */
  fun barometric(windowLabel: String, trendClass: Int): Double? {
    val prior = constantRate(windowLabel) ?: return null
    val cell = barometricRows[windowLabel]?.getOrNull(trendClass) ?: return null
    return cell.shrunkToward(prior, pseudoCounts)
  }

  /** La riga di testo `PP1|...`: stesso riferimento, stessa riga. */
  fun encode(): String = buildString {
    append(FORMAT).append(SEP).append(pseudoCounts)
    for ((label, cell) in constants) append(SEP).append(CONSTANT_TAG).append(label).append(':').append(cell.encode())
    for ((label, bins) in persistenceRows) {
      for ((bin, row) in bins.withIndex()) {
        append(SEP).append(PERSISTENCE_TAG).append(label).append(LAG_SEPARATOR).append(bin).append(':')
        row.joinTo(this, ",") { it.encode() }
      }
    }
    for ((label, row) in barometricRows) {
      append(SEP).append(BAROMETRIC_TAG).append(label).append(':')
      row.joinTo(this, ",") { it.encode() }
    }
  }

  companion object {
    const val FORMAT: String = "PP1"
    private const val SEP = WindowClimatology.SEPARATOR
    private const val CONSTANT_TAG = 'C'
    private const val PERSISTENCE_TAG = 'P'
    private const val BAROMETRIC_TAG = 'T'
    private const val LAG_SEPARATOR = '@'

    /**
     * Somma le climatologie e le baseline delle localita' con storia; le localita' senza storia
     * (null) non contano, e una finestra senza nessun caso resta fuori.
     */
    fun of(
      parts: List<Pair<WindowClimatology?, LocalBaselines?>>,
      windows: List<RainWindow> = RainWindows.ALL,
      pseudoCounts: Int = LocalBaselines.SHRINKAGE_PSEUDO_COUNTS,
    ): PooledPriors {
      val constants = LinkedHashMap<String, RateCell>()
      val persistence = LinkedHashMap<String, List<List<RateCell>>>()
      val barometric = LinkedHashMap<String, List<RateCell>>()
      for (window in windows) {
        val label = window.label
        var wet = 0L
        var total = 0L
        for ((climatology, _) in parts) {
          val counts = climatology?.overallCounts(label) ?: continue
          wet += counts.wet
          total += counts.total
        }
        if (total == 0L) continue
        constants[label] = RateCell(Math.toIntExact(wet), Math.toIntExact(total))

        persistence[label] = List(LocalBaselines.LAG_BINS) { bin ->
          sum(LocalBaselines.RAIN_NOW_CLASSES, parts) { it.persistenceCounts(label, bin) }
        }
        barometric[label] = sum(LocalBaselines.TREND_CLASSES, parts) { it.barometricCounts(label) }
      }
      return PooledPriors(pseudoCounts, constants, persistence, barometric)
    }

    private inline fun sum(
      classes: Int,
      parts: List<Pair<WindowClimatology?, LocalBaselines?>>,
      cellsOf: (LocalBaselines) -> List<RateCell>?,
    ): List<RateCell> {
      val wet = LongArray(classes)
      val total = LongArray(classes)
      for ((_, baselines) in parts) {
        val cells = cellsOf(baselines ?: continue) ?: continue
        for ((i, cell) in cells.withIndex()) {
          wet[i] += cell.wet.toLong()
          total[i] += cell.total.toLong()
        }
      }
      return List(classes) { RateCell(Math.toIntExact(wet[it]), Math.toIntExact(total[it])) }
    }

    /** L'inverso di [encode]; null su qualunque riga malformata, di un altro formato o incompleta. */
    fun decode(text: String): PooledPriors? {
      val parts = text.split(SEP)
      if (parts.size < 2 || parts[0] != FORMAT) return null
      val pseudoCounts = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
      val constants = LinkedHashMap<String, RateCell>()
      val persistence = LinkedHashMap<String, Array<List<RateCell>?>>()
      val barometric = LinkedHashMap<String, List<RateCell>>()
      for (part in parts.drop(2)) {
        if (part.isEmpty()) return null
        when (part[0]) {
          CONSTANT_TAG -> {
            val (label, cells) = decodeRow(part.substring(1), 1) ?: return null
            if (label in constants || cells[0].total == 0) return null
            constants[label] = cells[0]
          }

          PERSISTENCE_TAG -> {
            val (labelAndBin, row) = decodeRow(part.substring(1), LocalBaselines.RAIN_NOW_CLASSES) ?: return null
            val at = labelAndBin.lastIndexOf(LAG_SEPARATOR)
            if (at <= 0) return null
            val label = labelAndBin.substring(0, at)
            val bin = labelAndBin.substring(at + 1).toIntOrNull()?.takeIf { it in 0 until LocalBaselines.LAG_BINS } ?: return null
            val bins = persistence.getOrPut(label) { arrayOfNulls(LocalBaselines.LAG_BINS) }
            if (bins[bin] != null) return null
            bins[bin] = row
          }

          BAROMETRIC_TAG -> {
            val (label, row) = decodeRow(part.substring(1), LocalBaselines.TREND_CLASSES) ?: return null
            if (label in barometric) return null
            barometric[label] = row
          }

          else -> return null
        }
      }
      // Completo o niente: ogni finestra con un tasso ha tutte le sue fasce e la sua riga barometrica.
      if (persistence.keys != constants.keys || barometric.keys != constants.keys) return null
      val complete = LinkedHashMap<String, List<List<RateCell>>>()
      for (label in constants.keys) complete[label] = persistence.getValue(label).map { it ?: return null }
      return PooledPriors(pseudoCounts, constants, complete, barometric)
    }
  }
}
