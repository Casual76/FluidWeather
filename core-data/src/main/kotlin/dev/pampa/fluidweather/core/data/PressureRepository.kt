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

  /** Dati e privacy: l'archivio intero. */
  suspend fun clear() = dao.deleteOlderThan(Long.MAX_VALUE)

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
