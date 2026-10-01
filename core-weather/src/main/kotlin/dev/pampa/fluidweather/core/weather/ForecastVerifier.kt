package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.VerificationStore
import kotlin.math.abs

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
 * La verita' automatica e' la MEDIANA delle analisi dei provider per quell'ora (l'ora appena
 * passata di un fetch fresco e' analisi, non previsione): robusta, senza un giudice unico che
 * possa portare acqua al suo mulino. Serve il quorum di [minTruthOpinions] opinioni — sotto,
 * meglio nessun giudizio che un giudizio di parte — e le opinioni devono essere **del posto della
 * previsione** (entro [SAME_PLACE_KM]): la mediana di Firenze non e' la verita' di Prato.
 *
 * L'evento pioggia non passa piu' di qui: lo iscrive [LocalRoundRegistrar] e lo giudica
 * [TruthPanelSettler].
 */
class ForecastVerifier(
  private val store: VerificationStore,
  private val minTruthOpinions: Int = 3,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  /**
   * Le previsioni dei bundle appena arrivati entrano in attesa di giudizio, col punto del bundle:
   * chi le giudichera' usera' solo bundle di li'.
   */
  suspend fun registerPending(fetches: List<ProviderFetch>) {
    val now = clock()
    val pending = mutableListOf<PendingPrediction>()
    for (fetch in fetches) {
      val bundle = fetch.bundle ?: continue
      for (horizonHours in HORIZONS) {
        val target = now + horizonHours * 3_600_000L
        val point = bundle.at(target) ?: continue
        for (variable in FusionVariables.verified) {
          val value = FusionVariables.of(point, variable) ?: continue
          pending += PendingPrediction(
            providerId = bundle.providerId,
            variable = variable,
            targetTimestampMillis = point.timestampMillis,
            predictedValue = value,
            issuedAtMillis = now,
            latitude = bundle.latitude,
            longitude = bundle.longitude,
          )
        }
      }
    }
    store.addPending(pending)
  }

  /**
   * Giudica tutte le previsioni scadute usando come verita' i bundle freschi **dello stesso
   * posto**.
   *
   * Prima la mediana si prendeva dai bundle del giro, ovunque fosse: una previsione seminata a casa
   * e giudicata in ufficio a dieci chilometri spostava i pesi della fusione con la verita' di un
   * altro posto. Le righe scritte prima che il punto si registrasse (latitudine null) si giudicano
   * come allora finche' non scadono. Le righe dell'evento pioggia rimaste nelle tabelle generali si
   * tolgono senza giudizio: la loro verita' era quella circolare, e la classifica pioggia ora vive
   * altrove.
   */
  suspend fun settle(fetches: List<ProviderFetch>) {
    val now = clock()
    val due = store.duePending(now)
    if (due.isEmpty()) return

    val bundles = fetches.mapNotNull { it.bundle }
    val verifications = mutableListOf<ForecastVerification>()
    val settled = mutableListOf<PendingPrediction>()

    for (prediction in due) {
      if (RainEvent.isRainEvent(prediction.variable)) {
        settled += prediction
        continue
      }
      val nearby = bundles.filter { it.isNear(prediction) }
      val truth = medianTruth(nearby, prediction.variable, prediction.targetTimestampMillis)
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
      )
      settled += prediction
    }

    store.addVerifications(verifications)
    store.removePending(settled)
  }

  private fun medianTruth(bundles: List<ForecastBundle>, variable: String, timestampMillis: Long): Double? {
    val opinions = bundles.mapNotNull { bundle ->
      bundle.at(timestampMillis)?.let { FusionVariables.of(it, variable) }
    }
    if (opinions.size < minTruthOpinions) return null
    val sorted = opinions.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
  }

  /** Il bundle e' del posto della previsione? Una riga senza punto (legacy) accetta tutti. */
  private fun ForecastBundle.isNear(prediction: PendingPrediction): Boolean {
    val latitude = prediction.latitude ?: return true
    val longitude = prediction.longitude ?: return true
    return haversineKm(latitude, longitude, this.latitude, this.longitude) <= SAME_PLACE_KM
  }

  companion object {
    /** Entro quanto un bundle e' "dello stesso posto" della previsione: il raggio del refresher. */
    const val SAME_PLACE_KM: Double = WeatherSnapshotRefresher.MAX_DISTANCE_KM

    private val HORIZONS = listOf(1, 3, 6, 12, 24)

    /** Oltre sei ore nel passato le analisi dei bundle freschi non arrivano piu'. */
    private const val TRUTH_WINDOW_MILLIS = 6 * 3_600_000L
  }
}
