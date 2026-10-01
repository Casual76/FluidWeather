package dev.pampa.fluidweather.testbench.baselines

import dev.pampa.fluidweather.nowcast.climatology.LocalBaselines
import dev.pampa.fluidweather.nowcast.climatology.LocalPriors
import dev.pampa.fluidweather.nowcast.climatology.PooledPriors
import dev.pampa.fluidweather.nowcast.climatology.WindowClimatology
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.TierPeriod

/**
 * I nomi dei predittori del banco onesto: la chiave con cui righe, tabelle e test si parlano.
 *
 * Le quattro righe oneste sono fuori campione e giudicate dalla stessa verita' del modello; le
 * tre "info:" sono i vecchi numeri fissi del banco, tenuti per misurare quanto erano ottimisti e
 * mai candidati a "migliore baseline".
 */
object PredictorNames {
  const val SEMPRE_0 = "sempre-0"
  const val CLIMATOLOGIA = "climatologia"
  const val PERSISTENZA = "persistenza"
  const val REGOLA_BAROMETRICA = "regola-barometrica"

  /** Il vecchio tasso sull'intero periodo giudicato: conosce il proprio futuro. */
  const val INFO_CLIMA_FUTURO = "info:clima-futuro"

  /** La vecchia persistenza a numeri fissi: 0,85 se piove adesso, 0,08 altrimenti. */
  const val INFO_PERSISTENZA_FISSA = "info:persistenza-fissa"

  /** La vecchia regola barometrica a numeri fissi (0,75 / 2,5x / 0,4x il tasso). */
  const val INFO_REGOLA_FISSA = "info:regola-fissa"

  /** Il modello spedito oggi, verdetto grezzo. */
  const val MODELLO = "nowcast-spedito"

  /**
   * Le baseline che il modello deve battere (D1): la migliore fra climatologia locale, persistenza
   * (dove "piove adesso" e' noto) e regola barometrica. "sempre-0" e' un riferimento della
   * classifica, non una di queste: si riporta a parte.
   */
  val BASELINES: List<String> = listOf(CLIMATOLOGIA, PERSISTENZA, REGOLA_BAROMETRICA)

  val INFO: List<String> = listOf(INFO_CLIMA_FUTURO, INFO_PERSISTENZA_FISSA, INFO_REGOLA_FISSA)
}

/**
 * Le baseline oneste di una localita' per un periodo: climatologia locale, persistenza tarata sul
 * ritardo del contesto e regola barometrica tarata, tutte costruite **solo** dai due anni di verita'
 * del pannello che precedono il periodo — come le scaricherebbe il telefono — e mai da un millimetro
 * di quello che si sta giudicando.
 *
 * Prima il banco aveva una climatologia calcolata sull'intero dataset (il futuro compreso),
 * una persistenza "0,85 se piove, 0,08 altrimenti" scelta a mano e una regola barometrica a soglie
 * fisse: tre avversari che non esistono sul telefono e che il modello batteva o perdeva contro
 * per motivi sbagliati. Qui ogni baseline e' la frequenza vera, nel posto, di cio' che annuncia
 * (vedi [LocalBaselines]).
 *
 * **Un solo codice per avversarie e feature.** Le tre baseline sono risposte di [LocalPriors]
 * ([priors]): lo stesso oggetto da cui il modello del v3 legge le sue feature di baseline, quindi
 * il gate e il modello parlano degli stessi numeri e il modello puo' riprodurre qualunque avversaria.
 *
 * - **climatologia**: stagione x ora solare, ristretta; dove non c'e' ([ContextTier.NONE_NOCLIMA]) il
 *   tasso costante pre-periodo di tutte le localita'.
 * - **persistenza**: P(bagnata | classe della pioggia dell'ultimo slot chiuso del contesto **e ritardo
 *   di quello slot**), per FRESH e STALE. Lo STALE e' persistenza ritardata, e ora lo dice la tabella
 *   (LB2) e non solo la costruzione: un'avversaria piu' forte di quella di prima, non piu' facile.
 * - **regola-barometrica**: sulla tendenza a 3 ore *misurata dal telefono* (non quella del provider);
 *   nel livello senza climatologia, la stessa regola sui conteggi di tutte le localita'.
 */
