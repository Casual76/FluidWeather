package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.VerificationStore
import kotlin.math.abs

/**
 * La verifica automatica: ogni fetch semina previsioni "in attesa" a +1/+3/+6/+12/+24 ore, e
 * quando quelle ore sono passate le giudica contro la verita' di riferimento.
 *
 * La verita' automatica e' la MEDIANA delle analisi dei provider per quell'ora (l'ora appena
 * passata di un fetch fresco e' analisi, non previsione): robusta, senza un giudice unico che
 * possa portare acqua al suo mulino. Serve il quorum di [minTruthOpinions] opinioni — sotto,
 * meglio nessun giudizio che un giudizio di parte. Il barometro locale entrera' in classifica
 * alla pari sulla pressione nella pagina Benchmark (fase 13).
 */
class ForecastVerifier(
  private val store: VerificationStore,
  private val minTruthOpinions: Int = 3,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  /** Le previsioni dei bundle appena arrivati entrano in attesa di giudizio. */
  suspend fun registerPending(fetches: List<ProviderFetch>) {
    val now = clock()
    val pending = mutableListOf<PendingPrediction>()
    for (fetch in fetches) {
      val bundle = fetch.bundle ?: continue
      for (horizonHours in HORIZONS) {
        val target = now + horizonHours * 3_600_000L
        val point = bundle.at(target) ?: continue
        for (variable in FusionVariables.all) {
          val value = FusionVariables.of(point, variable) ?: continue
          pending += PendingPrediction(
            providerId = bundle.providerId,
            variable = variable,
            targetTimestampMillis = point.timestampMillis,
            predictedValue = value,
            issuedAtMillis = now,
          )
        }
      }
    }
    store.addPending(pending)
  }

  /** Giudica tutte le previsioni scadute usando i bundle freschi come fonte di verita'. */
  suspend fun settle(fetches: List<ProviderFetch>) {
    val now = clock()
    val due = store.duePending(now)
    if (due.isEmpty()) return

    val bundles = fetches.mapNotNull { it.bundle }
    val verifications = mutableListOf<ForecastVerification>()
    val settled = mutableListOf<PendingPrediction>()

    for (prediction in due) {
      val truth = medianTruth(bundles, prediction.variable, prediction.targetTimestampMillis)
      if (truth == null) {
        // Nessun quorum (offline, ora troppo vecchia per le analisi): la si lascia decadere
        // quando e' piu' vecchia dell'orizzonte delle analisi, senza inventare un giudizio.
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

  private companion object {
    val HORIZONS = listOf(1, 3, 6, 12, 24)

    /** Oltre sei ore nel passato le analisi dei bundle freschi non arrivano piu'. */
    const val TRUTH_WINDOW_MILLIS = 6 * 3_600_000L
  }
}
