package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.ForecastLearning
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.VerificationStore
import kotlin.math.abs
import dev.pampa.fluidweather.core.data.DataWipeGuard
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * L'evento "piove?" nelle tabelle generali delle verifiche: **legacy**.
 *
 * Qui l'evento pioggia si registrava e si giudicava insieme alle variabili dei provider, con la
 * verita' presa dagli stessi provider in classifica. Oggi vive nelle sue tabelle
 * (`rain_event_pending` / `rain_event_verifications`), giudicato dal pannello dei giudici
 * ([TruthPanelSettler]) e letto dalla [RainBoard]. Le righe vecchie restano nel database, non si
 * giudicano piu' ([ForecastVerifier.settle] le scarta) e non si mostrano: di tutto il vecchio
 * vocabolario (finestre, soglia di pioggia, id del barometro) resta solo il prefisso con cui si
 * riconoscono, perche' la classifica e le sue finestre stanno in [RainBoard] e [RainBoardIds].
 */
object RainEvent {
  const val PREFIX = "rain_event_"

  fun isRainEvent(variable: String): Boolean = variable.startsWith(PREFIX)
}

/**
 * La verifica automatica delle variabili dei provider: ogni fetch semina previsioni "in attesa" a
 * +1/+3/+6/+12/+24 ore, e quando quelle ore sono passate le giudica contro la verita' di
 * riferimento. Sono questi giudizi a muovere i pesi della fusione.
 *
 * Il riferimento e' un CONSENSO DI ANALISI DI MODELLI, non una misura sul terreno. Si accettano
 * solo modelli espliciti che il client richiede con ore passate, acquisiti dopo il target e non
 * nel futuro del chiamante, sul timestamp/intervallo esatto. Il soggetto non giudica se stesso;
 * best_match non conta come voto indipendente da modelli che puo' aver selezionato. Ogni modello
 * ha al massimo un voto. Serve ancora il quorum di [minTruthOpinions] peer ammessi e dello stesso
 * posto (entro [SAME_PLACE_KM]); sotto quorum la previsione resta pending fino alla sua scadenza.
 *
 * L'evento pioggia non passa piu' di qui: lo iscrive [LocalRoundRegistrar] e lo giudica
 * [TruthPanelSettler].
 */
