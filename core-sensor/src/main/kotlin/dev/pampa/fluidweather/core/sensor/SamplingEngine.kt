package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.PressureTrend
import dev.pampa.fluidweather.core.model.SampleSource
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Un giro di campionamento completo: leggi, arricchisci, archivia. E' l'unico pezzo che worker,
 * allarmi, servizio di sorveglianza e raffica manuale condividono, cosi' un campione e' identico
 * qualunque strada l'abbia raccolto.
 */
class SamplingEngine(
  private val barometer: Barometer,
  private val locationProvider: LocationProvider,
  private val activityStore: LatestActivityStore,
  private val repository: PressureRepository,
  private val settingsStore: SamplingSettingsStore,
  private val surveillance: SurveillanceController,
) {

  /** Il giro periodico: raffica o lettura secca secondo la modalita', poi il cambio di marcia. */
  suspend fun runScheduledPass() {
    val mode = settingsStore.current().mode
    collect(source = SampleSource.PERIODIC, durationSeconds = mode.burstSeconds)
    if (mode.surveillanceCapable && PressureTrend.callsForSurveillance(currentTrend())) {
      surveillance.start()
    }
  }

  /**
   * Registra letture per [durationSeconds] (0 = una sola) e restituisce quante ne ha archiviate.
   * Ogni lettura e' inserita appena arriva: una raffica interrotta a meta' lascia in archivio
   * la meta' che esiste.
   */
  suspend fun collect(
    source: SampleSource,
    durationSeconds: Int,
    onProgress: ((completedSeconds: Int, totalSeconds: Int) -> Unit)? = null,
  ): Int = coroutineScope {
    if (!barometer.isAvailable) return@coroutineScope 0
    val locationDeferred = async { locationProvider.snapshot() }
    val activity = freshActivity()
    var stored = 0
    if (durationSeconds <= 0) {
      val reading = barometer.single() ?: return@coroutineScope 0
      val location = locationDeferred.await()
      repository.record(listOf(reading.toSample(source, burstId = null, location, activity)))
      stored = 1
    } else {
      val burstId = UUID.randomUUID().toString()
      // La posizione prima della raffica: costa qualche secondo di attesa, ma tutti i campioni
      // della raffica condividono lo stesso contesto invece di un contesto arrivato a meta'.
      val location = locationDeferred.await()
      withTimeoutOrNull((durationSeconds + BURST_GRACE_SECONDS) * 1_000L) {
        barometer.burst(durationSeconds).collect { reading ->
          repository.record(listOf(reading.toSample(source, burstId, location, activity)))
          stored++
          onProgress?.invoke(stored, durationSeconds)
        }
      }
    }
    stored
  }

  /** La tendenza grezza dell'ultima ora, per il cambio di marcia e per la diagnostica. */
  suspend fun currentTrend(): Double? =
    PressureTrend.hPaPerHour(repository.samplesSince(System.currentTimeMillis() - 3_600_000L))

  private suspend fun freshActivity(): Pair<ActivityKind, Int?> {
    val latest = activityStore.current() ?: return ActivityKind.UNKNOWN to null
    val stale = System.currentTimeMillis() - latest.observedAtMillis > ACTIVITY_FRESHNESS_MILLIS
    return if (stale) ActivityKind.UNKNOWN to null else latest.kind to latest.confidence
  }

  private fun TimedReading.toSample(
    source: SampleSource,
    burstId: String?,
    location: LocationSnapshot?,
    activity: Pair<ActivityKind, Int?>,
  ) = PressureSample(
    timestampMillis = timestampMillis,
    pressureHpa = pressureHpa,
    source = source,
    burstId = burstId,
    altitudeMeters = location?.altitudeMeters,
    latitude = location?.latitude,
    longitude = location?.longitude,
    activity = activity.first,
    activityConfidence = activity.second,
  )

  private companion object {
    /** Un riconoscimento piu' vecchio di cosi' non descrive piu' questo campione. */
    const val ACTIVITY_FRESHNESS_MILLIS = 10 * 60_000L

    /** Margine oltre la durata nominale prima di considerare il sensore ammutolito. */
    const val BURST_GRACE_SECONDS = 10
  }
}
