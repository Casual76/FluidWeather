package dev.pampa.fluidweather.core.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.core.model.RainEventStore
import dev.pampa.fluidweather.core.model.RainEventVerification

/**
 * Una previsione di pioggia in attesa del suo giudice.
 *
 * Tabella nuova e non una colonna in piu' di `pending_predictions`: la chiave di quella e'
 * (provider, variabile, bersaglio) e non sa di posti, di giri, di versioni ne' di livelli — tutto
 * cio' che la verifica onesta della pioggia deve ricordare. La finestra si chiama `windowLabel`
 * e non `window` perche' WINDOW e' una parola riservata di SQLite, e ogni @Query scritta a mano
 * dovrebbe ricordarsi le virgolette.
 *
 * L'ordine dei campi E' l'ordine delle colonne: la migrazione 6->7 la scrive a mano sullo stesso
 * ordine, e MigrationTest la confronta con la fotografia dello schema.
 */
@Entity(
  tableName = "rain_event_pending",
  primaryKeys = ["placeKey", "roundId", "providerId", "windowLabel"],
  indices = [Index("issuedAtMillis")],
)
data class RainEventPendingEntity(
  val placeKey: String,
  val latitude: Double,
  val longitude: Double,
  val roundId: Long,
  val providerId: String,
  val windowLabel: String,
  val issuedAtMillis: Long,
  val probability: Double,
  val modelVersion: String,
  val tier: String?,
)

/** Una previsione di pioggia giudicata: la pendente piu' l'esito e cio' che l'ha deciso. */
@Entity(
  tableName = "rain_event_verifications",
  primaryKeys = ["placeKey", "roundId", "providerId", "windowLabel"],
  indices = [Index("modelVersion", "issuedAtMillis"), Index("issuedAtMillis")],
)
data class RainEventVerificationEntity(
  val placeKey: String,
  val latitude: Double,
  val longitude: Double,
  val roundId: Long,
  val providerId: String,
  val windowLabel: String,
  val issuedAtMillis: Long,
  val probability: Double,
  val modelVersion: String,
  val tier: String?,
  val outcome: Boolean,
  val truthSumMm: Double?,
  val truthVoters: Int,
  val truthSource: String,
  val settledAtMillis: Long,
)

@Dao
interface RainEventDao {

  /** IGNORE: una chiave gia' presente e' lo stesso giro riscritto, non una previsione nuova. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun addPending(rows: List<RainEventPendingEntity>)

  @Query("SELECT * FROM rain_event_pending WHERE issuedAtMillis <= :issuedBeforeMillis ORDER BY issuedAtMillis ASC")
  suspend fun pendingIssuedBefore(issuedBeforeMillis: Long): List<RainEventPendingEntity>

  /** Per chiave primaria composta: Room cancella la riga con gli stessi quattro campi chiave. */
  @Delete
  suspend fun removePending(rows: List<RainEventPendingEntity>)

  @Query("DELETE FROM rain_event_pending WHERE issuedAtMillis < :beforeMillis")
  suspend fun expirePending(beforeMillis: Long): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun addVerifications(rows: List<RainEventVerificationEntity>)

  @Query(
    "SELECT * FROM rain_event_verifications WHERE modelVersion = :modelVersion " +
      "AND issuedAtMillis >= :sinceMillis ORDER BY issuedAtMillis ASC",
  )
  suspend fun verificationsSince(modelVersion: String, sinceMillis: Long): List<RainEventVerificationEntity>

  @Query("SELECT * FROM rain_event_verifications ORDER BY issuedAtMillis ASC")
  suspend fun allVerifications(): List<RainEventVerificationEntity>

  /** Nell'ordine della chiave primaria: lo percorre il suo indice, e una pagina non salta righe. */
  @Query(
    "SELECT * FROM rain_event_verifications ORDER BY placeKey, roundId, providerId, windowLabel " +
      "LIMIT :limit OFFSET :offset",
  )
  suspend fun verificationsPage(offset: Int, limit: Int): List<RainEventVerificationEntity>

  @Query("SELECT COUNT(*) FROM rain_event_pending")
  suspend fun pendingCount(): Int

  @Query("SELECT COUNT(*) FROM rain_event_verifications")
  suspend fun verificationCount(): Int

  @Query("DELETE FROM rain_event_verifications WHERE issuedAtMillis < :beforeMillis")
  suspend fun pruneVerifications(beforeMillis: Long)

  @Query("DELETE FROM rain_event_pending")
  suspend fun clearPending()

  @Query("DELETE FROM rain_event_verifications")
  suspend fun clearVerifications()
}

/** L'implementazione Room del magazzino della pioggia: la classifica non sa che esiste. */
class RoomRainEventStore(private val dao: RainEventDao) : RainEventStore {

  override suspend fun addPending(rows: List<RainEventPending>) {
    if (rows.isEmpty()) return
    dao.addPending(rows.map { it.toEntity() })
  }

