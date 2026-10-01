package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.RoomRainEventStore
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.core.model.RainEventStore
import dev.pampa.fluidweather.core.model.RainEventVerification

/**
 * Il magazzino della pioggia in memoria dei test: stessa interfaccia e stessa semantica di Room
 * (chiave = posto + giro + id + finestra, IGNORE sui doppioni, giudizio prima e pendente dopo),
 * zero Android.
 */
class InMemoryRainEventStore : RainEventStore {

  val pending = mutableListOf<RainEventPending>()
  val verified = mutableListOf<RainEventVerification>()

  private fun keyOf(row: RainEventPending) = listOf(row.placeKey, row.roundId, row.providerId, row.window)

  override suspend fun addPending(rows: List<RainEventPending>) {
    for (row in rows) {
      if (pending.none { keyOf(it) == keyOf(row) }) pending += row
    }
  }

  override suspend fun pendingIssuedBefore(issuedBeforeMillis: Long): List<RainEventPending> =
    pending.filter { it.issuedAtMillis <= issuedBeforeMillis }.sortedBy { it.issuedAtMillis }

  override suspend fun settle(verifications: List<RainEventVerification>) {
    for (verification in verifications) {
      if (verified.none { keyOf(it.prediction) == keyOf(verification.prediction) }) verified += verification
    }
    val settledKeys = verifications.map { keyOf(it.prediction) }.toSet()
    pending.removeAll { keyOf(it) in settledKeys }
  }

  override suspend fun expirePending(issuedBeforeMillis: Long): Int {
    val before = pending.size
    pending.removeAll { it.issuedAtMillis < issuedBeforeMillis }
    return before - pending.size
  }

  override suspend fun verifications(modelVersion: String, sinceMillis: Long): List<RainEventVerification> =
    verified
      .filter { it.prediction.modelVersion == modelVersion && it.prediction.issuedAtMillis >= sinceMillis }
      .sortedBy { it.prediction.issuedAtMillis }

  override suspend fun allVerifications(): List<RainEventVerification> =
    verified.sortedBy { it.prediction.issuedAtMillis }

  override suspend fun verificationsPage(offset: Int, limit: Int): List<RainEventVerification> =
    verified
      .sortedWith(
        compareBy<RainEventVerification>(
          { it.prediction.placeKey },
          { it.prediction.roundId },
          { it.prediction.providerId },
          { it.prediction.window },
        ),
      )
      .drop(offset)
      .take(limit)

  override suspend fun pendingCount(): Int = pending.size

  override suspend fun verificationCount(): Int = verified.size

  override suspend fun prune(nowMillis: Long) {
    verified.removeAll { it.prediction.issuedAtMillis < nowMillis - RoomRainEventStore.KEEP_MILLIS }
    pending.removeAll { it.issuedAtMillis < nowMillis - RoomRainEventStore.PENDING_BACKSTOP_MILLIS }
  }

  override suspend fun clear() {
    pending.clear()
    verified.clear()
  }
}
