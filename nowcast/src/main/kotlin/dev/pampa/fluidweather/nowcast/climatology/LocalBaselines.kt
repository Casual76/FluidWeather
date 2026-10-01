package dev.pampa.fluidweather.nowcast.climatology

import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows

/**
 * Le regole "a occhio" che il barometro deve battere, tarate sul posto.
 *
 * La promessa del nucleo indipendente (D1) non e' "batte la climatologia": e' "batte, in ogni
 * livello, la migliore fra climatologia locale, persistenza e regola barometrica". Due di queste
 * sono regole che chiunque applica guardando fuori o guardando un barometro da parete — "se piove
 * adesso piovera' ancora", "se la pressione crolla arriva il brutto" — e il modo onesto di metterle
 * in classifica non e' con numeri scelti a mano (lo 0,85 della vecchia persistenza del banco era
 * ottimista, i pavimenti misurati dicono 0,65), ma con la frequenza vera, qui, di cio' che la
 * regola annuncia:
 *
 * - **persistenza**: P(finestra bagnata | classe della pioggia dell'ultima ora chiusa, **e quanto
 *   e' vecchia quell'ora**), classi <0,2 · [0,2; 0,5) · [0,5; 2) · ≥2 mm, per quattro fasce di
 *   ritardo ([LAG_BINS]). La prima soglia e' quella dell'evento: "piove adesso" vuol dire la
 *   stessa cosa che "la finestra e' bagnata".
 * - **regola barometrica**: P(finestra bagnata | classe della tendenza MSL a 3 ore), classi
 *   ≤-1,16 · (-1,16; -0,53] · (-0,53; -0,1] · (-0,1; 0,1) · [0,1; 0,53) · ≥0,53 hPa/h. Le soglie
 *   1,16 e 0,53 sono quelle della letteratura (3,5 e 1,6 hPa in tre ore) gia' usate dalla regola
 *   del banco; 0,1 separa "ferma" da "si muove appena".
 *
 * Ogni classe e' ristretta verso la climatologia della **stessa emissione** con
 * [SHRINKAGE_PSEUDO_COUNTS] pseudo-conteggi: una classe rara (≥2 mm in un posto secco, crollo di
 * pressione ai tropici) dice soprattutto la climatologia di quella stagione e di quell'ora, una
 * classe frequente dice se stessa. Cosi' la baseline non e' mai peggio della climatologia per
 * mancanza di casi — e il modello che la batte la batte davvero.
 *
 * Le etichette vengono sempre dalla serie di verita' attraverso [RainWindows] e dallo stesso ciclo
 * di emissioni della [WindowClimatology]: baseline, climatologia e modello rispondono alla stessa
 * domanda sugli stessi casi. La serie "piove adesso" e quella della pressione sono invece quelle
 * che vede chi usa la regola (sul banco il contesto dei provider, sul telefono il barometro e il
 * quarto d'ora); di default la persistenza guarda la verita' stessa.
 *
 * **Il ritardo (LB2).** La versione LB1 costruiva la persistenza con l'ultima ora chiusa *appena
 * prima* dell'ancora (ritardo zero) e il banco la serviva con l'ultima ora chiusa del contesto, che
 * all'emissione e' vecchia di uno-tre slot (FRESH) o fino a tredici (STALE): "ha piovuto nell'ultima
 * ora" contava come se l'ora fosse appena finita, e la baseline era troppo sicura — piu' debole di
 * come doveva essere, quindi un avversario piu' facile per il modello. Ora la cella e' condizionata
 * al ritardo ℓ = (ancora - fine dello slot del contesto) / 1 h, in quattro fasce: 1-2 h, 3-4 h, 5-7 h,
 * 8-13 h (contesti di circa un'ora e mezza, fino a tre ore, fino a sei, fino a dodici). Dentro la fascia
 * ogni ritardo pesa uguale. Un ritardo oltre [MAX_LAG_HOURS] non ha risposta: fuori da STALE il
 * contesto non e' piu' un contesto. Il gate si fa piu' severo, non piu' facile.
 *
 * Nota sul telefono: la storia emette alle ore piene; un'emissione alle 10:20 guarda lo slot
 * (9:00, 10:00] e giudica da (11:00, 12:00]. Quella distanza e' il ritardo, ed e' misurata qui.
 */