  override suspend fun pendingIssuedBefore(issuedBeforeMillis: Long): List<RainEventPending> =
    dao.pendingIssuedBefore(issuedBeforeMillis).map { it.toModel() }

  /**
   * Prima il giudizio, poi la tolta dalle pendenti. Senza transazione e senza rischi: se il
   * processo muore in mezzo, la riga resta sia pendente sia giudicata, e al giro dopo il giudizio
   * si riscrive (IGNORE: nessun doppione) e la pendente se ne va. Nell'ordine opposto, la stessa
   * morte perderebbe un giudizio per sempre.
   */
  override suspend fun settle(verifications: List<RainEventVerification>) {
    if (verifications.isEmpty()) return
    dao.addVerifications(verifications.map { it.toEntity() })
    dao.removePending(verifications.map { it.prediction.toEntity() })
  }

  override suspend fun expirePending(issuedBeforeMillis: Long): Int = dao.expirePending(issuedBeforeMillis)

  override suspend fun verifications(modelVersion: String, sinceMillis: Long): List<RainEventVerification> =
    dao.verificationsSince(modelVersion, sinceMillis).map { it.toModel() }

  override suspend fun allVerifications(): List<RainEventVerification> =
    dao.allVerifications().map { it.toModel() }

  override suspend fun verificationsPage(offset: Int, limit: Int): List<RainEventVerification> =
    dao.verificationsPage(offset, limit).map { it.toModel() }

  override suspend fun pendingCount(): Int = dao.pendingCount()

  override suspend fun verificationCount(): Int = dao.verificationCount()

  /**
   * Butta i giudizi oltre [KEEP_MILLIS], e le pendenti rimaste oltre otto giorni.
   *
   * Le pendenti le scade gia' chi giudica, a sette giorni: questa e' la rete di sicurezza di chi
   * non giudica mai (niente rete per una settimana, app tenuta ferma), perche' una tabella che
   * cresce per sempre e' un bug anche quando nessuno la guarda.
   */
  override suspend fun prune(nowMillis: Long) {
    dao.pruneVerifications(nowMillis - KEEP_MILLIS)
    dao.expirePending(nowMillis - PENDING_BACKSTOP_MILLIS)
  }

  /** Dati e privacy: via tutto, pendenti comprese. */
  override suspend fun clear() {
    dao.clearPending()
    dao.clearVerifications()
  }

  private fun RainEventPending.toEntity() = RainEventPendingEntity(
    placeKey = placeKey,
    latitude = latitude,
    longitude = longitude,
    roundId = roundId,
    providerId = providerId,
    windowLabel = window,
    issuedAtMillis = issuedAtMillis,
    probability = probability,
    modelVersion = modelVersion,
    tier = tier,
  )

  private fun RainEventPendingEntity.toModel() = RainEventPending(
    placeKey = placeKey,
    latitude = latitude,
    longitude = longitude,
    roundId = roundId,
    providerId = providerId,
    window = windowLabel,
    issuedAtMillis = issuedAtMillis,
    probability = probability,
    modelVersion = modelVersion,
    tier = tier,
  )

  private fun RainEventVerification.toEntity() = RainEventVerificationEntity(
    placeKey = prediction.placeKey,
    latitude = prediction.latitude,
    longitude = prediction.longitude,
    roundId = prediction.roundId,
    providerId = prediction.providerId,
    windowLabel = prediction.window,
    issuedAtMillis = prediction.issuedAtMillis,
    probability = prediction.probability,
    modelVersion = prediction.modelVersion,
    tier = prediction.tier,
    outcome = rained,
    truthSumMm = truthSumMm,
    truthVoters = truthVoters,
    truthSource = truthSource,
    settledAtMillis = settledAtMillis,
  )

  private fun RainEventVerificationEntity.toModel() = RainEventVerification(
    prediction = RainEventPending(
      placeKey = placeKey,
      latitude = latitude,
      longitude = longitude,
      roundId = roundId,
      providerId = providerId,
      window = windowLabel,
      issuedAtMillis = issuedAtMillis,
      probability = probability,
      modelVersion = modelVersion,
      tier = tier,
    ),
    rained = outcome,
    truthSumMm = truthSumMm,
    truthVoters = truthVoters,
    truthSource = truthSource,
    settledAtMillis = settledAtMillis,
  )

  companion object {
    /**
     * Sei mesi: la classifica guarda sessanta giorni e le pendenti vivono fino a sette, quindi
     * centottanta lasciano il triplo di margine e un export che racconta una stagione intera.
     * Senza decadimento nella classifica, non c'e' un'aritmetica che imponga di piu'.
     */
    const val KEEP_MILLIS: Long = 180L * 24 * 3_600_000L

    /** Otto giorni: un giorno oltre la scadenza a sette di chi giudica. */
    const val PENDING_BACKSTOP_MILLIS: Long = 8L * 24 * 3_600_000L
  }
}
