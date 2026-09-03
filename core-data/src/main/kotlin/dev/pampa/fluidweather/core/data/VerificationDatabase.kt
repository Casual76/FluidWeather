package dev.pampa.fluidweather.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.VerificationStore

/** Una previsione in attesa del suo giudizio (fusione, fase 7). */
@Entity(
  tableName = "pending_predictions",
  primaryKeys = ["providerId", "variable", "targetTimestampMillis"],
  indices = [Index("targetTimestampMillis")],
)
data class PendingPredictionEntity(
  val providerId: String,
  val variable: String,
  val targetTimestampMillis: Long,
  val predictedValue: Double,
  val issuedAtMillis: Long,
)

/** Un giudizio emesso: errore assoluto contro la mediana delle analisi. */
@Entity(tableName = "forecast_verifications", indices = [Index("variable", "horizonBucket")])
data class ForecastVerificationEntity(
  @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
  val providerId: String,
  val variable: String,
  val horizonBucket: String,
  val absoluteError: Double,
  val verifiedAtMillis: Long,
)

@Dao
interface VerificationDao {

  @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
  suspend fun addPending(predictions: List<PendingPredictionEntity>)

  @Query("SELECT * FROM pending_predictions WHERE targetTimestampMillis <= :nowMillis")
  suspend fun duePending(nowMillis: Long): List<PendingPredictionEntity>

  @Query(
    "DELETE FROM pending_predictions WHERE providerId = :providerId AND variable = :variable " +
      "AND targetTimestampMillis = :targetTimestampMillis",
  )
  suspend fun removePending(providerId: String, variable: String, targetTimestampMillis: Long)

  @Insert
  suspend fun addVerifications(verifications: List<ForecastVerificationEntity>)

  @Query("SELECT * FROM forecast_verifications WHERE variable = :variable AND horizonBucket = :bucket")
  suspend fun verificationsFor(variable: String, bucket: String): List<ForecastVerificationEntity>

  @Query("DELETE FROM forecast_verifications WHERE verifiedAtMillis < :beforeMillis")
  suspend fun pruneOlderThan(beforeMillis: Long)

  @Query("SELECT * FROM forecast_verifications WHERE verifiedAtMillis >= :sinceMillis ORDER BY verifiedAtMillis ASC")
  suspend fun allSince(sinceMillis: Long): List<ForecastVerificationEntity>

  @Query("DELETE FROM pending_predictions")
  suspend fun clearPending()

  @Query("DELETE FROM forecast_verifications")
  suspend fun clearVerifications()
}

/** L'implementazione Room del magazzino: la matematica dei pesi non sa che esiste. */
class RoomVerificationStore(private val dao: VerificationDao) : VerificationStore {

  override suspend fun addPending(predictions: List<PendingPrediction>) {
    if (predictions.isEmpty()) return
    dao.addPending(
      predictions.map {
        PendingPredictionEntity(
          providerId = it.providerId,
          variable = it.variable,
          targetTimestampMillis = it.targetTimestampMillis,
          predictedValue = it.predictedValue,
          issuedAtMillis = it.issuedAtMillis,
        )
      },
    )
  }

  override suspend fun duePending(nowMillis: Long): List<PendingPrediction> =
    dao.duePending(nowMillis).map {
      PendingPrediction(
        providerId = it.providerId,
        variable = it.variable,
        targetTimestampMillis = it.targetTimestampMillis,
        predictedValue = it.predictedValue,
        issuedAtMillis = it.issuedAtMillis,
      )
    }

  override suspend fun removePending(predictions: List<PendingPrediction>) {
    for (prediction in predictions) {
      dao.removePending(prediction.providerId, prediction.variable, prediction.targetTimestampMillis)
    }
  }

  override suspend fun addVerifications(verifications: List<ForecastVerification>) {
    if (verifications.isEmpty()) return
    dao.addVerifications(
      verifications.map {
        ForecastVerificationEntity(
          providerId = it.providerId,
          variable = it.variable,
          horizonBucket = it.horizonBucket.name,
          absoluteError = it.absoluteError,
          verifiedAtMillis = it.verifiedAtMillis,
        )
      },
    )
  }

  override suspend fun verificationsFor(
    variable: String,
    bucket: HorizonBucket,
  ): List<ForecastVerification> =
    dao.verificationsFor(variable, bucket.name).map { it.toModel() }

  override suspend fun allVerifications(sinceMillis: Long): List<ForecastVerification> =
    dao.allSince(sinceMillis).map { it.toModel() }

  /**
   * Butta le verifiche piu' vecchie di [KEEP_MILLIS].
   *
   * Centottanta giorni, e il numero non e' arbitrario: il punteggio dei provider pesa ogni
   * verifica con un decadimento a emivita quattordici giorni, quindi a centottanta giorni una
   * verifica conta `0,5^12,9`, cioe' lo 0,013% di una di oggi. Potare li' e' aritmeticamente
   * invisibile alla classifica, e la tabella smette di crescere per sempre.
   */
  suspend fun prune(nowMillis: Long) = dao.pruneOlderThan(nowMillis - KEEP_MILLIS)

  /** Dati e privacy: via tutto, pendenti comprese. */
  suspend fun clear() {
    dao.clearPending()
    dao.clearVerifications()
  }

  private fun ForecastVerificationEntity.toModel() = ForecastVerification(
    providerId = providerId,
    variable = variable,
    horizonBucket = HorizonBucket.valueOf(horizonBucket),
    absoluteError = absoluteError,
    verifiedAtMillis = verifiedAtMillis,
  )

  companion object {
    /** Sei mesi: oltre, il decadimento a emivita 14 giorni ha gia' azzerato il peso. */
    const val KEEP_MILLIS: Long = 180L * 24 * 3_600_000L
  }
}