class LocalBaselines internal constructor(
  /** Il prior verso cui si restringe ogni classe: la climatologia costruita sulla stessa storia. */
  val climatology: WindowClimatology,
  val pseudoCounts: Int,
  /** etichetta -> [LAG_BINS] fasce di ritardo x [RAIN_NOW_CLASSES] celle. */
  private val persistenceRows: Map<String, List<List<RateCell>>>,
  /** etichetta -> [TREND_CLASSES] celle. */
  private val barometricRows: Map<String, List<RateCell>>,
) {

  /** Le finestre che queste baseline conoscono. */
  val windowLabels: List<String> get() = persistenceRows.keys.toList()

  /**
   * P(finestra bagnata | pioggia dell'ultima ora chiusa del contesto = [rainNowMm], ora chiusa a
   * [contextSlotEndMillis]) per questa emissione. Il ritardo e' [lagOf]; oltre [MAX_LAG_HOURS]
   * non c'e' risposta. Null anche se la pioggia di adesso non e' nota, o se la finestra non ha
   * climatologia.
   */
  fun persistence(
    windowLabel: String,
    issuedAtMillis: Long,
    rainNowMm: Double?,
    contextSlotEndMillis: Long,
  ): Double? {
    if (rainNowMm == null || rainNowMm.isNaN()) return null
    val bin = lagBinOf(lagOf(issuedAtMillis, contextSlotEndMillis)) ?: return null
    val row = persistenceRows[windowLabel]?.get(bin) ?: return null
    val prior = climatology.rate(windowLabel, issuedAtMillis) ?: return null
    return row[rainNowClassOf(rainNowMm)].shrunkToward(prior, pseudoCounts)
  }

  /**
   * P(finestra bagnata | tendenza MSL a 3 ore = [trendHpaPerHour]) per questa emissione.
   * Null se la tendenza non e' nota, o se la finestra non ha climatologia.
   */
  fun barometric(windowLabel: String, issuedAtMillis: Long, trendHpaPerHour: Double?): Double? {
    if (trendHpaPerHour == null || trendHpaPerHour.isNaN()) return null
    val row = barometricRows[windowLabel] ?: return null
    val prior = climatology.rate(windowLabel, issuedAtMillis) ?: return null
    return row[trendClassOf(trendHpaPerHour)].shrunkToward(prior, pseudoCounts)
  }

  /** I conteggi grezzi della persistenza di una fascia di ritardo, classe per classe: per diagnosi e test. */
  fun persistenceCounts(windowLabel: String, lagBin: Int): List<RateCell>? =
    persistenceRows[windowLabel]?.getOrNull(lagBin)

  /** I conteggi grezzi della regola barometrica, classe per classe. */
  fun barometricCounts(windowLabel: String): List<RateCell>? = barometricRows[windowLabel]

  /**
   * Una riga di testo, versionata: `LB2|k|P0-1h@0:w/n,...(4)|...|T0-1h:w/n,...(6)|...`, dove
   * `@b` e' la fascia di ritardo. La climatologia non ci entra: si conserva per conto suo e si
   * ripassa a [decode]. Il vecchio formato `LB1` non si legge piu': chi lo trova lo ricostruisce.
   */
  fun encode(): String = buildString {
    append(FORMAT).append(WindowClimatology.SEPARATOR).append(pseudoCounts)
    for ((label, bins) in persistenceRows) {
      for ((bin, row) in bins.withIndex()) {
        append(WindowClimatology.SEPARATOR).append(PERSISTENCE_TAG).append(label).append(LAG_SEPARATOR).append(bin).append(':')
        row.joinTo(this, ",") { it.encode() }
      }
    }
    for ((label, row) in barometricRows) {
      append(WindowClimatology.SEPARATOR).append(BAROMETRIC_TAG).append(label).append(':')
      row.joinTo(this, ",") { it.encode() }
    }
  }

  companion object {
    const val SHRINKAGE_PSEUDO_COUNTS: Int = 40

    /** Piove forte: sopra i 2 mm in un'ora. */
    const val HEAVY_RAIN_NOW_MM: Double = 2.0

    /** Piove davvero: da mezzo millimetro in su. */
    const val MODERATE_RAIN_NOW_MM: Double = 0.5
    const val RAIN_NOW_CLASSES: Int = 4

    /** 3,5 hPa in tre ore: aria di tempesta. */
    const val STORM_FALL_HPA_PER_HOUR: Double = -1.16

    /** 1,6 hPa in tre ore: cambio di tempo. */
    const val CHANGE_FALL_HPA_PER_HOUR: Double = -0.53

    /** Sotto un decimo di hPa all'ora la pressione e' ferma. */
    const val STEADY_HPA_PER_HOUR: Double = 0.1
    const val CHANGE_RISE_HPA_PER_HOUR: Double = 0.53
    const val TREND_CLASSES: Int = 6

    /** La tendenza si misura su tre ore, come la regola della letteratura. */
    const val TREND_HOURS: Int = 3

    /** Le fasce di ritardo del contesto: 1-2 h, 3-4 h, 5-7 h, 8-13 h. */
    const val LAG_BINS: Int = 4

    /**
     * Il ritardo massimo che la persistenza conosce: un contesto STALE (12 h) piu' il minuto di
     * emissione (fino a un'ora fra emissione e ancora) e l'ora dello slot (fino a un'ora fra fetch e
     * fine dello slot chiuso).
     */
    const val MAX_LAG_HOURS: Int = 13

    /** L'ultimo ritardo (incluso) di ogni fascia. */
    private val LAG_BIN_LAST_HOUR = intArrayOf(2, 4, 7, MAX_LAG_HOURS)

    const val FORMAT: String = "LB2"
    private const val PERSISTENCE_TAG = 'P'
    private const val BAROMETRIC_TAG = 'T'
    private const val LAG_SEPARATOR = '@'

    /** La classe della pioggia dell'ultima ora chiusa: 0 asciutto, 1 debole, 2 moderata, 3 forte. */
    fun rainNowClassOf(lastHourMm: Double): Int = when {
      !RainWindows.isWet(lastHourMm) -> 0
      lastHourMm < MODERATE_RAIN_NOW_MM -> 1
      lastHourMm < HEAVY_RAIN_NOW_MM -> 2
      else -> 3
    }

    /**
     * La classe della tendenza (hPa/h): 0 crollo, 1 caduta, 2 lieve calo, 3 ferma, 4 lieve
     * salita, 5 salita.
     */
    fun trendClassOf(trendHpaPerHour: Double): Int = when {
      trendHpaPerHour <= STORM_FALL_HPA_PER_HOUR -> 0
      trendHpaPerHour <= CHANGE_FALL_HPA_PER_HOUR -> 1
      trendHpaPerHour <= -STEADY_HPA_PER_HOUR -> 2
      trendHpaPerHour < STEADY_HPA_PER_HOUR -> 3
      trendHpaPerHour < CHANGE_RISE_HPA_PER_HOUR -> 4
      else -> 5
    }

    /**
     * Il ritardo del contesto all'emissione, in ore: ancora della finestra meno fine dello slot
     * "adesso" del contesto. FRESH cade a 1-3 (l'ancora e' l'emissione per eccesso, lo slot l'ultimo
     * chiuso al fetch), STALE fino a 13. Zero o meno (emissione sull'ora, contesto dello stesso
     * istante) cade nella prima fascia.
     */
    fun lagOf(issuedAtMillis: Long, contextSlotEndMillis: Long): Int =
      Math.floorDiv(RainWindows.anchorOf(issuedAtMillis) - contextSlotEndMillis, RainWindows.HOUR_MILLIS).toInt()

    /** La fascia di un ritardo in ore: `ℓ <= 0` e' la prima, `ℓ > MAX_LAG_HOURS` non ha fascia (null). */
    fun lagBinOf(lagHours: Int): Int? {
      if (lagHours > MAX_LAG_HOURS) return null
      return LAG_BIN_LAST_HOUR.indexOfFirst { lagHours <= it }
    }

    /**
     * La tendenza a 3 ore di una serie MSL (istante -> hPa) a un'emissione oraria: null se manca
     * uno dei due estremi.
     */
    fun trendAt(mslHpa: Map<Long, Double>, issuedAtMillis: Long): Double? {
      val now = mslHpa[issuedAtMillis]?.takeUnless { it.isNaN() } ?: return null
      val before = mslHpa[issuedAtMillis - TREND_HOURS * RainWindows.HOUR_MILLIS]?.takeUnless { it.isNaN() }
        ?: return null
      return (now - before) / TREND_HOURS
    }

    /**
     * Costruisce le baseline dalla storia.
     *
     * [truthMm] da' gli esiti (fine dello slot -> mm), come per la climatologia; [climatology]
     * deve essere quella costruita sulla stessa storia. [rainNowMm] e' la serie da cui si legge
     * "piove adesso" (un'ora chiusa `ℓ` ore prima dell'ancora, per ogni ℓ da 1 a [MAX_LAG_HOURS]);
     * [mslHpa] la pressione al mare (istante -> hPa), null se non c'e': la regola barometrica resta
     * allora vuota e ogni sua risposta e' la climatologia.
     */
    fun build(
      truthMm: Map<Long, Double>,
      climatology: WindowClimatology,
      rainNowMm: Map<Long, Double>? = truthMm,
      mslHpa: Map<Long, Double>? = null,
      windows: List<RainWindow> = RainWindows.ALL,
      pseudoCounts: Int = SHRINKAGE_PSEUDO_COUNTS,
    ): LocalBaselines {
      val persistence = windows.associate { it.label to Array(LAG_BINS) { Array(RAIN_NOW_CLASSES) { RateCell.EMPTY } } }
      val barometric = windows.associate { it.label to Array(TREND_CLASSES) { RateCell.EMPTY } }
      forEachJudgedIssue(truthMm, windows) { issuedAt, window, wet ->
        if (rainNowMm != null) {
          val bins = persistence.getValue(window.label)
          // Ogni ritardo da 1 a 13 ore e' un caso a se': l'ora chiusa `lag` ore prima dell'ancora.
          for (lag in 1..MAX_LAG_HOURS) {
            val rainNow = rainNowMm[issuedAt - lag * RainWindows.HOUR_MILLIS]?.takeUnless { it.isNaN() } ?: continue
            val row = bins[lagBinOf(lag)!!]
            val index = rainNowClassOf(rainNow)
            row[index] = row[index].counting(wet)
          }
        }
        val trend = mslHpa?.let { trendAt(it, issuedAt) }
        if (trend != null) {
          val row = barometric.getValue(window.label)
          val index = trendClassOf(trend)
          row[index] = row[index].counting(wet)
        }
      }
      return LocalBaselines(
        climatology = climatology,
        pseudoCounts = pseudoCounts,
        persistenceRows = persistence.mapValues { (_, bins) -> bins.map { it.toList() } },
        barometricRows = barometric.mapValues { it.value.toList() },
      )
    }

    /**
     * L'inverso di [encode], con la climatologia conservata a parte; null su righe malformate, su un
     * altro formato (`LB1` compreso) e su una persistenza con fasce di ritardo mancanti.
     */
    fun decode(text: String, climatology: WindowClimatology): LocalBaselines? {
      val parts = text.split(WindowClimatology.SEPARATOR)
      if (parts.size < 2 || parts[0] != FORMAT) return null
      val pseudoCounts = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
      val persistence = LinkedHashMap<String, Array<List<RateCell>?>>()
      val barometric = LinkedHashMap<String, List<RateCell>>()
      for (part in parts.drop(2)) {
        if (part.isEmpty()) return null
        when (part[0]) {
          PERSISTENCE_TAG -> {
            val (labelAndBin, row) = decodeRow(part.substring(1), RAIN_NOW_CLASSES) ?: return null
            val at = labelAndBin.lastIndexOf(LAG_SEPARATOR)
            if (at <= 0) return null
            val label = labelAndBin.substring(0, at)
            val bin = labelAndBin.substring(at + 1).toIntOrNull()?.takeIf { it in 0 until LAG_BINS } ?: return null
            val bins = persistence.getOrPut(label) { arrayOfNulls(LAG_BINS) }
            if (bins[bin] != null) return null
            bins[bin] = row
          }

          BAROMETRIC_TAG -> {
            val (label, row) = decodeRow(part.substring(1), TREND_CLASSES) ?: return null
            if (label in barometric) return null
            barometric[label] = row
          }

          else -> return null
        }
      }
      val complete = LinkedHashMap<String, List<List<RateCell>>>()
      for ((label, bins) in persistence) {
        complete[label] = bins.map { it ?: return null }
      }
      return LocalBaselines(climatology, pseudoCounts, complete, barometric)
    }
  }
}