class HonestBaselines internal constructor(
  val locationName: String,
  /** La climatologia locale; null se la storia pre-periodo e' vuota (la localita' non si giudica). */
  val localClimatology: WindowClimatology?,
  private val localBaselines: LocalBaselines?,
  private val pooled: PooledPriors,
) {

  /** Questa localita' ha una storia su cui tarare le baseline? */
  val hasHistory: Boolean get() = localClimatology != null && localBaselines != null

  private val localPriors: LocalPriors? by lazy {
    if (localClimatology != null && localBaselines != null && pooled.covers(RainWindows.ALL)) {
      LocalPriors.local(localClimatology, localBaselines, pooled)
    } else {
      null
    }
  }

  private val pooledOnlyPriors: LocalPriors? by lazy {
    if (pooled.covers(RainWindows.ALL)) LocalPriors.pooledOnly(pooled) else null
  }

  /**
   * Le tabelle che questo livello ha: quelle del posto, o il riferimento di tutti i posti per
   * [ContextTier.NONE_NOCLIMA]; null se la storia manca (la localita' non si gioca).
   */
  fun priors(tier: ContextTier): LocalPriors? = if (tier == ContextTier.NONE_NOCLIMA) pooledOnlyPriors else localPriors

  /**
   * Il solo riferimento di tutti i posti, qualunque sia il livello (null se nessuna localita' ha
   * storia): e' il ripiego di un telefono che ha il contesto ma non le tabelle locali.
   */
  fun pooledOnly(): LocalPriors? = pooledOnlyPriors

  /** La climatologia della finestra per l'emissione: locale, o costante di tutte le localita' se il livello non la conosce. */
  fun climatology(window: RainWindow, issuedAtMillis: Long, tier: ContextTier): Double? =
    priors(tier)?.climatology(window, issuedAtMillis)

  /**
   * La persistenza tarata sul ritardo: [rainNowMm] e' l'ultima ora chiusa del contesto, chiusa a
   * [contextSlotEndMillis]. Null se "piove adesso" non e' noto, se il ritardo e' oltre il massimo
   * ([LocalBaselines.MAX_LAG_HOURS]) o se la localita' non ha storia.
   */
  fun persistence(
    window: RainWindow,
    issuedAtMillis: Long,
    rainNowMm: Double?,
    contextSlotEndMillis: Long,
    tier: ContextTier = ContextTier.FRESH,
  ): Double? = priors(tier)?.persistence(window, issuedAtMillis, rainNowMm, contextSlotEndMillis)

  /** La regola barometrica tarata sulla tendenza a tre ore; null se la tendenza non e' nota. */
  fun barometric(window: RainWindow, issuedAtMillis: Long, trendHpaPerHour: Double?, tier: ContextTier): Double? {
    if (trendHpaPerHour == null || trendHpaPerHour.isNaN()) return null
    return priors(tier)?.barometric(window, issuedAtMillis, trendHpaPerHour)
  }

  /**
   * Tutto lo stato delle baseline in una riga di testo: due baseline con la stessa storia danno la
   * stessa riga. Serve al test "le baseline non vedono il periodo": cambiare il periodo non
   * puo' cambiare questa riga.
   */
  fun encodedState(): String =
    "${localClimatology?.encode()}#${localBaselines?.encode()}#${pooled.encode()}"

  companion object {

    /**
     * Costruisce le baseline di tutte le localita' per [period], dalla verita' del pannello nella
     * finestra di storia del periodo (estremi inclusi) — due anni, ritagliati al pannello. Il
     * riferimento pooled di ogni localita' e' lo stesso: la somma di tutte.
     */
    fun buildAll(
      period: TierPeriod,
      inputs: List<LocationInputs>,
      windows: List<RainWindow> = RainWindows.ALL,
    ): Map<String, HonestBaselines> =
      buildFromRanges(listOf(period.historyFromMillis..period.historyUntilMillis), inputs, windows)

    /**
     * Le baseline di tutte le localita' dagli slot (fine dello slot) dentro [ranges], estremi inclusi.
     * E' [buildAll] con la storia scelta dal chiamante: l'addestramento del v3 costruisce cosi' le
     * tabelle "degli altri anni" (vedi `JackknifeSlices`). Con un solo intervallo e' esattamente lo
     * stesso stato di [buildAll]; con piu' intervalli le finestre a cavallo di un buco restano
     * incomplete e non contano, e nessun millimetro fuori dagli intervalli entra nei conteggi.
     */
    fun buildFromRanges(
      ranges: List<LongRange>,
      inputs: List<LocationInputs>,
      windows: List<RainWindow> = RainWindows.ALL,
    ): Map<String, HonestBaselines> {
      val parts = inputs.map { input ->
        val truth = slice(input.panelTruth.asMap(), ranges)
        val climatology = WindowClimatology.build(truth, input.location.longitude, windows)
        val baselines = climatology?.let {
          LocalBaselines.build(
            truthMm = truth,
            climatology = it,
            rainNowMm = slice(input.contextRain, ranges),
            mslHpa = slice(input.panelMsl, ranges).takeIf { msl -> msl.isNotEmpty() },
            windows = windows,
          )
        }
        Triple(input.location.name, climatology, baselines)
      }
      val pooled = PooledPriors.of(parts.map { it.second to it.third }, windows)
      return parts.associate { (name, climatology, baselines) ->
        name to HonestBaselines(name, climatology, baselines, pooled)
      }
    }

    private fun slice(series: Map<Long, Double>, ranges: List<LongRange>): Map<Long, Double> {
      val part = HashMap<Long, Double>()
      for ((t, value) in series) if (ranges.any { t in it }) part[t] = value
      return part
    }
  }
}

/**
 * I vecchi predittori a numeri fissi del banco, tenuti solo come righe informative ("info:"):
 * servono a dire quanto la persistenza 0,85/0,08 e la regola a soglie fisse erano lontane dalle
 * frequenze vere. Non sono mai candidati a migliore baseline.
 */
object LegacyRules {

  /** 0,85 se nell'ultimo slot chiuso pioveva, 0,08 se no; senza il dato, il tasso dato. */
  fun fixedPersistence(lastHourMm: Double?, fallbackRate: Double): Double {
    if (lastHourMm == null || lastHourMm.isNaN()) return fallbackRate
    return if (RainWindows.isWet(lastHourMm)) 0.85 else 0.08
  }

  /**
   * La regola di prima sulla tendenza del filtro: sotto -1,16 hPa/h 0,75; sotto -0,53 due volte e
   * mezza il tasso (al massimo 0,6); sopra +0,53 quattro decimi del tasso (almeno 0,02); altrimenti il tasso.
   */
  fun fixedBarometric(rate: Double, trendHpaPerHour: Double?): Double {
    val trend = trendHpaPerHour?.takeUnless { it.isNaN() } ?: return rate
    return when {
      trend <= -1.16 -> 0.75
      trend <= -0.53 -> (rate * 2.5).coerceAtMost(0.6)
      trend >= 0.53 -> (rate * 0.4).coerceAtLeast(0.02)
      else -> rate
    }
  }
}
