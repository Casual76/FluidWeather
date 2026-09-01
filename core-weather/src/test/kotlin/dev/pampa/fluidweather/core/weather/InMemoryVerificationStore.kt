package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.VerificationStore

/** Il magazzino in memoria dei test: stessa interfaccia di Room, zero Android. */
class InMemoryVerificationStore : VerificationStore {

  val pending = mutableListOf<PendingPrediction>()
  val verifications = mutableListOf<ForecastVerification>()

  override suspend fun addPending(predictions: List<PendingPrediction>) {
    for (prediction in predictions) {
      pending.removeAll {
        it.providerId == prediction.providerId &&
          it.variable == prediction.variable &&
          it.targetTimestampMillis == prediction.targetTimestampMillis
      }
      pending += prediction
    }
  }

  override suspend fun duePending(nowMillis: Long): List<PendingPrediction> =
    pending.filter { it.targetTimestampMillis <= nowMillis }

  override suspend fun removePending(predictions: List<PendingPrediction>) {
    pending.removeAll { candidate ->
      predictions.any {
        it.providerId == candidate.providerId &&
          it.variable == candidate.variable &&
          it.targetTimestampMillis == candidate.targetTimestampMillis
      }
    }
  }

  override suspend fun addVerifications(added: List<ForecastVerification>) {
    verifications += added
  }

  override suspend fun verificationsFor(
    variable: String,
    bucket: HorizonBucket,
  ): List<ForecastVerification> =
    verifications.filter { it.variable == variable && it.horizonBucket == bucket }
}
