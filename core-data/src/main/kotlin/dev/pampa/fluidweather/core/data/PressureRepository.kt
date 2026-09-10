package dev.pampa.fluidweather.core.data

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** L'unico punto da cui si scrive e si legge l'archivio barometrico. */
class PressureRepository(private val dao: PressureDao) {

  suspend fun record(samples: List<PressureSample>) {
    if (samples.isEmpty()) return
    dao.insertAll(samples.map { it.toEntity() })
  }

  suspend fun samplesSince(sinceMillis: Long): List<PressureSample> =
    dao.samplesSince(sinceMillis).map { it.toModel() }

  fun latest(limit: Int): Flow<List<PressureSample>> =
    dao.latest(limit).map { entities -> entities.map { it.toModel() } }

  fun count(): Flow<Long> = dao.count()

  /** La media oraria della pressione di stazione dall'istante dato a oggi; null se non c'e' nulla. */
  suspend fun averageStationPressureSince(sinceMillis: Long): Double? =
    dao.averageHourlyPressureSince(sinceMillis)

  /** Da quando l'archivio parla: serve a sapere se la normale ha abbastanza storia dietro. */
  suspend fun oldestSampleMillis(): Long? = dao.oldestTimestamp()

  /** L'ultima lettura in archivio: il segno vitale del campionamento (vedi SamplingHealth). */
  suspend fun latestSampleMillis(): Long? = dao.newestTimestamp()

  /** I campioni di una raffica, per id: serve a ritentare una stima di taratura gia' raccolta. */
  suspend fun samplesOfBurst(burstId: String): List<PressureSample> =
    dao.samplesOfBurst(burstId).map { it.toModel() }

  /** Dati e privacy: l'archivio intero. */
  suspend fun clear() = dao.deleteOlderThan(Long.MAX_VALUE)

  /** Butta i campioni piu' vecchi di [KEEP_MILLIS]. */
  suspend fun prune(nowMillis: Long) = dao.deleteOlderThan(nowMillis - KEEP_MILLIS)

  companion object {
    /**
     * Trenta giorni di campioni grezzi.
     *
     * La finestra piu' lunga che qualcuno chiede davvero e' sette giorni (la pagina Pressione); il
     * nowcast ne usa ventiquattro ore. Trenta e' quattro volte la piu' lunga, e sopra c'e' un
     * tetto da dichiarare invece che da scoprire: in modalita' massima sono 288 passaggi al giorno
     * per 30 letture a raffica, cioe' ~8.600 righe al giorno, ~260.000 righe e qualche decina di
     * megabyte. La memoria lunga dell'app non sta qui: sta nell'archivio dell'apprendimento, che
     * tiene due anni di vettori gia' ridotti.
     */
    const val KEEP_MILLIS: Long = 30L * 24 * 3_600_000L
  }

  private fun PressureSample.toEntity() = PressureSampleEntity(
    timestampMillis = timestampMillis,
    pressureHpa = pressureHpa,
    source = source.name,
    burstId = burstId,
    altitudeMeters = altitudeMeters,
    latitude = latitude,
    longitude = longitude,
    activity = activity.name,
    activityConfidence = activityConfidence,
  )

  private fun PressureSampleEntity.toModel() = PressureSample(
    timestampMillis = timestampMillis,
    pressureHpa = pressureHpa,
    // Un name() che non esiste piu' (refactor futuro) degrada al valore neutro invece di rompere
    // la lettura di un archivio che l'utente ha accumulato per mesi.
    source = runCatching { SampleSource.valueOf(source) }.getOrDefault(SampleSource.PERIODIC),
    burstId = burstId,
    altitudeMeters = altitudeMeters,
    latitude = latitude,
    longitude = longitude,
    activity = runCatching { ActivityKind.valueOf(activity) }.getOrDefault(ActivityKind.UNKNOWN),
    activityConfidence = activityConfidence,
  )
}
