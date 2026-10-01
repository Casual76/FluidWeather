package dev.pampa.fluidweather.nowcast.climatology

import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows

/**
 * Le tre baseline oneste di un posto in un solo oggetto: climatologia, persistenza, regola
 * barometrica. E' la porta da cui il gate legge le avversarie (il banco le chiede per livello) **e** da cui il modello del v3 legge le sue feature 30-38
 * ([FeatureExtractorV3]): stesso codice, stessi numeri. Per questo il modello puo' sempre riprodurre
 * qualunque baseline — basta darle coefficiente uno e lasciare a zero il resto — e "batte la migliore
 * baseline" diventa una proprieta' che l'addestramento ha gli strumenti per soddisfare.
 *
 * - **Locale** ([local]): le tabelle del posto, [WindowClimatology] (WC1) e [LocalBaselines] (LB2),
 *   con sotto il riferimento di tutti i posti ([PooledPriors]) come ultimo ripiego.
 * - **Solo globale** ([pooledOnly]): quando il posto non si conosce (primo avvio offline) o le tabelle
 *   locali non si leggono. Il tasso e' il costante di tutte le localita', persistenza e regola
 *   barometrica quelle sommate.
 *
 * Il locale e' **tutto o niente**: WC1 e LB2 si leggono entrambi ([decodeLocal]) o nessuno dei due,
 * perche' una persistenza locale ristretta verso una climatologia che non c'e' non significa niente.
 *
 * Le risposte sono sempre numeri: la climatologia e la regola barometrica non hanno mai un "non so"
 * (la regola ripiega sulla climatologia quando la tendenza manca, esattamente come il gate). Solo la
 * persistenza puo' essere null: senza "piove adesso" o con un ritardo oltre [LocalBaselines.MAX_LAG_HOURS]
 * non c'e' niente da dire.
 */
class LocalPriors private constructor(
  private val climatologyTable: WindowClimatology?,
  private val baselines: LocalBaselines?,
  val pooled: PooledPriors,
) {

  init {
    require((climatologyTable == null) == (baselines == null)) { "il locale e' tutto o niente: clima e baseline insieme" }
    require(pooled.covers(RainWindows.ALL)) { "il riferimento di tutti i posti non conosce tutte le finestre" }
    if (climatologyTable != null) {
      require(RainWindows.ALL.all { it.label in climatologyTable.windowLabels }) { "la climatologia locale non conosce tutte le finestre" }
    }
  }

  /** Le tabelle sono quelle del posto (true) o il riferimento di tutti i posti (false)? */
  val isLocal: Boolean get() = climatologyTable != null

  /** La probabilita' climatologica che la finestra dell'emissione finisca bagnata. */
  fun climatology(window: RainWindow, issuedAtMillis: Long): Double =
    climatologyTable?.rate(window.label, issuedAtMillis) ?: pooled.constantRate(window.label)!!

  /**
   * La persistenza tarata sul ritardo del contesto: P(bagnata | pioggia dell'ultima ora chiusa = [rainNowMm],
   * ora chiusa a [contextSlotEndMillis]). Null se "piove adesso" non e' noto o il ritardo e' oltre la
   * fascia piu' vecchia.
   */
  fun persistence(window: RainWindow, issuedAtMillis: Long, rainNowMm: Double?, contextSlotEndMillis: Long): Double? {
    if (rainNowMm == null || rainNowMm.isNaN()) return null
    if (baselines != null) return baselines.persistence(window.label, issuedAtMillis, rainNowMm, contextSlotEndMillis)
    val bin = LocalBaselines.lagBinOf(LocalBaselines.lagOf(issuedAtMillis, contextSlotEndMillis)) ?: return null
    return pooled.persistence(window.label, bin, LocalBaselines.rainNowClassOf(rainNowMm))
  }

  /**
   * La regola barometrica tarata: P(bagnata | classe della tendenza misurata a 3 ore). Senza tendenza
   * (NaN o null) risponde la climatologia: la regola non sa niente e lo dice col tasso del posto.
   */
  fun barometric(window: RainWindow, issuedAtMillis: Long, trendHpaPerHour: Double?): Double {
    if (trendHpaPerHour == null || trendHpaPerHour.isNaN()) return climatology(window, issuedAtMillis)
    val rate = if (baselines != null) {
      baselines.barometric(window.label, issuedAtMillis, trendHpaPerHour)
    } else {
      pooled.barometric(window.label, LocalBaselines.trendClassOf(trendHpaPerHour))
    }
    return rate ?: climatology(window, issuedAtMillis)
  }

  /** Lo stato in una riga di testo: due oggetti con le stesse tabelle danno la stessa riga. */
  fun encodedState(): String = "${climatologyTable?.encode()}#${baselines?.encode()}#${pooled.encode()}"

  companion object {

    /** Le tabelle del posto, con il riferimento di tutti i posti sotto. */
    fun local(climatology: WindowClimatology, baselines: LocalBaselines, pooled: PooledPriors): LocalPriors =
      LocalPriors(climatology, baselines, pooled)

    /** Solo il riferimento di tutti i posti. */
    fun pooledOnly(pooled: PooledPriors): LocalPriors = LocalPriors(null, null, pooled)

    /**
     * Le tabelle locali dalle loro righe di testo (WC1 + LB2); null se una delle due non si legge o se
     * non conosce tutte le finestre: tutto o niente.
     */
    fun decodeLocal(wc1: String, lb2: String, pooled: PooledPriors): LocalPriors? {
      val climatology = WindowClimatology.decode(wc1) ?: return null
      val baselines = LocalBaselines.decode(lb2, climatology) ?: return null
      if (RainWindows.ALL.any { it.label !in climatology.windowLabels || it.label !in baselines.windowLabels }) return null
      if (!pooled.covers(RainWindows.ALL)) return null
      return local(climatology, baselines, pooled)
    }
  }
}