class ForecastVerifier(
  private val store: VerificationStore,
  private val minTruthOpinions: Int = 3,
  private val clock: () -> Long = System::currentTimeMillis,
  private val wipeGuard: DataWipeGuard? = null,
) {
  constructor(store: VerificationStore, minTruthOpinions: Int = 3, clock: () -> Long) :
    this(store, minTruthOpinions, clock, null)

  /**
   * Le previsioni dei bundle appena arrivati entrano in attesa di giudizio, col punto del bundle:
   * chi le giudichera' usera' solo bundle di li'.
   */
  suspend fun registerPending(fetches: List<ProviderFetch>) {
    val epoch = weatherWipeEpoch(wipeGuard)
    epoch?.checkCurrent()
    val now = clock()
    val pending = mutableListOf<PendingPrediction>()
    for (fetch in fetches) {
      val bundle = fetch.bundle ?: continue
      if (fetch.error != null || bundle.fetchedAtMillis !in 0L..now || !validForecastPoint(bundle.latitude, bundle.longitude)) continue
      val products = ForecastProducts(bundle)
      for (horizonHours in HORIZONS) {
        val target = now + horizonHours * 3_600_000L
        for (variable in FusionVariables.verified) {
          // Select an actual source endpoint after the requested horizon, never a neighbouring
          // value relabelled as that endpoint. A gap longer than one hour is not allocated.
          val (at, value) = products.firstValueAtOrAfter(target, variable, ForecastProducts.HOUR_MILLIS) ?: continue
          pending += PendingPrediction(
            providerId = bundle.providerId,
            variable = variable,
            targetTimestampMillis = at,
            predictedValue = value,
            issuedAtMillis = bundle.fetchedAtMillis,
            latitude = bundle.latitude,
            longitude = bundle.longitude,
            semanticsVersion = ForecastLearning.CURRENT_SEMANTICS,
          )
        }
      }
    }
    currentCoroutineContext().ensureActive()
    val unique = pending.distinctBy { listOf(it.providerId, it.variable, it.targetTimestampMillis, it.placeKey, it.semanticsVersion) }
    if (epoch == null) store.addPending(unique) else epoch.write { store.addPending(unique) }
  }

  /**
   * Giudica tutte le previsioni scadute usando come verita' i bundle freschi **dello stesso
   * posto**.
   *
   * Prima la mediana si prendeva dai bundle del giro, ovunque fosse: una previsione seminata a casa
   * e giudicata in ufficio a dieci chilometri spostava i pesi della fusione con la verita' di un
   * altro posto. Le righe di una politica precedente o senza punto restano senza giudizio fino
   * alla normale scadenza: non diventano prove della nuova politica. Le righe dell'evento pioggia si
   * tolgono senza giudizio: la loro verita' era quella circolare, e la classifica pioggia ora vive
   * altrove.
   */
  suspend fun settle(fetches: List<ProviderFetch>) {
    val epoch = weatherWipeEpoch(wipeGuard)
    epoch?.checkCurrent()
    val now = clock()
    val due = store.duePending(now)
    currentCoroutineContext().ensureActive()
    epoch?.checkCurrent()
    if (due.isEmpty()) return

    val analyses = fetches.filter { it.error == null }.mapNotNull { it.bundle }
      .filter { it.providerId in ANALYSIS_MODEL_IDS && it.fetchedAtMillis <= now && validForecastPoint(it.latitude, it.longitude) }
      .groupBy { it.providerId }.values.mapNotNull { copies -> copies.distinct().singleOrNull() }
      .map(::ForecastProducts)
    val verifications = mutableListOf<ForecastVerification>()
    val settled = mutableListOf<PendingPrediction>()

    for (prediction in due) {
      if (RainEvent.isRainEvent(prediction.variable)) {
        settled += prediction
        continue
      }
      if (prediction.semanticsVersion != ForecastLearning.CURRENT_SEMANTICS ||
        !ForecastLearning.validPoint(prediction.latitude, prediction.longitude)) {
        if (now - prediction.targetTimestampMillis > TRUTH_WINDOW_MILLIS) settled += prediction
        continue
      }
      if (!prediction.predictedValue.isFinite()) {
        settled += prediction
        continue
      }
      val nearby = analyses.filter { it.bundle.isNear(prediction) }
      val truth = medianAnalysis(nearby, prediction, now)
      if (truth == null) {
        // Nessun quorum (offline, ora troppo vecchia per le analisi, un altro posto): la si lascia
        // decadere quando e' piu' vecchia dell'orizzonte delle analisi, senza inventare un giudizio.
        if (now - prediction.targetTimestampMillis > TRUTH_WINDOW_MILLIS) settled += prediction
        continue
      }
      verifications += ForecastVerification(
        providerId = prediction.providerId,
        variable = prediction.variable,
        horizonBucket = HorizonBucket.of(prediction.horizonHours),
        absoluteError = abs(prediction.predictedValue - truth),
        verifiedAtMillis = now,
        latitude = prediction.latitude,
        longitude = prediction.longitude,
        semanticsVersion = ForecastLearning.CURRENT_SEMANTICS,
      )
      settled += prediction
    }

    currentCoroutineContext().ensureActive()
    suspend fun commit() {
      store.addVerifications(verifications)
      store.removePending(settled)
    }
    if (epoch == null) commit() else epoch.write { commit() }
  }

  private fun medianAnalysis(analyses: List<ForecastProducts>, prediction: PendingPrediction, now: Long): Double? {
    val target = prediction.targetTimestampMillis
    val opinions = analyses.mapNotNull { product ->
      val bundle = product.bundle
      if (bundle.providerId == prediction.providerId || bundle.fetchedAtMillis <= target || bundle.fetchedAtMillis > now)
        return@mapNotNull null
      product.valueAt(target, prediction.variable)
    }
    if (opinions.size < minTruthOpinions) return null
    val sorted = opinions.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
  }

  /** Il bundle e' del posto noto della previsione? Unknown never supplies local truth. */
  private fun ForecastBundle.isNear(prediction: PendingPrediction): Boolean {
    val latitude = prediction.latitude ?: return false
    val longitude = prediction.longitude ?: return false
    return haversineKm(latitude, longitude, this.latitude, this.longitude) <= SAME_PLACE_KM
  }

  companion object {
    /** Entro quanto un bundle e' "dello stesso posto" della previsione: il raggio del refresher. */
    const val SAME_PLACE_KM: Double = ForecastLearning.LOCAL_RADIUS_KM

    private val HORIZONS = listOf(1, 3, 6, 12, 24)

    /** These explicit forecast calls request past_hours=6; the best_match model is ambiguous. */
    internal val ANALYSIS_MODEL_IDS = setOf(
      ProviderRegistry.OPEN_METEO_ICON, ProviderRegistry.OPEN_METEO_ECMWF,
      ProviderRegistry.OPEN_METEO_GFS, ProviderRegistry.OPEN_METEO_AROME, ProviderRegistry.OPEN_METEO_JMA,
    )

    /** Oltre sei ore nel passato le analisi dei bundle freschi non arrivano piu'. */
    private const val TRUTH_WINDOW_MILLIS = 6 * 3_600_000L
  }
}
